package com.km.mcp.audit;

import com.km.audit.AuditEvent;
import com.km.mcp.client.OrderServiceClient.OrderServiceException;
import com.km.mcp.guard.BackendUnavailableException;
import com.km.mcp.guard.ToolRateLimitedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallStatus} 的判定规则 —— 这些规则决定了审计表里的 status 一列怎么写。
 *
 * <p>重点在<b>一条反直觉的规则</b>: 4xx 记 OK。它看起来像 bug, 所以要有测试
 * 把它钉住 —— 否则将来有人"顺手修一下", 把 404 改成 ERROR, 而错误率的
 * 统计口径就此静默变化。
 */
class CallStatusTest {

    @Test
    void 没抛异常就是OK() {
        assertEquals(AuditEvent.OK, CallStatus.of(null));
    }

    @Test
    void 后端明确回答404算成功() {
        // 后端活着, 它明确地说"没有这个东西"。系统工作正常。
        // 记成 ERROR 的话, 错误率会被用户输错订单号的行为污染。
        assertEquals(AuditEvent.OK, CallStatus.of(notFound()));
    }

    @Test
    void 限流429算ERROR_不算成功() {
        // 唯一的 4xx 例外: 限流是容量问题, 必须被算成故障, 否则永远没人去扩。
        HttpClientErrorException tooMany = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", HttpHeaders.EMPTY, null, null);
        assertEquals(AuditEvent.ERROR, CallStatus.of(tooMany));
    }

    @Test
    void 后端返回5xx算ERROR() {
        HttpServerErrorException boom = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, null, null);
        assertEquals(AuditEvent.ERROR, CallStatus.of(boom));
    }

    @Test
    void 连不上是ERROR() {
        assertEquals(AuditEvent.ERROR,
                CallStatus.of(new ResourceAccessException("I/O error", new ConnectException("refused"))));
    }

    @Test
    void 读超时是TIMEOUT_不是ERROR() {
        // 超时和"连不上"要分开: 前者是对方慢或者自己等太短, 后者是路不通。
        // 排查方向完全不同, 混成一个值就再也分不出来了。
        assertEquals(AuditEvent.TIMEOUT, CallStatus.of(
                new ResourceAccessException("I/O error", new SocketTimeoutException("read timed out"))));
    }

    @Test
    void 认的是根因_不是被包在最外面的那个异常() {
        // OrderServiceClient 会把所有异常包成自己的 OrderServiceException,
        // 所以直接被抛出的类型永远是它, 信息量为零。真正的线索在 cause 链末端。
        assertEquals(AuditEvent.OK,
                CallStatus.of(new OrderServiceException("调用存量服务失败", notFound())));

        assertEquals(AuditEvent.TIMEOUT,
                CallStatus.of(new OrderServiceException("调用存量服务失败",
                        new ResourceAccessException("I/O error", new SocketTimeoutException("timeout")))));
    }

    @Test
    void 被限流挡掉记DEGRADED_不是ERROR() {
        // 这不是后端坏了, 是保护机制在工作。记成 ERROR 会让限流告警
        // 掩盖真正的后端故障 —— 两个数字混在一起就没法用了。
        assertEquals(AuditEvent.DEGRADED,
                CallStatus.of(new ToolRateLimitedException("太频繁", "task")));
    }

    @Test
    void 熔断打开时没发出去的调用记DEGRADED() {
        assertEquals(AuditEvent.DEGRADED,
                CallStatus.of(new BackendUnavailableException("熔断中",
                        new IllegalStateException("circuit open"))));
    }

    @Test
    void 降级判断先于根因判断() {
        // 限流/熔断异常的 cause 里可能挂着别的异常 (BackendUnavailableException
        // 挂着 CallNotPermittedException), 但它们不是后端故障。如果先走
        // rootCause, 就会被认成 ERROR, 而熔断器又会把这个 ERROR 当成
        // "又一次失败" —— 越熔越开, 正确性被自我强化成一个死循环。
        assertEquals(AuditEvent.DEGRADED,
                CallStatus.of(new BackendUnavailableException("熔断中", notFound())));
    }

    @Test
    void 只有后端故障才计入熔断失败率() {
        // 和 of() 共用同一套规则: 4xx 既不是 ERROR 也不能让熔断器开闸。
        assertFalse(CallStatus.isBackendFailure(null));
        assertFalse(CallStatus.isBackendFailure(notFound()));
        assertTrue(CallStatus.isBackendFailure(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "boom", HttpHeaders.EMPTY, null, null)));
        assertTrue(CallStatus.isBackendFailure(
                new ResourceAccessException("I/O error", new SocketTimeoutException("timeout"))));
        assertFalse(CallStatus.isBackendFailure(new ToolRateLimitedException("太频繁", "tool")));
    }

    @Test
    void cause链自环也不会死循环() {
        // 理论上不该发生, 但真发生了的话这里是死循环 —— 而审计链路上的死循环
        // 意味着工具调用永远不返回, 为了记日志把业务挂死是绝不能接受的。
        SelfReferencingException loop = new SelfReferencingException();

        assertEquals(AuditEvent.ERROR, CallStatus.of(loop));
    }

    private static HttpClientErrorException notFound() {
        return HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null);
    }

    /** 一个 cause 指向自己的异常。 */
    private static final class SelfReferencingException extends RuntimeException {
        SelfReferencingException() {
            super("自环");
        }

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
