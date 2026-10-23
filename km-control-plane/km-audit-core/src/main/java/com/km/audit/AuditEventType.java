package com.km.audit;

/**
 * 审计事件类型 —— 发生了什么。
 *
 * <p>取值与 {@code docker/init/mysql/01-schema.sql} 里
 * {@code audit_log.event_type} 列的注释对齐 (LLM_CALL / MCP_TOOL_CALL /
 * PROMPT_GUARD / SANDBOX_EXEC), 另外补了两个本项目实际会产生的事件。
 */
public enum AuditEventType {

    /** 一次 LLM 调用。tokenIn / tokenOut / latencyMs 在这类事件上才有意义。 */
    LLM_CALL,

    /** 一次 MCP 工具调用。由网关采集入参出参和真实耗时。 */
    MCP_TOOL_CALL,

    /** 任务生命周期事件 (创建 / 状态流转 / 终态)。 */
    TASK_LIFECYCLE,

    /** Prompt 注入防护命中 —— 检测到疑似注入时记录, 无论最终拦没拦。 */
    PROMPT_GUARD,

    /** 代码沙箱执行 (Phase 3)。 */
    SANDBOX_EXEC;
}
