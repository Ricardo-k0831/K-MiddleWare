package com.km.task.web;

import com.km.task.error.ExecutionMismatchException;
import com.km.task.error.IllegalTaskTransitionException;
import com.km.task.error.MaxRetryExceededException;
import com.km.task.error.RetryNotAllowedException;
import com.km.task.error.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 把异常翻译成 HTTP 响应。
 *
 * <p>没有这一层的话, 所有异常都会变成 500 —— 而 404 和 409 对调用方
 * (Python 侧的 TaskStateReporter) 意义完全不同:
 * 409 说明<b>上报方逻辑有问题</b>, 值得记一条 WARN;
 * 500 说明服务端自己坏了, 应该退避重试。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 统一错误响应体。字段名和 Python 侧读的一致。 */
    public record ApiError(String error, String message) {
    }

    /** 任务不存在 */
    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(TaskNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("TASK_NOT_FOUND", ex.getMessage()));
    }

    /**
     * 非法状态流转。
     *
     * <p>这里记 WARN 而不是 ERROR: 它不代表服务坏了, 而是说明上报方的
     * 状态逻辑有问题。日志里能直接看到 "从哪个状态到哪个状态被拒了",
     * 这是排查 Agent 卡在哪一步最直接的线索。
     */
    @ExceptionHandler(IllegalTaskTransitionException.class)
    public ResponseEntity<ApiError> handleIllegalTransition(IllegalTaskTransitionException ex) {
        log.warn("状态流转被拒: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ILLEGAL_TRANSITION", ex.getMessage()));
    }

    /**
     * 状态上报的 traceId 和任务对不上。
     *
     * <p>返回 409 而不是 400: 报文本身是合法的, 是"执行身份"冲突。
     * 调用方 (Python) 记 WARN 即可 —— 重试同一个请求不会变好, 需要人看。
     */
    @ExceptionHandler(ExecutionMismatchException.class)
    public ResponseEntity<ApiError> handleExecutionMismatch(ExecutionMismatchException ex) {
        log.warn("执行身份不匹配: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("EXECUTION_MISMATCH", ex.getMessage()));
    }

    /**
     * 重试请求被拒 (任务不在 FAILED / 还有执行未闭链 / 并发冲突)。
     *
     * <p>409 而不是 400: 请求本身没问题, 是任务当前状态不允许重试。
     * 记 WARN —— 和状态流转被拒同一个理由, 它是"控制面动作与事实不符"的
     * 直接线索。
     */
    @ExceptionHandler(RetryNotAllowedException.class)
    public ResponseEntity<ApiError> handleRetryNotAllowed(RetryNotAllowedException ex) {
        log.warn("重试被拒: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("RETRY_NOT_ALLOWED", ex.getMessage()));
    }

    /**
     * 重试次数用尽。
     *
     * <p>同样是 409, 但<b>只记 INFO</b>: 这是配置好的边界被触碰, 不是异常,
     * 也不是调用方的逻辑错误 —— 如果在这里刷 WARN, 一个卡在失败循环里的
     * 任务会把日志刷满, 反而遮住真正需要看的那条。
     */
    @ExceptionHandler(MaxRetryExceededException.class)
    public ResponseEntity<ApiError> handleMaxRetryExceeded(MaxRetryExceededException ex) {
        log.info("重试次数用尽: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("MAX_RETRY_EXCEEDED", ex.getMessage()));
    }

    /** @Valid 校验不过 —— 缺字段、超长、负数等 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", message));
    }

    /**
     * 请求体读不出来 —— 最常见的原因是枚举值写错了 (比如 toStatus 传了 "DONE")。
     *
     * <p>Jackson 的原始消息里已经列出了所有合法值, 直接透传就够用了,
     * 不用自己再拼一遍 (那样反而容易和枚举定义脱节)。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("MALFORMED_BODY", ex.getMostSpecificCause().getMessage()));
    }

    /** 兜底。走到这里说明是服务端自己的 bug, 必须留下完整堆栈。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("未预期的异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", ex.getClass().getSimpleName() + ": " + ex.getMessage()));
    }
}
