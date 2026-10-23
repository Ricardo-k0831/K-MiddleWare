package com.km.audit;

/**
 * 把"这是哪个任务在调用"从 HTTP 头传进网关的约定 —— <b>名字只在这里定义一次</b>。
 *
 * <h3>为什么需要一套头</h3>
 *
 * MCP 协议本身没有"任务"这个概念。Python Agent 连上网关后, 网关看到的只是
 * 一个匿名的 MCP 会话 —— 它不知道这次 {@code tools/call} 属于哪个任务。
 * 而审计要回答的第一个问题恰恰是"哪个任务调用了这个工具"。
 *
 * <p>所以身份只能走传输层: 客户端在每次工具调用的 HTTP 请求上带上这几个头,
 * 网关的 {@code McpTransportContextExtractor} 把它们抽进
 * {@code McpTransportContext}, 工具方法再把它们填进 {@link AuditEvent}。
 *
 * <h3>★ 必须每个请求都带, 不能只在握手时带一次 ★</h3>
 *
 * 这是最容易设计错的地方。MCP 的 streamable 传输是<b>有会话</b>的:
 * 一次 initialize 之后, 后面几十上百次 {@code tools/call} 都复用同一个会话。
 * 如果身份在 initialize 时定死, 那一个会话就只能服务一个任务 ——
 * 而 Agent 的真实用法是一个会话里连续处理多个任务。
 *
 * <p>协议实现 (Spring AI 2.0.1 的 {@code WebMvcStreamableServerTransportProvider})
 * 的做法正是<b>逐请求</b>抽取: 每个 POST 都调一次 contextExtractor, 结果只作用于
 * 那一个请求的处理链。方向是对的, 客户端必须配合。
 *
 * <p>头名加 {@code X-KM-} 前缀而不是裸的 {@code X-Task-Id}: 中间可能还要过
 * 网关 / 代理 / 链路追踪组件, 带前缀能避免和企业里已有的同名头撞上。
 *
 * <h3>漏带头会怎样</h3>
 *
 * 不会失败 —— 审计照记, 只是 {@code taskId} / {@code traceId} 为空,
 * 那条记录就再也归不到任何任务下。网关遇到这种情况会打 WARN:
 * <b>这是客户端的 bug, 不该被静默吞掉</b>, 否则"审计查不到东西"会被误当成
 * 存储问题去排查。
 */
public final class AuditHeaders {

    /** 业务任务 ID。对应 {@code audit_log.agent_task_id}。 */
    public static final String TASK_ID = "X-KM-Task-Id";

    /** 分布式追踪 ID。对应 {@code audit_log.trace_id}。 */
    public static final String TRACE_ID = "X-KM-Trace-Id";

    /** 租户。对应 {@code audit_log.tenant_id}, 留空时消费端填 {@code default}。 */
    public static final String TENANT_ID = "X-KM-Tenant-Id";

    /** 触发者 (用户 ID / Agent ID)。对应 {@code audit_log.actor}。 */
    public static final String ACTOR = "X-KM-Actor";

    private AuditHeaders() {
    }
}
