package com.km.mcp.guard;

/**
 * "这次调用没有真正打到后端"的统一父类 —— 限流挡掉、熔断打开都属于它。
 *
 * <h3>为什么需要一个单独的父类</h3>
 *
 * <p>{@link com.km.mcp.audit.CallStatus} 要把异常翻译成审计状态, 工具层也要
 * 根据异常类型给 Agent 不同的话术。如果限流和熔断各自是一个独立的类, 这两处
 * 都会变成 {@code if (ex instanceof A || ex instanceof B)} —— 以后再加一种
 * "没打后端就失败"的情况 (比如权限不足) 就得回来改两个地方, 漏一处就出现
 * "审计记成 ERROR、Agent 说成订单不存在"的不一致。
 *
 * <p>把它们归到同一个父类下, 判断就只剩一次 {@code instanceof},
 * 新增子类自动获得同样的语义。这也正是 {@link CallStatus} 存在的理由:
 * "算不算失败"必须是<b>一条规则</b>, 而不是散落在各处的条件。
 *
 * <h3>为什么算 DEGRADED 而不是 ERROR</h3>
 *
 * <p>ERROR 的意思是"调用了, 而且坏了" —— 它应该拉警报、说明后端有问题。
 * 而 DEGRADED 的意思是"调用被主动降级, 这是保护机制按预期在工作":
 * 后端可能还活着 (只是太忙), 也可能真的挂了 (但熔断已经挡住了, 没有再次
 * 伤害它)。混成一类的话, 限流触发的告警会掩盖真正的后端故障。
 */
public class DegradedCallException extends RuntimeException {

    public DegradedCallException(String message) {
        super(message);
    }

    public DegradedCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
