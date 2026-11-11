package com.km.mcp.guard;

/**
 * 熔断器处于打开状态, 这次调用<b>没有发出去</b>。
 *
 * <p>和"后端调用失败"是两件事: 后者会给后端增加负载, 前者正是为了不再增加
 * 负载。审计里两者都记 DEGRADED, 但错误信息不同 —— 看到这个类名就知道
 * 熔断器在工作, 该去看后端为什么坏了, 而不是怀疑网关。
 */
public class BackendUnavailableException extends DegradedCallException {

    public BackendUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
