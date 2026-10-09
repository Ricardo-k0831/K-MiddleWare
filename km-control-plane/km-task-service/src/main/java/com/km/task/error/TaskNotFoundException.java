package com.km.task.error;

/** 任务不存在。对应 HTTP 404。 */
public class TaskNotFoundException extends RuntimeException {

    private final String agentTaskId;

    public TaskNotFoundException(String agentTaskId) {
        super("任务不存在: " + agentTaskId);
        this.agentTaskId = agentTaskId;
    }

    public String getAgentTaskId() {
        return agentTaskId;
    }
}
