package com.km.mcp.guard;

/**
 * 这次工具调用因为超出限流额度被挡掉了 —— <b>没有打到后端</b>。
 *
 * <p>不带"还剩多少秒"之类的重试建议: 令牌桶在突发流量下恢复时间是个滑动量,
 * 算出来的数字不准确, 而 Agent 会把不准确的数字原样告诉用户。
 * 只表达"现在不行, 稍后再试", 这是唯一确定的事实。
 */
public class ToolRateLimitedException extends DegradedCallException {

    private final String scope;

    /**
     * @param scope 被哪个维度挡住: {@code "tool"} 或 {@code "task"}。
     *              写进日志和审计的错误信息 —— 排查时需要知道是"这个任务太猛"
     *              还是"整体流量太大", 两者的处理方式完全不同。
     */
    public ToolRateLimitedException(String message, String scope) {
        super(message);
        this.scope = scope;
    }

    public String getScope() {
        return scope;
    }
}
