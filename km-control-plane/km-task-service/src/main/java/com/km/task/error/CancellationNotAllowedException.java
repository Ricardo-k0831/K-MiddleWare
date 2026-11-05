package com.km.task.error;

/**
 * 这个任务<b>现在不能取消</b>。对应 HTTP 409, 错误码 {@code CANCEL_NOT_ALLOWED}。
 *
 * <p>单独一个错误码而不是复用 {@code ILLEGAL_TRANSITION}: 取消是控制面的
 * 运维动作, 和 Python 的状态上报是两条完全不同的入口。运维看到错误码
 * 就该知道去查"谁在跑这个任务", 而不是"Python 报了非法状态"。
 *
 * <p>会走到这里的情况:
 * <ul>
 *   <li>任务已经是 COMPLETED / FAILED —— 已经结束, 没有可以停的东西</li>
 *   <li>两个取消请求几乎同时到达, CAS 输掉且复查发现状态已不是自己读到的那一个
 *       (取决于复查结果: 已经 CANCELLED 的按幂等成功返回, 不走这里)</li>
 * </ul>
 *
 * <p>注意 {@code CANCELLED} 本身<b>不</b>走这里: 取消是幂等的, 重复取消
 * 等于"它已经是你要的样子了", 返回 200。把它做成 409 只会让运维在
 * 超时重发时误以为出了问题。
 */
public class CancellationNotAllowedException extends RuntimeException {

    private final String agentTaskId;

    public CancellationNotAllowedException(String agentTaskId, String reason) {
        super("任务不能取消: task=" + agentTaskId + " (" + reason + ")");
        this.agentTaskId = agentTaskId;
    }

    public String getAgentTaskId() {
        return agentTaskId;
    }
}
