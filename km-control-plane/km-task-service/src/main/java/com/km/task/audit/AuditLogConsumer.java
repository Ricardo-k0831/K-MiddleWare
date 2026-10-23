package com.km.task.audit;

import com.km.audit.AuditEvent;
import com.km.audit.AuditProperties;
import com.km.task.domain.AuditLog;
import com.km.task.mapper.AuditLogMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计事件消费者 —— 四层审计的<b>唯一入库口</b>。
 *
 * <h3>为什么消费者在任务服务里</h3>
 *
 * 因为 {@code audit_log} 在本服务的库里。放在别处的话, 要么给它一份数据库
 * 账号 (又多一处直连), 要么再加一跳 HTTP。现在这样, 加新的采集层不需要
 * 碰数据库 —— 采集方只管往 MQ 发, 落库是这里的事。
 *
 * <p>将来审计量大到需要独立伸缩时, 把这个类和 {@code AuditLogMapper}
 * 一起搬进一个新服务即可, 对采集方零影响 —— 这正是走 MQ 而不是各层直连
 * 数据库换来的东西。
 *
 * <h3>消费从最早开始, 不是从最新开始</h3>
 *
 * 见 {@link #start()} 里的注释 —— 这是个很容易写反、写反了还不报错的地方。
 *
 * <h3>两类失败要分开处理</h3>
 *
 * 一批消息进来, 失败原因决定了该怎么处理:
 * <ul>
 *   <li><b>消息本身解析不了 / 不合契约</b> —— 重投一万次也还是不行, 记下来跳过</li>
 *   <li><b>唯一键冲突</b> —— 这是重投, 正常路径, 去重即可</li>
 *   <li><b>其他写库失败</b> —— 多半是数据库短暂不可用, 重投有意义</li>
 * </ul>
 *
 * 不区分的话, 一条坏消息会把整个消费组卡死在重试上 (RocketMQ 重试 16 次后
 * 丢进死信队列, 但那期间后面的消息全堵着)。审计是旁路, 堵住它就是本末倒置。
 *
 * <p><b>特别注意</b> "是不是重投" 不能靠影响行数判断 —— 踩过这个坑,
 * 详见 {@code AuditLogMapper} 的类注释。
 */
@Component
public class AuditLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditLogConsumer.class);

    /** 一次最多拉多少条。审计是低吞吐场景, 32 足够, 也不会让单批超时。 */
    private static final int BATCH_SIZE = 32;

    /** 采集方没带租户时的兜底值, 和 {@code audit_log.tenant_id} 的默认值一致。 */
    private static final String DEFAULT_TENANT = "default";

    private final AuditProperties props;
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    private volatile DefaultMQPushConsumer consumer;

    private final AtomicLong stored = new AtomicLong();
    private final AtomicLong deduped = new AtomicLong();
    private final AtomicLong malformed = new AtomicLong();

    public AuditLogConsumer(AuditProperties props, AuditLogMapper auditLogMapper,
                            ObjectMapper objectMapper) {
        this.props = props;
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    //  生命周期
    // ------------------------------------------------------------------

    @PostConstruct
    void start() {
        if (!props.isEnabled()) {
            log.warn("审计已禁用 (km.audit.enabled=false), 消费者不启动, 事件将堆积在 broker 上");
            return;
        }

        DefaultMQPushConsumer candidate = new DefaultMQPushConsumer(props.getConsumerGroup());
        candidate.setNamesrvAddr(props.getNamesrvAddr());
        candidate.setConsumeMessageBatchMaxSize(BATCH_SIZE);

        // ★ 必须显式指定从最早开始 ★
        // 新消费组的默认值是 CONSUME_FROM_LAST_OFFSET, 也就是"从我出生这一刻
        // 之后的才开始收"。对业务消息这是对的 (重放历史订单是灾难), 但审计
        // 恰恰相反 —— 它的全部意义就是"发生过的事都留着"。用默认值的话,
        // 第一次部署时 topic 里已有的历史事件会被<b>静默跳过</b>: 不报错,
        // 不告警, 只是那批数据永远查不到了。
        candidate.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);

        try {
            candidate.subscribe(props.getTopic(), "*");
            candidate.registerMessageListener(
                    (MessageListenerConcurrently) this::consume);
            candidate.start();
            this.consumer = candidate;
            log.info("审计消费者已就绪 group={} topic={} 从最早位点开始",
                    props.getConsumerGroup(), props.getTopic());
        } catch (Exception ex) {
            shutdownQuietly(candidate);
            // 注意措辞: 事件没有丢, 只是还在 broker 上躺着。
            // 但 broker 只保留 fileReservedTime (本仓库配的是 48 小时),
            // 所以这不是"永远安全", 而是"48 小时内修好就没事"。
            log.error("审计消费者启动失败。事件不会丢, 但会一直堆在 broker 上 "
                            + "(按当前配置保留 48 小时后被清理)。原因: {}", ex.toString());
        }
    }

    @PreDestroy
    void stop() {
        DefaultMQPushConsumer current = this.consumer;
        this.consumer = null;
        if (current != null) {
            shutdownQuietly(current);
            log.info("审计消费者已关闭 已入库={} 去重={} 坏消息={}",
                    stored.get(), deduped.get(), malformed.get());
        }
    }

    // ------------------------------------------------------------------
    //  消费
    // ------------------------------------------------------------------

    private ConsumeConcurrentlyStatus consume(List<MessageExt> messages,
                                              ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {

            AuditEvent event;
            try {
                event = objectMapper.readValue(message.getBody(), AuditEvent.class);
            } catch (Exception ex) {
                // 解析不了的字节, 重投多少次都还是解析不了。让它重试 16 次再进
                // 死信队列, 只是把同一个错误刷 16 遍屏, 顺便堵住后面的消息。
                malformed.incrementAndGet();
                log.error("审计消息解析失败, 跳过 msgId={} 原因={}",
                        message.getMsgId(), ex.toString());
                continue;
            }

            String invalid = validate(event);
            if (invalid != null) {
                malformed.incrementAndGet();
                log.error("审计事件不合契约, 跳过 msgId={} eventId={} 原因={}",
                        message.getMsgId(), event.eventId(), invalid);
                continue;
            }

            try {
                auditLogMapper.insert(toRow(event));
                stored.incrementAndGet();
            } catch (DuplicateKeyException ex) {
                // 不是故障, 是 at-least-once 的常态。
                // 唯一键 uk_audit_event 挡下了重复行, 这次什么也不用做。
                deduped.incrementAndGet();
                // 用 INFO 而不是 DEBUG: 重投频率是"消费是否健康"的重要信号,
                // 一直在重投说明消费位点在反复回退, 这事该被看见。
                log.info("审计事件重投, 已去重 eventId={}", event.eventId());
            } catch (Exception ex) {
                log.error("审计事件写库失败, 整批稍后重试 eventId={} 原因={}",
                        event.eventId(), ex.toString());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /**
     * 契约校验 —— <b>反序列化成功不等于消息合法</b>。
     *
     * <p>{@code AuditEvent.Builder} 上的守卫拦不住从 MQ 来的事件: 那条路径
     * 走的是 Jackson 的直接构造, 不经过 builder。所以缺字段的消息在这里
     * 才会第一次暴露。
     *
     * <p>为什么必须显式拦住而不是让它去写库: 缺 event_id 会撞 NULL 约束,
     * 缺 layer 会让 {@code toRow} 抛 NPE, 而这两种异常都会被下面的 catch
     * 当成"数据库写失败"去重试 —— 一条<b>永远写不进去</b>的消息于是永远
     * 在重试, 顺便把后面的消息全堵住。
     *
     * @return null 表示合法; 否则返回不能入库的原因
     */
    private static String validate(AuditEvent event) {
        if (event.eventId() == null || event.eventId().isBlank()) {
            return "缺少 eventId —— 没有它就无法去重, 重投会变成重复记录";
        }
        if (event.layer() == null) {
            return "缺少 layer —— 分不出是哪一层采集的";
        }
        if (event.eventType() == null) {
            return "缺少 eventType";
        }
        return null;
    }

    /**
     * 信封 -> 数据库行。
     *
     * <p>这个映射<b>不做任何判断</b>, 一一对应。一旦这里出现"如果 A 就填 B"
     * 的分支, 说明信封缺字段了 —— 该改的是信封, 不是这里。
     */
    private AuditLog toRow(AuditEvent event) {
        AuditLog row = new AuditLog();
        row.setEventId(event.eventId());
        row.setAgentTaskId(event.taskId());
        row.setTraceId(event.traceId());
        row.setTenantId(event.tenantId() == null ? DEFAULT_TENANT : event.tenantId());
        row.setLayer(event.layer().name());
        row.setEventType(event.eventType().name());
        row.setActor(event.actor());
        row.setRequestBody(writeJson(event.requestBody(), event.eventId(), "requestBody"));
        row.setResponseBody(writeJson(event.responseBody(), event.eventId(), "responseBody"));
        row.setStatus(event.status() == null ? AuditEvent.OK : event.status());
        row.setErrorMsg(event.errorMsg());
        row.setLatencyMs(event.latencyMs());
        row.setTokenIn(event.tokenIn());
        row.setTokenOut(event.tokenOut());
        row.setOccurredAt(toLocalDateTime(event.occurredAt()));
        // created_at 不设: 由数据库的 DEFAULT CURRENT_TIMESTAMP(3) 填
        return row;
    }

    /**
     * Map -> JSON 文本。
     *
     * <p>理论上不该失败: requestBody / responseBody 是从 MQ 上 JSON 反序列化
     * 来的, 本身就是 JSON 能表达的值。真失败了说明信封被人塞了非 JSON 的东西,
     * 那是采集端的 bug。
     *
     * <p>处理方式是<b>记错误但保住这一行</b>, 而不是让整个事件写不进去 ——
     * 一条审计记录的价值主要在"谁 / 什么时候 / 干了什么 / 成没成",
     * 明细部分丢了比整行丢了强。
     */
    private String writeJson(Map<String, Object> body, String eventId, String field) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            log.error("审计明细序列化失败, 该字段留空 eventId={} field={} 原因={}",
                    eventId, field, ex.toString());
            return null;
        }
    }

    /**
     * epoch millis -> 数据库的 DATETIME(3)。
     *
     * <p>用系统时区转换, 和 JDBC URL 里的 {@code serverTimezone=Asia/Shanghai}
     * 是同一个时区 (开发机就在这个时区), 所以两边对得上。
     *
     * <p>要提醒的是: {@code DATETIME} 存的是"墙上时间", 不含时区。真把服务
     * 部署到 UTC 的机器上, 这一行会开始按 UTC 解释 —— 那时要么显式写死
     * 时区, 要么把列改成 TIMESTAMP。现在不提这个, 是因为只有一台开发机,
     * 提前处理反而是凭空多一个概念。
     */
    private static LocalDateTime toLocalDateTime(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }

    // ------------------------------------------------------------------
    //  观测
    // ------------------------------------------------------------------

    public long storedCount() {
        return stored.get();
    }

    public long dedupedCount() {
        return deduped.get();
    }

    public long malformedCount() {
        return malformed.get();
    }

    private static void shutdownQuietly(DefaultMQPushConsumer c) {
        try {
            c.shutdown();
        } catch (Exception ex) {
            log.debug("关闭审计消费者时出错, 忽略: {}", ex.toString());
        }
    }
}
