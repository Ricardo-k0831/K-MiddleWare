package com.km.task.error;

/**
 * 重试次数已用尽 ({@code retry_count >= max_retry})。对应 HTTP 409,
 * 错误码 {@code MAX_RETRY_EXCEEDED}。
 *
 * <p>单独一个类型而不是塞进 {@link RetryNotAllowedException}: 这是
 * <b>预期内的边界</b> —— 用户或调度器按策略试到了上限, 不需要任何人去查
 * "为什么不允许", 只需要知道"不能再试了"。而 {@code RETRY_NOT_ALLOWED}
 * 通常意味着调用方对任务当前状态的理解有误, 值得排查。
 *
 * <p>注意判定用的是 {@code >=} 而不是 {@code >}: {@code retry_count} 记的是
 * <b>已经重试过几次</b>, 不是"总共执行了几次"。{@code max_retry=3} 表示
 * 第一次执行之外最多再试 3 次, 所以拒绝发生在 {@code retry_count == 3}。
 */
public class MaxRetryExceededException extends RuntimeException {

    private final String agentTaskId;
    private final int retryCount;
    private final int maxRetry;

    public MaxRetryExceededException(String agentTaskId, int retryCount, int maxRetry) {
        super("重试次数已用尽: task=" + agentTaskId
                + " (retryCount=" + retryCount + ", maxRetry=" + maxRetry + ")");
        this.agentTaskId = agentTaskId;
        this.retryCount = retryCount;
        this.maxRetry = maxRetry;
    }

    public String getAgentTaskId() {
        return agentTaskId;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetry() {
        return maxRetry;
    }
}
