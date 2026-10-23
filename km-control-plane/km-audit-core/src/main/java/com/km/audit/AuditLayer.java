package com.km.audit;

/**
 * 审计的采集层 —— 事件是"在哪一层"被记下来的。
 *
 * <h3>为什么用枚举而不是字符串</h3>
 *
 * 同理 {@code com.km.task.domain.TaskEventSource}: 取值是定长集合,
 * 写成枚举能让"写错一个字母"在编译期就被拦住, 而不是等到查询时
 * 发现某层的数据一条都查不到。
 *
 * <p>那个枚举在 km-task-service 里, 这里刻意不写成 {@code @link} ——
 * 本模块是各层共用的库, 不该为了一个 javadoc 链接反向依赖任务服务。
 *
 * <h3>为什么存量微服务也在列表里</h3>
 *
 * 技术上, {@code km-mock-order-service} 是纯内存的 mock, 没有数据库,
 * 也不可能自己去写审计。但这一层的信息必须被记录下来 ——
 * "网关代它记"就是 {@link #LEGACY_SERVICE} 这个取值的含义:
 * 事件描述的是<b>存量服务那次调用</b>, 采集点是 MCP 网关。
 *
 * <p>这正是"零改造接入"的关键: 存量服务一行代码不用改,
 * 它的行为照样被完整审计。加这一层不是自相矛盾, 而是把
 * "审计对象"和"审计采集点"这两件事分开。
 */
public enum AuditLayer {

    /** Python Agent 运行时 —— LLM 调用、ReAct 循环、Prompt 层的事 */
    AGENT_RUNTIME,

    /** Java MCP 网关 (km-mcp-server) —— 工具调用、鉴权、参数校验、限流 */
    MCP_GATEWAY,

    /** Java 任务服务 (km-task-service) —— 任务生命周期 */
    TASK_SERVICE,

    /** 存量微服务调用, 由网关代理采集 —— 见类注释 */
    LEGACY_SERVICE;
}
