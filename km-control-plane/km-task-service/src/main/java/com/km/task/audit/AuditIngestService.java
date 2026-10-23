package com.km.task.audit;

import com.km.audit.AuditEvent;
import com.km.audit.AuditPublisher;
import com.km.task.web.dto.AuditEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 接收 Python 运行时提交的审计事件, 转投 MQ。
 *
 * <h3>为什么 Python 不自己连 broker</h3>
 *
 * <p>不是没试过, 是这条路在 Ricardo 的机器上根本不成立: Windows 上
 * {@code rocketmq-client-python} 只有 sdist 没有轮子 (要 C++ 工具链),
 * {@code rocketmq-python-client} 则要求一个跑着的 rocketmq-proxy, 而
 * {@code broker.conf} 里的 {@code brokerIP1 = 127.0.0.1} 让容器里的
 * proxy 连不上 broker。
 *
 * <p>于是变成现在这样: <b>Java 持有唯一的生产者</b>, Python 只负责提交。
 * 好处比"绕开一个环境问题"要大 —— 名称服务器地址、topic、采样策略、重试
 * 次数这些东西只有一份配置, 改一次两边都生效, 不会出现"Java 翻倍采样了
 * 但 Python 还是全量"这种对不上的情况。
 *
 * <h3>失败语义</h3>
 *
 * <p>整批<b>不</b>做原子性, 也不保证送达。审计是旁路: 提交失败时返回的
 * accepted 数会比提交数小, 调用方记一条日志就够了, 不该重试到天荒地老。
 * 一条事件映射不出来也只丢那一条, 不会连坐整个批次。
 */
@Service
public class AuditIngestService {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestService.class);

    private final AuditPublisher publisher;

    public AuditIngestService(AuditPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 逐条映射并投递, 返回成功投出去的数量。
     *
     * @return 实际投递条数。小于 {@code requests.size()} 说明有事件被丢弃,
     *         原因会记在日志里
     *
     */
    // 将Python侧传递的 审计请求(AuditEventRequest) 映射 成审计事件, 并投递MQ
    public int ingest(List<AuditEventRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return 0;
        }

        int accepted = 0;
        for (AuditEventRequest request : requests) {
            if (request == null) {
                continue;
            }
            try {
                publisher.publish(toEvent(request));
                accepted++;
            } catch (RuntimeException ex) {
                // 单条坏掉不该连坐整批。最可能的两种原因: layer/eventType
                // 为 null (校验漏了), 或者 occurredAt 是个负数。
                // 这里不做校验是有意的 —— 校验放在 DTO 上, 走到这一步还炸
                // 说明是映射代码自己的 bug, 值得看一眼堆栈。
                log.warn("审计事件映射失败, 已丢弃 layer={} eventType={} taskId={}",
                        request.layer(), request.eventType(), request.taskId(), ex);
            }
        }

        if (accepted < requests.size()) {
            log.warn("审计提交部分被丢弃: {} / {} 条", requests.size() - accepted, requests.size());
        }
        return accepted;
    }

    private static AuditEvent toEvent(AuditEventRequest r) {
        AuditEvent.Builder builder = AuditEvent.builder()
                .layer(r.layer())
                .eventType(r.eventType())
                .taskId(r.taskId())
                .traceId(r.traceId())
                .tenantId(r.tenantId())
                .actor(r.actor())
                .status(r.statusOrDefault())
                .latencyMs(r.latencyMs())
                .tokenIn(r.tokenIn())
                .tokenOut(r.tokenOut())
                .requestBody(r.requestBody())
                .responseBody(r.responseBody())
                .errorMsg(r.errorMsg());

        // 只有生产者真的给了才覆盖 —— 这两个字段的空缺交给 Builder 的默认值
        // (新 ID / 现在), 不在这里再写一遍同样的逻辑。
        if (r.eventId() != null && !r.eventId().isBlank()) {
            builder.eventId(r.eventId());
        }
        if (r.occurredAt() != null) {
            builder.occurredAt(r.occurredAt());
        }
        return builder.build();
    }
}
