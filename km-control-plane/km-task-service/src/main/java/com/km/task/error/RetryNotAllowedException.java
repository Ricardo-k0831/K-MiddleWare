package com.km.task.error;

/**
 * 这个任务<b>现在不能重试</b>。对应 HTTP 409, 错误码 {@code RETRY_NOT_ALLOWED}。
 *
 * <p>和 {@link IllegalTaskTransitionException} 分开, 不是因为 HTTP 码不同
 * (两者都是 409), 而是因为<b>触发方不同</b>: 那条来自 Agent 运行时的
 * 状态上报, 这条来自控制面的人工/系统重试请求。运维看到错误码就知道
 * 该查"Python 报错了状态"还是"这个任务为什么还是 PENDING / 还在跑"。
 *
 * <p>会走到这里的情况:
 * <ul>
 *   <li>任务不是 FAILED —— 只有失败的任务才谈得上重试</li>
 *   <li>任务状态是 FAILED, 但仍有一次执行没闭链 —— 数据不一致, 先把这次
 *       执行处理掉, 不能凭空再开一条链</li>
 *   <li>两个重试请求几乎同时到达, CAS 输掉的那一个</li>
 * </ul>
 *
 * <p>"次数用尽"不走这里, 用 {@link MaxRetryExceededException} —— 那是另一种
 * 处理方式: 前者值得人看一眼, 后者是配置的边界, 属于预期内的拒绝。
 */
public class RetryNotAllowedException extends RuntimeException {

    private final String agentTaskId;

    public RetryNotAllowedException(String agentTaskId, String reason) {
        super("任务不能重试: task=" + agentTaskId + " (" + reason + ")");
        this.agentTaskId = agentTaskId;
    }

    public String getAgentTaskId() {
        return agentTaskId;
    }
}
