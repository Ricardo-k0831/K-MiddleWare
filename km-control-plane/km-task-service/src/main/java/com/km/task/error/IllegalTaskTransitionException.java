package com.km.task.error;

import com.km.task.domain.TaskStatus;

/**
 * 非法状态流转。对应 HTTP 409。
 *
 * 这个异常被抛出说明<b>上报方 (通常是 Python 侧) 的逻辑有问题</b>,
 * 所以除了拒绝请求, 调用方还会记一条 WARN 日志 —— 它是排查
 * "Agent 到底跑到哪一步卡住了"的最直接线索。
 */
public class IllegalTaskTransitionException extends RuntimeException {

    private final String agentTaskId;
    private final String from;
    private final String to;

    public IllegalTaskTransitionException(String agentTaskId, TaskStatus from, TaskStatus to) {
        super("非法状态流转: " + from + " -> " + to + " (task=" + agentTaskId + ")");
        this.agentTaskId = agentTaskId;
        this.from = from == null ? null : from.name();
        this.to = to == null ? null : to.name();
    }

    /**
     * 并发上报导致 CAS 失败时用这个构造。
     *
     * 和"规则上不允许"是两回事: 规则上可能完全合法, 只是在我们读完状态、
     * 准备写的这个间隙里, 别人先写了一笔。
     */
    public IllegalTaskTransitionException(String agentTaskId, String from, String to, String reason) {
        super("状态流转失败: " + from + " -> " + to + " (task=" + agentTaskId + "): " + reason);
        this.agentTaskId = agentTaskId;
        this.from = from;
        this.to = to;
    }

    public String getAgentTaskId() {
        return agentTaskId;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
