package com.km.audit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计事件的 RocketMQ 投递器 —— 唯一出口。
 *
 * <h3>这个类最重要的性质: 它永远不会抛异常</h3>
 *
 * 审计是<b>旁路</b>。MQ 挂了、broker 抽风、序列化炸了 —— 业务该怎么走还怎么走。
 * 如果 {@link #publish} 能把异常抛回调用方, 那就等于"审计系统一抖, 业务就挂",
 * 这是完全颠倒的优先级: 我们宁愿丢一条审计, 也不愿让用户的任务失败。
 *
 * <p>同样的约束在 Python 侧已经存在 —— {@code task_state.py} 里
 * "上报失败绝不能影响主流程"。两边是同一条原则, 只是语言不同。
 *
 * <h3>降级: 不可用就不发, 而不是挡在启动上</h3>
 *
 * {@code producer.start()} 时会去连 namesrv。如果 RocketMQ 没起, 这里
 * <b>不会</b>让 Spring 启动失败 —— 只记一条 error 然后把生产者置空,
 * 之后所有 publish 静默丢弃。本地只想调接口、不想开全套中间件时, 这个
 * 行为决定了你还能不能干活。
 *
 * <h3>为什么没有 {@code @Component}</h3>
 *
 * 因为本类在 {@code com.km.audit}, 而各服务的 {@code @SpringBootApplication}
 * 在 {@code com.km.task} / {@code com.km.mcp} —— <b>包扫描扫不到这里</b>。
 * 标 {@code @Component} 不会报错, 只是不生效, 然后注入点报
 * "No qualifying bean of type 'AuditPublisher'" —— 一个不会指向真正原因的错。
 *
 * <p>所以装配交给 {@link AuditAutoConfiguration}, 它靠
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 被 Boot 发现, 不受包扫描范围限制。
 */
public class AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(AuditPublisher.class);

    /** 掉一条记一条会把日志刷爆, 所以每 N 条汇总记一次。 */
    private static final long DROP_LOG_EVERY = 1000;

    private final AuditProperties props;
    private final ObjectMapper objectMapper;

    /**
     * null = 不可用 (未启用 / 启动失败)。启动时由 {@link #start()} 写,
     * 之后所有业务线程读, 所以是 volatile。
     *
     * <p>包级可见, 便于测试注入假生产者 (见 {@link #bindProducerForTest})。
     */
    volatile DefaultMQProducer producer;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    /**
     * 异步发送回调。用异步是因为同步发送会阻塞业务线程最长
     * sendTimeoutMs —— 一条审计不该让接口慢 3 秒。
     */
    private final SendCallback callback = new SendCallback() {
        // 发送成功, 就记录为功次数
        @Override
        public void onSuccess(SendResult result) {
            sent.incrementAndGet();
        }

        @Override
        public void onException(Throwable ex) {
            drop("broker 拒收: " + ex.getMessage());
        }
    };

    public AuditPublisher(AuditProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    //  生命周期
    // ------------------------------------------------------------------

    @PostConstruct
    void start() {
        if (!props.isEnabled()) {
            log.warn("审计已禁用 (km.audit.enabled=false), 事件将被全部丢弃");
            return;
        }

        // 根据Properties配置 创建一个生产者, 指定生产者组, 发送端口, 发送超时, 重试次数, 异步重试次数
        DefaultMQProducer candidate = new DefaultMQProducer(props.getProducerGroup());
        candidate.setNamesrvAddr(props.getNamesrvAddr());
        candidate.setSendMsgTimeout(props.getSendTimeoutMs());
        candidate.setRetryTimesWhenSendFailed(props.getRetryTimes());
        candidate.setRetryTimesWhenSendAsyncFailed(props.getRetryTimes());

        try {
            candidate.start();
            this.producer = candidate;
            log.info("审计生产者已就绪 namesrv={} topic={} group={}",
                    props.getNamesrvAddr(), props.getTopic(), props.getProducerGroup());
        } catch (Exception ex) {
            // 起不来的话 client 内部可能已经起了半边线程池, 顺手清掉。
            shutdownQuietly(candidate);
            log.error("审计生产者启动失败 (namesrv={}), 本次运行期间不再上报审计, "
                            + "业务不受影响。原因: {}",
                    props.getNamesrvAddr(), ex.toString());
        }
    }

    @PreDestroy
    void stop() {
        // 关闭当前生产者
        DefaultMQProducer current = this.producer;
        this.producer = null;
        if (current != null) {
            shutdownQuietly(current);
            log.info("审计生产者已关闭 已发={} 已丢={}", sent.get(), dropped.get());
        }
    }

    // ------------------------------------------------------------------
    //  投递
    // ------------------------------------------------------------------

    /**
     * 投递一条审计事件。<b>不会抛异常, 没有返回值, 也不保证送达。</b>
     *
     * <p>唯一可能"看不见效果"的地方是采样 —— 被采样掉的事件不会上报,
     * 这是设计如此, 不是 bug。
     */
    public void publish(AuditEvent event) {
        if (event == null) {
            return;
        }

        // 在事务里的话, 推到提交之后再发。
        //
        // 不这么做的话: TaskService.create 是 @Transactional, 插完库就直接发 MQ,
        // 而事务后面完全可能回滚 (比如 appendEvent 失败)。那时的结果是 ——
        // 库里没有这个任务, 但审计里有一条"任务已创建"。查审计的人会以为
        // 任务存在, 而查库的人找不到, 两边对不上。
        //
        // 注意: occurredAt 仍然是【事件发生】的时刻, 不是提交的时刻。
        // 落库的时间差本来就是审计要记录的信息之一, 不该被这里抹平。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendNow(event);
                }
            });
            return;
        }

        sendNow(event);
    }

    // 立即发送一条审计事件
    private void sendNow(AuditEvent event) {
        DefaultMQProducer current = this.producer;
        if (current == null) {
            drop("生产者不可用");
            return;
        }

        if (!shouldSample(event)) {
            return;
        }

        byte[] body;
        try {
            // 将传入的event对象序列化为Byte数组
            body = objectMapper.writeValueAsBytes(event);
        } catch (Exception ex) {
            // 一般来说只可能是 payload 里塞了序列化不了的东西 (比如某个
            // 第三方的对象)。这是采集方的 bug, 但代价只是丢这一条 ——
            // 所以记 warning 就够了, 不需要惊动谁。
            drop("序列化失败: " + ex.getMessage());
            return;
        }

        // tags 用层名, 消费端就能按层订阅 (KM_AUDIT 消费者可用
        // "AGENT_RUNTIME || MCP_TOOL_CALL" 之类做过滤, 不用全量拉下来再筛)。
        Message message = new Message(props.getTopic(), event.layer().name(), keyOf(event), body);

        try {
            // 异步发送消息, 并注册回调
            // 成功: onSuccess
            // 失败: onException
            current.send(message, callback);
        } catch (Exception ex) {
            // 异步 send 自己抛异常 = 连"排进发送队列"都没成功 (生产者已关闭、
            // 客户端状态不对等)。真的网络失败是走 callback.onException 的。
            drop("投递失败: " + ex.getMessage());
        }
    }

    /**
     * 分层采样。
     *
     * <p><b>失败的事件永远上报, 采样只作用于成功事件。</b>
     * 采样是为了在高量下省成本, 不是为了把问题藏起来 —— 一条 ERROR 恰恰是
     * 最该留下的那条。如果采样把失败也随机丢掉, 你会得到"线上偶发失败、
     * 日志里查不到"这种最难查的局面。
     */
    private boolean shouldSample(AuditEvent event) {
        if (!AuditEvent.OK.equals(event.status())) {
            return true;
        }
        double rate = props.sampleRateFor(event.layer());
        if (rate >= 1.0) {
            return true;
        }
        if (rate <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    /**
     * 消息 key: taskId 和 traceId 都放进去。
     *
     * <p>RocketMQ 控制台的"按 key 查消息"就是拿这个字段匹配的。两个都放,
     * 才能既不丢任务维度也不丢链路维度 —— 这是"根据 task_id 和 trace_id
     * 作唯一标识"在传输层的落地。
     *
     * <p>用空格分隔是 RocketMQ 的约定 (它内部按空格拆成 key 集合)。
     */
    private static String keyOf(AuditEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.taskId() != null && !event.taskId().isBlank()) {
            sb.append(event.taskId());
        }
        if (event.traceId() != null && !event.traceId().isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(event.traceId());
        }
        // 两个都没有的话 key 会是空串, RocketMQ 允许, 只是查不到 —— 不能返回 null。
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    // ------------------------------------------------------------------
    //  观测
    // ------------------------------------------------------------------

    /** 已成功投递条数。生产上是给将来的 Prometheus 用的, 测试里用来断言。 */
    public long sentCount() {
        return sent.get();
    }

    /** 被丢弃条数 (含采样丢弃之外的失败丢弃)。持续增长说明 MQ 那条路有问题。 */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * 塞入一个生产者, <b>仅供测试</b> —— 生产代码从不调用。
     *
     * <p>之所以留这么一个显式方法而不是用反射往 private 字段里写:
     * 反射注入会让"测试到底怎么替掉 broker 的"这件事从源码里彻底消失,
     * 后来人读到测试时只能靠猜。一行显式的方法比任何魔术都便宜。
     *
     * <p><b>为什么是 public 而不是包级可见</b>: 采集点不止一个模块 ——
     * km-mcp-server 的测试也要用同样的方式替掉 broker (它那边的
     * {@code McpToolAudit} 和 {@code OrderServiceClient} 也要验"发出去的
     * 事件长什么样")。包级可见的话, 那边就只能靠反射或者自己造一个假的
     * AuditPublisher, 两条路都比直接调用这个方法来路不明。
     */
    public void bindProducerForTest(DefaultMQProducer testProducer) {
        this.producer = testProducer;
    }

    // ------------------------------------------------------------------
    //  内部
    // ------------------------------------------------------------------
    // 审计消息发送失败, 记录失败次数
    private void drop(String reason) {
        long n = dropped.incrementAndGet();
        if (n == 1 || n % DROP_LOG_EVERY == 0) {
            log.warn("审计事件被丢弃 (累计 {}): {}", n, reason);
        }
    }
    // 关闭生产者, 忽略异常
    private static void shutdownQuietly(DefaultMQProducer p) {
        try {
            p.shutdown();
        } catch (Exception ex) {
            log.debug("关闭审计生产者时出错, 忽略: {}", ex.toString());
        }
    }
}
