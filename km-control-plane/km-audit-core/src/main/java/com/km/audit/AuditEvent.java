package com.km.audit;

import java.util.Map;
import java.util.UUID;

/**
 * 审计事件信封 —— <b>跨层、跨语言的统一契约</b>。
 *
 * <h3>为什么要单独定义一个信封, 而不是各层各写各的</h3>
 *
 * 审计的采集点分散在四层 (Agent 运行时 / MCP 网关 / 任务服务 / 存量微服务),
 * 但最终要落进同一张 {@code audit_log} 表。如果每层自己拼 JSON 直接投 MQ,
 * 消费端就得认识四种格式, 每加一层就要改一次消费者 —— 采集点和存储格式
 * 被绑死了。
 *
 * 信封把两者解耦: <b>各层只需要填信封, 不需要知道表长什么样</b>。
 *
 * <h3>字段是照着 audit_log 的列设计的</h3>
 *
 * 除了 {@code layer}/{@code eventType} 之外, 每个字段都能直接对应表里的一列
 * (见 {@code AuditLog} 的类注释里的对照表)。这不是巧合: 一一对应意味着
 * 映射过程<b>不做任何判断</b>, 也就没有把信息丢在半路的机会。
 * 一旦信封的字段比表少, 消费端就必须"猜"剩下那列填什么 —— 猜错一次,
 * 以后所有历史数据都是错的。
 *
 * <h3>taskId 和 traceId 为什么两个都要</h3>
 *
 * <ul>
 *   <li>{@code taskId} —— 业务身份。回答"这是<b>哪件事</b>" (一次用户会话里
 *       可以有好几件事)</li>
 *   <li>{@code traceId} —— 执行身份。回答"这件事的<b>第几遍执行</b>是怎么跑的"</li>
 * </ul>
 *
 * 两者不是冗余关系, 而是 <b>1:N</b>: 一个任务重试几次就有几条链 (每次重试
 * 是一个新的 traceId, taskId 不变)。只留一个, 另一个维度就查不了了 ——
 * 只剩 taskId 就分不清某个工具调用发生在哪一遍执行里。
 *
 * <p><b>traceId 不跨任务。</b> 一条 trace 的边界是"一次执行" (一次
 * {@code POST /chat} 跑到终态), 不是一次会话。用户在一个会话里连问三件事
 * 是三个任务, 也就是三条独立的链; 串起它们的应该是 {@code sessionId},
 * 不是 traceId。把多个任务的 traceId 合并成一个, 会让"这条链为什么慢/失败"
 * 永远无法归因。
 *
 * <p>两者都作为 RocketMQ 的消息 key 写入, 所以按任一维度都能在控制台查到消息。
 *
 * <h3>layer 和 eventType 的分工</h3>
 *
 * {@code layer} 是<b>谁产生的</b> (在哪一层采集的), {@code eventType} 是
 * <b>发生了什么</b>。同一层会产生多种事件 (Agent 运行时既报 LLM_CALL 也报
 * MCP_TOOL_CALL), 同一类事件也会来自多层 (MCP_TOOL_CALL 既可能由网关报,
 * 也可能由运行时报)。合成一个字段就再也拆不开了。
 *
 * <h3>怎么构造</h3>
 *
 * 15 个字段的位置参数已经不可能记清了, 所以用 {@link #builder()}:
 * <pre>{@code
 * AuditEvent.builder()
 *     .taskId(taskId).traceId(traceId).tenantId(tenantId)
 *     .layer(AuditLayer.MCP_GATEWAY).eventType(AuditEventType.MCP_TOOL_CALL)
 *     .actor(userId).status(AuditEvent.OK)
 *     .latencyMs(37)
 *     .requestBody(Map.of("orderId", "12345"))
 *     .responseBody(Map.of("status", "已发货"))
 *     .build();
 * }</pre>
 *
 * eventId 和 occurredAt 由 builder 自动填, 不用管 —— 漏填的后果不是编译错误,
 * 而是"这一层的审计悄悄地全都去重成一条", 让默认行为就是对的比写文档可靠。
 *
 * @param eventId      本事件自己的全局唯一 ID。<b>消费端幂等去重的依据</b> ——
 *                     RocketMQ 保证的是"至少一次", 重投是常态而不是故障,
 *                     没有这个键, 审计表里同一次调用会被记好几遍
 * @param taskId       业务任务 ID, 可能为 null —— 例如建任务之前就失败了的调用
 * @param traceId      一次执行的链路 ID; 同一任务重试会换一个新值, 不要拿它当任务 ID 用
 * @param tenantId     租户。别的事件可能拿不到, 允许为 null
 * @param layer        采集层, 见 {@link AuditLayer}
 * @param eventType    事件类型, 见 {@link AuditEventType}
 * @param actor        谁触发的: 用户 ID 或 Agent ID
 * @param status       结果: OK / ERROR / TIMEOUT / DEGRADED
 * @param latencyMs    耗时, 毫秒。不适用时为 null
 * @param tokenIn      输入 token 数, 仅 LLM_CALL 有值
 * @param tokenOut     输出 token 数, 仅 LLM_CALL 有值
 * @param requestBody  入参明细 (MCP 工具参数 / LLM prompt / 任务创建参数)
 * @param responseBody 出参明细 (工具返回 / LLM 回复 / 任务快照)
 * @param errorMsg     这次调用捕获到的异常, 没有异常时为 null。
 *                     <b>注意它不是 "status != OK" 的同义词</b> —— 两者回答的是
 *                     不同的问题: status 说"算不算系统故障", errorMsg 说"抛了什么"。
 *                     典型反例是后端返回 404: 那是一次成功的查询 (status=OK,
 *                     见 {@code CallStatus}), 但确实抛了
 *                     {@code HttpClientErrorException$NotFound}, 这条线索必须留下,
 *                     否则没法区分"订单不存在"和"后端连不上"
 * @param occurredAt   事件发生时刻 (epoch millis)。<b>跨语言用 epoch millis 而不是
 *                     格式化字符串</b>: 字符串要约定时区和格式, 约定错了就是
 *                     几小时的系统性偏差, 而时间戳没有解释空间。
 *                     落库时才转成 DATETIME(3), 那是消费端的事
 */
