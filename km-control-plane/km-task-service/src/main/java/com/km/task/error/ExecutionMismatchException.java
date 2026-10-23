package com.km.task.error;

/**
 * 状态上报带来的 {@code traceId} 和这个任务对不上。对应 HTTP 409。
 *
 * <p>和 {@link IllegalTaskTransitionException} 刻意分开: 那一个是
 * "状态不能这么变", 这一个是"<b>你连自己属于哪次执行都说错了</b>"。
 * 两者都返回 409, 但错误码不同 (`EXECUTION_MISMATCH` vs `ILLEGAL_TRANSITION`),
 * 排查时看码就知道该查状态机还是查执行身份。
 *
 * <p>会走到这里的情况:
 * <ul>
 *   <li>客户端把 trace 传错了 (粘贴了别的任务的值)</li>
 *   <li>该 trace 属于另一个任务 —— 绝不能把事件记到这个任务头上</li>
 *   <li>trace 查不到 —— 从未创建过的执行</li>
 *   <li>这次执行已经闭链, 又来了一条不同的状态上报 (迟到的旧执行)</li>
 * </ul>
 */
public class ExecutionMismatchException extends RuntimeException {

    private final String agentTaskId;
    private final String traceId;

    public ExecutionMismatchException(String agentTaskId, String traceId, String reason) {
        super("执行身份不匹配: task=" + agentTaskId + " trace=" + traceId + " (" + reason + ")");
        this.agentTaskId = agentTaskId;
        this.traceId = traceId;
    }

    public String getAgentTaskId() {
        return agentTaskId;
    }

    public String getTraceId() {
        return traceId;
    }
}
