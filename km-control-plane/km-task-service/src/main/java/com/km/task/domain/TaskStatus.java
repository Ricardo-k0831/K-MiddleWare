package com.km.task.domain;

/**
 * Agent 任务状态。
 *
 * 枚举名与 MySQL {@code agent_task.status} 字段严格一一对应 ——
 * 中间不做任何映射转换, 免得两边慢慢地漂移。
 *
 * <pre>
 *                              ┌──────────────────┐
 *                              │                  │
 *                              ▼                  │
 *   ┌─────────┐          ┌──────────┐      ┌──────────────┐
 *   │ PENDING │─────────▶│ THINKING │─────▶│ TOOL_CALLING │
 *   └─────────┘          └──────────┘      └──────────────┘
 *        │                    │                    │
 *        ▼                    ▼                    ▼
 *   ┌───────────────────────────────────────────────────┐
 *   │  COMPLETED / FAILED        (任意非终态 ──▶ CANCELLED) │
 *   └───────────────────────────────────────────────────┘
 * </pre>
 *
 * 注意 {@code TOOL_CALLING -> THINKING} 是合法且高频的流转:
 * ReAct 的本质是循环, 调完工具要回到思考, 一次请求可能走好几轮。
 */
public enum TaskStatus {

    /** 已创建, 尚未开始 */
    PENDING,

    /** Agent 正在调 LLM (思考 / 生成) */
    THINKING,

    /** Agent 正在执行一次 MCP 工具调用 */
    TOOL_CALLING,

    /** 正常结束 */
    COMPLETED,

    /** 异常结束 */
    FAILED,

    /** 人工取消 */
    CANCELLED;

    /**
     * 终态 —— 到了这里就不能再走了。
     *
     * ★ 这个判断是整个状态机的核心约束, 单独抽成方法是为了只有一处定义。★
     * 一旦任务进了终态, 任何后续上报都必须被拒绝: 否则一个已经记成
     * FAILED 的任务可能又被改成 COMPLETED, 事件表和审计就全乱了。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