public record AuditEvent(
        String eventId,
        String taskId,
        String traceId,
        String tenantId,
        AuditLayer layer,
        AuditEventType eventType,
        String actor,
        String status,
        Integer latencyMs,
        Integer tokenIn,
        Integer tokenOut,
        Map<String, Object> requestBody,
        Map<String, Object> responseBody,
        String errorMsg,
        long occurredAt
) {

    /** 结果取值。与 {@code audit_log.status} 列、以及 {@code tool_invocation.status} 对齐。 */
    public static final String OK = "OK";
    public static final String ERROR = "ERROR";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String DEGRADED = "DEGRADED";

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 信封的构造器。
     *
     * <p>用 builder 而不是长位置参数列表, 是因为 15 个字段的
     * {@code new AuditEvent(a, b, c, ...)} 里放错一个位置编译器不会报错 ——
     * 两个字段类型都是 String 就足以让 taskId 和 traceId 悄悄对调,
     * 而症状是"审计查不到东西", 排查起来离现场非常远。
     */
    public static final class Builder {

        private String eventId;
        private String taskId;
        private String traceId;
        private String tenantId;
        private AuditLayer layer;
        private AuditEventType eventType;
        private String actor;
        private String status = OK;
        private Integer latencyMs;
        private Integer tokenIn;
        private Integer tokenOut;
        private Map<String, Object> requestBody;
        private Map<String, Object> responseBody;
        private String errorMsg;
        private long occurredAt;

        /**
         * 指定事件 ID。<b>一般不用调</b> —— 默认每次 build 生成一个新的。
         *
         * <p>什么时候需要: 采集方自己就是"可能重发"的那一端。ID 是
         * {@code audit_log} 上唯一键 {@code uk_audit_event} 的依据, 而
         * 默认行为是"每次 build 都是一个新事件" —— 对于重发的场景,
         * 那正是你最不想要的结果: <b>同一次观察在库里变成两行, 而且没有任何
         * 东西会告诉你</b>。
         *
         * <p>典型场景是跨进程提交 (Python 提交 -> Java 转投 MQ): 生产者
         * 在"观察到这件事"的时刻就把 ID 定下来, 后面无论重试几次、经过
         * 几层投递, 唯一键都拦得住。反过来如果让每一层各自生成, 那一层的
         * 重投去重就只在那一层内部有效。
         */
        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder layer(AuditLayer layer) {
            this.layer = layer;
            return this;
        }

        public Builder eventType(AuditEventType eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder actor(String actor) {
            this.actor = actor;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder latencyMs(Integer latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder tokenIn(Integer tokenIn) {
            this.tokenIn = tokenIn;
            return this;
        }

        public Builder tokenOut(Integer tokenOut) {
            this.tokenOut = tokenOut;
            return this;
        }

        public Builder requestBody(Map<String, Object> requestBody) {
            this.requestBody = requestBody;
            return this;
        }

        public Builder responseBody(Map<String, Object> responseBody) {
            this.responseBody = responseBody;
            return this;
        }

        public Builder errorMsg(String errorMsg) {
            this.errorMsg = errorMsg;
            return this;
        }

        /**
         * 覆盖发生时刻。<b>一般不用调</b> —— 默认取 build() 那一刻。
         *
         * <p>什么时候需要它: 采集方是"事后补记"(比如一次调用超时 30 秒后才
         * 判定失败并上报), 这时 build 时刻比真实发生时刻晚了 30 秒,
         * 而审计要的是后者。
         */
        public Builder occurredAt(long occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public AuditEvent build() {
            if (layer == null || eventType == null) {
                // layer / eventType 缺失的话, 这条事件落进 audit_log 后
                // 既分不出采集层也分不出类型, 等于一条废数据。宁可在这里炸 ——
                // 采集点的测试会立刻发现, 而不是上线后从库里翻出看不懂的行。
                throw new IllegalStateException(
                        "审计事件必须指定 layer 和 eventType (缺少的那个是 null)");
            }
            return new AuditEvent(
                    eventId == null ? newEventId() : eventId,
                    taskId, traceId, tenantId, layer, eventType, actor, status,
                    latencyMs, tokenIn, tokenOut, requestBody, responseBody, errorMsg,
                    occurredAt == 0L ? System.currentTimeMillis() : occurredAt);
        }
    }

    /** 事件 ID 的生成规则。和 taskId / traceId 同形, 便于日志里一眼认出。 */
    private static String newEventId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
