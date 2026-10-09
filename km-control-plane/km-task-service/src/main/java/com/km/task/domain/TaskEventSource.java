package com.km.task.domain;

/**
 * 是谁触发了这次状态变更。与 {@code agent_task_event.source} 字段一一对应。
 *
 * 做成枚举而不是裸字符串, 是为了让非法值在进入 Controller 时就被 Jackson
 * 拦掉 (400), 而不是写进库里变成一个谁也不知道什么意思的值。
 */
public enum TaskEventSource {

    /** Java 网关 (建任务、人工取消) */
    JAVA_GATEWAY,

    /** Python Agent 运行时 (ReAct 循环里的状态上报) */
    PYTHON_RUNTIME,

    /** 系统的超时重试 / 补偿任务 */
    SYSTEM_RETRY
}
