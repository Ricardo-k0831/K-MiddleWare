package com.km.mcp.audit;

import com.km.audit.AuditEvent;
import com.km.mcp.guard.DegradedCallException;
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
 * <h3>DEGRADED: 调用被主动挡住, 根本没打后端</h3>
 *
 * <p>网关自己的限流和熔断会让一部分调用<b>不发出</b>。这类结果记 DEGRADED
 * 而不是 ERROR: 保护机制正在按预期工作, 后端可能还活着 (只是太忙)。
 * 如果和真正的后端故障混成一个 status, 限流告警会把后端故障掩盖掉。
 * 判断依据是 {@link DegradedCallException} 这个父类, 见它的类注释。
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
     * @return {@link AuditEvent#OK} / {@link AuditEvent#TIMEOUT} /
     *         {@link AuditEvent#ERROR} / {@link AuditEvent#DEGRADED}
     */
     // 根据异常返回调用状态
    public static String of(Throwable failure) {
        if (failure == null) {
            return AuditEvent.OK;
        }

        // 先判降级: 这类异常由网关自己抛出, 没有 HTTP cause, 走到
        // rootCause 只会拿到它自己, 结果和"后端故障"无法区分。
        if (failure instanceof DegradedCallException) {
            return AuditEvent.DEGRADED;
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
     * 这次失败该不该计入<b>熔断器</b>的失败率。
     *
     * <p>和 {@link #of} 共用同一套判断 (4xx 不算失败), 只是返回值不同:
     * 审计要区分 OK/ERROR/TIMEOUT/DEGRADED 四种, 熔断器只关心
     * "算不算后端故障"这一个布尔值。
     *
     * <p>两个判断放在同一个类里不是巧合: 它们一旦分家, 就会出现
     * "审计里显示后端一片 ERROR, 熔断器却认为天下太平"这种自相矛盾的状态,
     * 而排查时你会同时相信这两个数字。
     *
     * @param failure 调用抛出的异常; null (成功) 不算失败
     * @return true 表示这是后端侧的故障, 应当计入失败率
     */
    // 利用AuditEvent判断是否是后端故障
    public static boolean isBackendFailure(Throwable failure) {
        String status = of(failure);
        // 429 , TIMEOUT 和 ERROR 是后端故障; OK (含 4xx) 与 DEGRADED 不是。
        return AuditEvent.ERROR.equals(status) || AuditEvent.TIMEOUT.equals(status);
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
