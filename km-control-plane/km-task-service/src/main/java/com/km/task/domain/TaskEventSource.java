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
    SYSTEM_RETRY,

    /**
     * 心跳兜底扫描: 确认 Python 侧没有这条执行在跑之后, 由系统关掉链路。
     *
     * <p>和 {@link #SYSTEM_RETRY} 分开, 是因为它们回答两个不同的问题:
     * 前者是"失败之后谁又试了一遍", 后者是"谁发现这次执行其实已经死了"。
     * 混成一个值的话, 事后统计"有多少任务是心跳救回来的"就得去猜 detail。
     */
    SYSTEM_HEARTBEAT
}
