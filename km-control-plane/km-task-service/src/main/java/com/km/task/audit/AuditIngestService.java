package com.km.task.audit;

import com.km.audit.AuditEvent;
import com.km.audit.AuditPublisher;
import com.km.task.web.dto.AuditEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 接收 Python 运行时提交的审计事件, <b>先核对身份</b>, 再转投 MQ。
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
 * <h3>★ 入口为什么要核对身份 ★</h3>
 *
 * <p>审计记录里的 {@code taskId} 和 {@code traceId} 是调用方<b>自报</b>的。
 * 自报意味着两件事: 拼错了没人拦 (那一列变成 null / 挂到别人名下), 以及
 * 伪造了也没人拦。token 解决"谁能写", 这里解决"写进来的话是否自洽"。
 *
 * <p>核对用的是 {@code agent_task_attempt} —— 它已经是任务执行身份的权威表,
 * 不需要为审计再建一张。规则只有四条:
 *
 * <ol>
 *   <li><b>没有 traceId</b>: 不查表。没有可核对的键, 而且匿名事件是合法的
 *       (建任务之前就失败的调用)。它不计入 {@code unverified} —— 那个指标
 *       的含义是"带了 trace 但没核对上", 把"无从核对"混进去会让指标失去意义</li>
 *   <li><b>查到了, 但事件上的 taskId 和这次执行的任务不一致</b>: <b>一律拒收</b>。
 *       这不是"不知道", 是两条身份互相矛盾。放进去会同时污染两个任务的链路,
 *       而且事后无法判断哪一边是真的</li>
 *   <li><b>查到了, 事件没有 taskId</b>: 用执行记录上的值补上。这不是"编造",
 *       是从权威来源取 —— traceId 已经唯一确定了它属于谁</li>
 *   <li><b>查不到</b>: 按 {@link AuditIngestProperties.Mode} 处理。
 *       WARN (默认) 收下并计入 {@code unverified}; STRICT 拒收</li>
 * </ol>
 *
 * <h3>★ 降级: 查询本身失败时照收 ★</h3>
 *
 * <p>数据库连不上和"trace 不存在"是两件事 (见 {@link AttemptLookup})。
 * 前者发生时, 无论 WARN 还是 STRICT, 本条都<b>放行</b>并计入 {@code unverified}。
 *
 * <p>理由不是"审计懒", 而是<b>依赖方向</b>: Java 本来就依赖 MySQL, 但审计
 * 摄入是旁路, 不该额外引入一次"必须查库成功才能记录"的同步依赖。否则数据库
 * 一抖, STRICT 模式下的审计入口会先把整批真实事件拒掉 —— 故障期间恰是最
 * 需要审计的时候, 却记不下来。
 *
 * <p><b>光"放行"还不够, 必须限时。</b> 核对查询由 {@link GuardedAttemptLookup}
 * 带着 500ms 硬截止时间执行: 数据库停机时直接查库会卡满 Hikari 的 30 秒
 * (实测 30.02s), 而 Python 的上报超时只有 2 秒 —— 客户端早就把整批丢了,
 * 服务端再"收下"没有意义。超时/熔断时抛
 * {@link AttemptLookupUnavailableException}, 走的就是这里的降级。
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
    private final AttemptLookup attemptLookup;
    private final AuditIngestProperties properties;

    public AuditIngestService(AuditPublisher publisher,
                              AttemptLookup attemptLookup,
                              AuditIngestProperties properties) {
        this.publisher = publisher;
        this.attemptLookup = attemptLookup;
        this.properties = properties;
    }

    /**
     * 逐条核对身份、映射并投递。
     *
     * <p>返回的不只是 accepted: 调用方 (HTTP 层和 Python) 需要看得出
     * "有几条没进来"和"为什么"。只回一个总数的话, 拒收和投递失败在
     * 日志里长得一样。
     */
    // 将Python侧传递的 审计请求(AuditEventRequest) 映射 成审计事件, 并投递MQ
    public IngestResult ingest(List<AuditEventRequest> requests) {
        // 一整批都是空事件, 直接返回
        if (requests == null || requests.isEmpty()) {
            return IngestResult.EMPTY;
        }

        int accepted = 0;
        int rejected = 0;
        int unverified = 0;
        int late = 0;

        for (AuditEventRequest request : requests) {
            if (request == null) {
                //单条审计事件(类型为TASK_LIFECYCLE,处理python侧的任务状态变更请求)为空
                rejected++;
                continue;
            }

            Resolution resolution = resolve(request);
            // 断言 = 拒绝 f f t
            if (resolution.rejected()) {
                rejected++;
                // 被拒绝的审计请求, 不投递MQ, 直接跳过
                continue;
            }

            try {
                // 能走到这里, 表示审计请求是通过的, 投递MQ
                publisher.publish(toEvent(request, resolution.taskId()));
                // 投递MQ成功, 才计入 accepted
                accepted++;
                // 这两个计数只统计"真的投出去了"的事件, 所以
                // accepted + rejected == received 恒成立
                // unverified / late 是 accepted 的子集。指标之间可以互相验算,
                // 比各自独立计数可靠。
                if (!resolution.verified()) {
                    unverified++;
                }
                if (resolution.late()) {
                    late++;
                }
            } catch (RuntimeException ex) {
                // 单条坏掉不该连坐整批。最可能的两种原因:
                // layer/eventType为 null (校验漏了), 或者 occurredAt 是个负数。
                // 这里不做校验是有意的 ——
                // 校验放在 DTO 上, 走到这一步还炸说明是映射代码自己的 bug, 值得看一眼堆栈。
                rejected++;
                log.warn("审计事件映射失败, 已丢弃 layer={} eventType={} taskId={}",
                        request.layer(), request.eventType(), request.taskId(), ex);
            }
        }

        if (rejected > 0) {
            log.warn("审计提交有 {} 条被拒/丢弃 (收到 {} 条)", rejected, requests.size());
        }
        if (unverified > 0) {
            // 批级汇总: 逐条日志已经说了原因, 这里给一个"这一批整体有疑点"的信号。
            // 否则故障期间这批日志会被 50 条 WARN 淹没, 反而看不出比例。
            log.warn("审计提交有 {} 条身份未核实 (收到 {} 条) —— "
                    + "检查核对链路或 traceId 的来源", unverified, requests.size());
        }
        return new IngestResult(accepted, requests.size(), rejected, unverified, late);
    }

    /**
     * 核对一条事件的身份, 决定"投 / 不投", 以及投的话用哪个 taskId。
     *
     * <p>所有规则集中在这一个方法里, 而不是散在循环里 —— 这套规则将来一定
     * 会被改动 (比如重试落地后,"已闭链的执行"要不要拒收), 集中一处才改得动。
     */
    // 核对审计事件请求的身份, 对其作 断言下结论, 根据返回的断言Resolution, 决定是否投递MQ, 以及投的话用哪个 taskId
    private Resolution resolve(AuditEventRequest request) {
        // 对 traceId 和 taskId 做空值归一化处理, 要么是 null, 要么是非空字符串, 不存在空字符串 "" 情况
        // 方便后续判断
        String traceId = trimToNull(request.traceId());
        String claimedTaskId = trimToNull(request.taskId());

        // 1. 没有 trace: 无从核对。匿名事件是合法的一类, 原样放行。
        if (traceId == null) {
            // Resolution(String taskId, boolean verified, boolean late, boolean rejected)
            // return new Resolution(taskId, true, false, false);
            return Resolution.verified(claimedTaskId);
        }

        // 2. 有 trace: 核对身份。
        // 去校验表里查询traceId对应的 attempt 记录. 能查到就说明有身份, 查不到就说明是黑户
        AttemptRef attempt;
        try {
            attempt = attemptLookup.findByTrace(traceId);
        } catch (AttemptLookupUnavailableException ex) {
            // 2a. 核对链路不可用 (超时 / 熔断 / 线程池满): 降级放行。
            //     不打堆栈 —— 数据库一挂, 一批 50 条会变成 50 份一样的栈。
            //     异常的 message 已经说清了是"超时"还是"熔断中"。
            log.warn("审计身份核对不可用, 按未核实放行 task={} trace={}: {}",
                    claimedTaskId, traceId, ex.getMessage());

            // 未审核, 未超时, 未拒绝
            // new Resolution(taskId, false, false, false)
            return Resolution.unverified(claimedTaskId);
        } catch (RuntimeException ex) {
            // 2b. 其它查询失败: 同样降级, 但带栈 ex 对象
            //     运行时异常说明不是预期内的失败路径, 值得看一眼。
            log.warn("审计身份核对查询失败, 按未核实放行 task={} trace={}", claimedTaskId, traceId, ex);

            // new Resolution(taskId, false, false, false)
            return Resolution.unverified(claimedTaskId);
        }

        if (attempt == null) {
            // 3. 明确查无。两种模式在这里分叉。
            if (properties.getIdentityCheck() == AuditIngestProperties.Mode.STRICT) {
                log.warn("审计身份核对: trace 没有任何执行记录, STRICT 模式拒收 task={} trace={}",
                        claimedTaskId, traceId);
                return Resolution.reject();
            }
            log.warn("审计身份核对: trace 没有任何执行记录, 按未核实收下 task={} trace={}",
                    claimedTaskId, traceId);
            return Resolution.unverified(claimedTaskId);
        }

        // 4. 查到记录存在, 但是校验表里的taskId和请求传递的taskId不一致, 说明身份矛盾
        // 拒收
        if (claimedTaskId != null && !claimedTaskId.equals(attempt.agentTaskId())) {
            log.warn("审计身份矛盾: 事件声明 task={}, 但 trace={} 属于 task={}, 拒收",
                    claimedTaskId, traceId, attempt.agentTaskId());
            return Resolution.reject();
        }

        // 5. 身份一致。taskId 缺失时用执行记录补齐 —— 不是编造, 是取权威值。
        // 保证taskId不为空, 前面也只是对traceId做了判断,
        String taskId = claimedTaskId != null ? claimedTaskId : attempt.agentTaskId();

        // 6. 迟到: 事件的发生时刻晚于这次执行的闭链时刻。
        //    含义通常是"执行已经结束了, 还有事件在排队/重发"。它仍然值得记
        //    (迟到不等于假), 所以只标记、不拒收, 也不改事件内容 ——
        //    audit_log 没有"late"列, 改了反而让记录不再忠于原始观察。

        // 对比 请求发起时间 和 traceId对应调用链的闭链时刻, 如果请求发起时间晚于闭链时刻, 则认为是迟到
        boolean late = isLate(request.occurredAt(), attempt.endedAt());
        if (late) {
            log.info("审计事件迟到: 发生时刻晚于执行闭链 task={} trace={} endedAt={}",
                    taskId, traceId, attempt.endedAt());
        }
        // 核实通过, 但是迟到, 不拒收
        return new Resolution(taskId, true, late, false);
    }

    /**
     * @param occurredAt 事件自报的发生时刻 (epoch millis), 可能为 null
     * @param endedAt    执行闭链时刻 ({@code LocalDateTime}), 未闭链时为 null
     *
     * <p>时间类型不同是因为两边的来源不同: 审计用 epoch millis (跨语言),
     * 数据库用 DATETIME。这里按<b>系统默认时区</b>换算 —— JDBC URL 里
     * {@code serverTimezone=Asia/Shanghai} 说明库里的 DATETIME 就是服务端
     * 本地时间, 用同一个时区才能对上。
     */
    private static boolean isLate(Long occurredAt, LocalDateTime endedAt) {
        if (occurredAt == null || endedAt == null) {
            return false;
        }
        long endedAtMillis = endedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return occurredAt > endedAtMillis;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static AuditEvent toEvent(AuditEventRequest r, String taskId) {
        AuditEvent.Builder builder = AuditEvent.builder()
                .layer(r.layer())
                .eventType(r.eventType())
                .taskId(taskId)
                .traceId(trimToNull(r.traceId()))
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

    /** 身份核对的结论: 用哪个 taskId、是否核实过、是否迟到、是否拒收。 */
    private record Resolution(String taskId, boolean verified, boolean late, boolean rejected) {

        static Resolution verified(String taskId) {
            return new Resolution(taskId, true, false, false);
        }

        static Resolution unverified(String taskId) {
            return new Resolution(taskId, false, false, false);
        }

        static Resolution reject() {
            return new Resolution(null, false, false, true);
        }
    }

    /**
     * 一批提交的处理结果。
     *
     * <p>计数之间可以互相验算:
     * {@code accepted + rejected == received}, 且
     * {@code unverified <= accepted}, {@code late <= accepted}。
     * 这是刻意的 —— 派生指标如果各自独立累加, 迟早会出现"总数对不上"的
     * 情况, 而那时没人知道该信哪一个。
     *
     * @param accepted   成功投进 MQ 的条数
     * @param received   收到的条数 (含被拒的)
     * @param rejected   没投出去的条数: 身份矛盾 / 未知 trace (STRICT) / 映射失败
     * @param unverified 投出去了, 但身份没能核实 (数据库查询失败或 trace 未知且
     *                   处于 WARN 模式)。<b>持续大于 0 值得排查</b>
     * @param late       投出去了, 但发生时刻晚于这次执行的闭链时刻
     */
    public record IngestResult(int accepted, int received, int rejected, int unverified, int late) {

        public static final IngestResult EMPTY = new IngestResult(0, 0, 0, 0, 0);
    }
}
