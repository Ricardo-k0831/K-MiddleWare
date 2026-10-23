package com.km.mcp.audit;

import com.km.audit.AuditEvent;
import org.springframework.web.client.HttpClientErrorException;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 把"一次调用出了什么事"翻译成 {@code audit_log.status}。
 *
 * <h3>为什么这件事值得单独一个类, 而不是在调用处写个三元表达式</h3>
 *
 * 因为"算不算失败"是个<b>有争议的判断</b>, 而不是一个事实。判断标准一旦
 * 分散在几个 catch 块里, 同一件事在不同地方就会被记成不同的 status,
 * 而那意味着按 status 做的统计 (错误率、告警阈值) 全部不可信。
 *
 * <h3>核心判断: 4xx 【不算】ERROR</h3>
 *
 * 这条最反直觉, 所以展开说。
 *
 * <p>网关调用存量订单服务, 后端返回 404 "订单不存在"。从 HTTP 的角度这是
 * 个错误响应; 从<b>系统</b>的角度, 这次调用完全成功 —— 后端活着, 它明确地
 * 回答"没有这个东西"。真正值得报警的是另一种情况: 后端连不上、5xx、
 * 超时 —— 那时你才知道"订单查不到"是因为<b>系统坏了</b>。
 *
 * <p>如果 404 也记成 ERROR, 后果是 ERROR 率被用户输错订单号的行为污染。
 * 而"Agent 老在查不存在的订单"这件事本身是<b>提示词质量问题</b>, 它的证据
 * 在 {@code responseBody} 里 (工具返回的 {@code error: true} 那段话),
 * 不在 status 里。一个指标想表达两件事, 最后两件都表达不清。
 *
 * <p>429 是唯一的例外: Too Many Requests 说明系统在<b>限流</b>, 那是
 * 容量问题, 必须算 ERROR。
 *
 * <h3>为什么认的是"根因"而不是直接被抛出的异常</h3>
 *
 * {@code OrderServiceClient} 会把所有异常包成自己的 {@code OrderServiceException},
 * 所以直接被抛出的类型永远是它, 信息量为零。真正的线索在 cause 链的末端 ——
 * 是 {@code HttpClientErrorException$NotFound} 还是
 * {@code ResourceAccessException} 包着 {@code SocketTimeoutException}。
 */
public final class CallStatus {

    /** 429 单独判: 见类注释 */
    private static final int TOO_MANY_REQUESTS = 429;

    private CallStatus() {
    }

    /**
     * @param failure 调用抛出的异常; <b>null 表示成功</b>
     * @return {@link AuditEvent#OK} / {@link AuditEvent#TIMEOUT} / {@link AuditEvent#ERROR}
     */
    public static String of(Throwable failure) {
        if (failure == null) {
            return AuditEvent.OK;
        }

        Throwable cause = rootCause(failure);

        if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
            return AuditEvent.TIMEOUT;
        }

        // 4xx 当成"后端正常回答了": 见类注释。429 走下面的 ERROR。
        if (cause instanceof HttpClientErrorException httpError
                && httpError.getStatusCode().is4xxClientError()
                && httpError.getStatusCode().value() != TOO_MANY_REQUESTS) {
            return AuditEvent.OK;
        }

        return AuditEvent.ERROR;
    }

    /**
     * 走到 cause 链末端。
     *
     * <p>{@code ResourceAccessException} 这类包装异常自己的 message 是
     * "I/O error on GET request ...", 什么也说明不了; 决定性质的
     * {@code SocketTimeoutException} / {@code ConnectException} 永远在下面几层。
     *
     * <p>带自环保护: cause 链理论上可能成环 (某个第三方库自己包装自己),
     * 真成环时这里会死循环, 而审计链路上死循环意味着工具调用永远不返回 ——
     * 一个为了记日志而把业务挂死的 bug, 不值得省那三行判断。
     */
    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        for (int depth = 0; depth < 32; depth++) {
            Throwable next = current.getCause();
            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
        return current;
    }
}
