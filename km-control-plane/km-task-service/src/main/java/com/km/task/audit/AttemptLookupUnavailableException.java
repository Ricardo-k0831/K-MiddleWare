package com.km.task.audit;

/**
 * 身份核对<b>没能在限时内给出结果</b> —— 不是"查无此人"。
 *
 * <p>和 {@link AttemptLookup} 的契约里"返回 null = 明确查无"是同一件事的两面:
 * 这个异常表示"核对这件事本身失败了" (超时 / 数据库连不上 / 线程池满),
 * {@link AuditIngestService} 收到它之后走降级 (收下并计入 {@code unverified}),
 * <b>而不是</b>按"trace 未知"处理。
 *
 * <p>为什么要单独一个类型而不是随便一个 {@link RuntimeException}: 降级日志里
 * 这两类原因的排查方向完全不同 —— 一个是"调用方传错了 trace", 一个是
 * "数据库/核对链路坏了"。混在一起, 值班的人会从错误的方向查起。
 */
public class AttemptLookupUnavailableException extends RuntimeException {

    public AttemptLookupUnavailableException(String message) {
        super(message);
    }

    public AttemptLookupUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
