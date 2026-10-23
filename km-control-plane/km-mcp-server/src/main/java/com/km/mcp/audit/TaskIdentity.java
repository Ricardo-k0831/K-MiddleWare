package com.km.mcp.audit;

import com.km.audit.AuditHeaders;
import io.modelcontextprotocol.common.McpTransportContext;

/**
 * "这次工具调用属于哪个任务" —— 从 MCP 传输层抽出来的身份, 一路带到审计事件里。
 *
 * <h3>它解决的问题</h3>
 *
 * MCP 协议里没有"任务"。Python Agent 连上网关后, 网关看到的只是一个 MCP
 * 会话和一堆 {@code tools/call} —— 任务 ID 只存在于<b>上层业务</b>, 网关
 * 除非被告知, 否则永远不知道。而审计要回答的第一个问题就是"哪个任务调了它"。
 *
 * <p>所以身份必须由客户端在 HTTP 头里带进来 (头名见
 * {@link AuditHeaders}), 由 {@code McpGatewayAuditConfig} 里的
 * {@code contextExtractor} 抽成 {@code McpTransportContext},
 * 再由 {@link #from(McpTransportContext)} 变成这个对象。
 *
 * <h3>为什么单独包一个类型, 不直接用 McpTransportContext</h3>
 *
 * 两个理由:
 * <ul>
 *   <li>{@code McpTransportContext} 的取值是 {@code Object}, 每用一次就要
 *       {@code String.valueOf} 一次, 而且写错 key 只是取到 null, 不报错。
 *       收口成一个类型, 拼错 key 这件事就只可能在一个地方发生。</li>
 *   <li>它是<b>传输层</b>的概念。{@code OrderServiceClient} 这种业务适配层
 *       不该认识 MCP 的 transport context —— 否则哪天换传输实现, 适配层
 *       跟着一起改。</li>
 * </ul>
 *
 * <h3>字段全都可以为 null, 而且这【不是】异常情况</h3>
 *
 * 客户端漏带头、或者有人直接 curl 网关调试, 都会得到一份"匿名"身份。
 * 这时的选择是<b>照常记审计, 只是这些字段为空</b>, 而不是拒绝请求 ——
 * 审计是旁路, 不能因为采集不到身份就让工具调不通。真正该做的是
 * 让这种调用在日志里显眼 ({@link #isAnonymous()}), 好被修掉。
 *
 * @param taskId   业务任务 ID, 未知时为 null
 * @param traceId  链路追踪 ID, 未知时为 null
 * @param tenantId 租户, 未知时为 null (消费端会填 {@code default})
 * @param actor    触发者, 未知时为 null
 */
public record TaskIdentity(String taskId, String traceId, String tenantId, String actor) {

    /** 什么都没有的身份。缓存一份, 匿名调用很常见, 没必要每次 new。 */
    public static final TaskIdentity ANONYMOUS = new TaskIdentity(null, null, null, null);

    /**
     * 从 MCP 的传输上下文里抽取身份信息。
     *
     * <p>传 null 也安全 —— 会得到 {@link #ANONYMOUS}。之所以要容这一手:
     * 工具方法的 {@code McpTransportContext} 参数在某些调用路径下可能是
     * 空的 ({@code McpTransportContext.EMPTY}), 而审计不该因为一个
     * 参数没给上就炸掉。
     */
    public static TaskIdentity from(McpTransportContext ctx) {
        if (ctx == null) {
            return ANONYMOUS;
        }
        return new TaskIdentity(
                str(ctx, AuditHeaders.TASK_ID),
                str(ctx, AuditHeaders.TRACE_ID),
                str(ctx, AuditHeaders.TENANT_ID),
                str(ctx, AuditHeaders.ACTOR));
    }

    /**
     * 有没有拿到任务身份。
     *
     * <p>只看 taskId: 另外三个是补充维度, 只有 taskId 是"能不能把这次调用
     * 归到某件事上"的分水岭。
     */
    public boolean isAnonymous() {
        return taskId == null || taskId.isBlank();
    }

    /**
     * 从 MCP 的传输上下文里取值并归一化。
     *
     * <p>{@code String.valueOf} 之后还要判空白串: HTTP 头里一个空串和一个
     * 缺失的头, 语义上都是"没带", 但落到库里一个是 {@code ""} 一个是
     * {@code NULL}。审计表里同时存在这两种"空"最消耗排查时间, 统一成 null。
     */
    private static String str(McpTransportContext ctx, String key) {
        Object value = ctx.get(key);
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
