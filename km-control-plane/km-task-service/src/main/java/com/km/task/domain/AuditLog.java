package com.km.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 统一审计日志。对应表 {@code audit_log}。
 *
 * <h3>这张表是四层审计的汇聚点</h3>
 *
 * Agent 运行时 / MCP 网关 / 任务服务 / 存量微服务产生的事件, 最终都落在这里。
 * 写入方只有 {@code AuditLogConsumer} 一个 —— 各层<b>不直接写库</b>。
 * 这样加一层新采集点不需要动数据库权限, 也不需要 N 个连接池。
 *
 * <h3>字段与 {@link com.km.audit.AuditEvent} 的对照</h3>
 *
 * <pre>
 *   AuditEvent.eventId      -> event_id       (唯一键, 幂等去重靠它)
 *   AuditEvent.taskId       -> agent_task_id
 *   AuditEvent.traceId      -> trace_id
 *   AuditEvent.tenantId     -> tenant_id
 *   AuditEvent.layer        -> layer
 *   AuditEvent.eventType    -> event_type
 *   AuditEvent.actor        -> actor
 *   AuditEvent.requestBody  -> request_body   (Map 序列化成 JSON 文本)
 *   AuditEvent.responseBody -> response_body
 *   AuditEvent.status       -> status
 *   AuditEvent.errorMsg     -> error_msg
 *   AuditEvent.latencyMs    -> latency_ms
 *   AuditEvent.tokenIn      -> token_in
 *   AuditEvent.tokenOut     -> token_out
 *   AuditEvent.occurredAt   -> occurred_at    (epoch millis -> DATETIME(3))
 * </pre>
 *
 * 一一对应, 映射过程不做任何判断 —— 这是刻意的, 见 {@code AuditEvent} 的类注释。
 *
 * <h3>created_at 为什么这里没有 setter 调用</h3>
 *
 * 和 {@link AgentTask} 同理: 由数据库的 {@code DEFAULT CURRENT_TIMESTAMP(3)} 填。
 * 消费端只管写, 从不读回, 所以拿不到这个值也无所谓。
 *
 * <h3>occurred_at 和 created_at 不是一回事</h3>
 *
 * {@code occurred_at} 是事件<b>发生</b>的时刻 (采集方填), {@code created_at}
 * 是这条记录<b>落库</b>的时刻。MQ 积压时两者可能差几十分钟。
 * 查"任务几点开始跑的"要的是前者, 查"这条审计什么时候进来的"要的是后者。
 */
@TableName("audit_log")
public class AuditLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 事件全局唯一 ID, 唯一索引 —— 消费端幂等的全部依据 */
    private String eventId;

    private String agentTaskId;

    private String traceId;

    private String tenantId;

    /** 采集层, 见 {@code com.km.audit.AuditLayer} */
    private String layer;

    /** 事件类型, 见 {@code com.km.audit.AuditEventType} */
    private String eventType;

    /** 谁触发的: 用户 ID 或 Agent ID */
    private String actor;

    /** 入参, JSON 文本 (已脱敏) */
    private String requestBody;

    /** 出参, JSON 文本 (已脱敏) */
    private String responseBody;

    /**
     * 结果: OK / ERROR / TIMEOUT / DEGRADED。
     *
     * <p>回答的是<b>"这次调用算不算失败"</b>, 不是一个纯粹的 HTTP 事实 ——
     * 判定规则集中在网关的 {@code CallStatus} 里。最反直觉的一条是
     * <b>4xx 记 OK</b>: 后端明确回一句"没有这个订单"时, 系统其实工作正常,
     * 把它算成 ERROR 只会让错误率被用户输错参数的行为污染。
     * 理由完整写在那边, 这里只强调结论。
     */
    private String status;

    /**
     * 这次调用捕获到的异常, 没有异常时为 NULL。
     *
     * <p><b>★ 不要把它当成 "status != OK" 的同义词 ★</b>
     *
     * <p>早先这里写的是"失败原因, status != OK 时才有值", 加上 4xx 规则之后
     * 那个说法就不成立了, 会出现 {@code status='OK' AND error_msg IS NOT NULL}
     * 的行。这不是数据错位, 而是两个字段各回答一个问题:
     *
     * <ul>
     *   <li>{@code status} —— 这次调用<b>算不算系统故障</b> (4xx 不算)</li>
     *   <li>{@code error_msg} —— 这次调用<b>到底抛了什么</b> (抛了就记)</li>
     * </ul>
     *
     * <p>"查 404 不算故障"和"查 404 确实发生了异常"两件事都是真的, 都要留。
     * 硬把 error_msg 清空的代价是丢掉 {@code HttpClientErrorException$NotFound}
     * 这条线索 —— 而它恰恰是区分"订单不存在"和"后端挂了"的唯一依据。
     */
    private String errorMsg;

    private Integer latencyMs;

    /** 仅 LLM_CALL 有值 */
    private Integer tokenIn;

    /** 仅 LLM_CALL 有值 */
    private Integer tokenOut;

    /** 事件发生时刻 */
    private LocalDateTime occurredAt;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAgentTaskId() {
        return agentTaskId;
    }

    public void setAgentTaskId(String agentTaskId) {
        this.agentTaskId = agentTaskId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getLayer() {
        return layer;
    }

    public void setLayer(String layer) {
        this.layer = layer;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getTokenIn() {
        return tokenIn;
    }

    public void setTokenIn(Integer tokenIn) {
        this.tokenIn = tokenIn;
    }

    public Integer getTokenOut() {
        return tokenOut;
    }

    public void setTokenOut(Integer tokenOut) {
        this.tokenOut = tokenOut;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
