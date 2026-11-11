package com.km.mcp.guard;

import com.km.mcp.client.OrderServiceClient.OrderServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断器的行为 —— 用真的 Resilience4j, 不 mock。
 *
 * <p>mock 掉熔断库等于测自己写的 if, 而这里要验的恰恰是状态机:
 * CLOSED -> OPEN -> HALF_OPEN -> CLOSED 的转换条件。用真库才有意义,
 * 代价是有一个用例要等 1 秒 (等熔断器的试探窗口)。
 */
class BackendCircuitBreakerTest {

    private final GatewayGuardProperties properties = properties();

    @Test
    void 连续失败达到阈值后打开_之后不再执行调用() {
        BackendCircuitBreaker breaker = new BackendCircuitBreaker(properties);
        AtomicInteger executions = new AtomicInteger();

        // 2 次失败就会触发 (最少调用数 2, 失败率 50%)
        for (int i = 0; i < 2; i++) {
            assertThrows(OrderServiceException.class, () -> breaker.call(() -> {
                executions.incrementAndGet();
                throw new OrderServiceException("后端炸了", new ConnectException("refused"));
            }));
        }

        // 第 3 次: 熔断已打开, action 根本不该执行
        BackendUnavailableException ex = assertThrows(BackendUnavailableException.class,
                () -> breaker.call(() -> {
                    executions.incrementAndGet();
                    return "不会到这里";
                }));

        assertEquals(2, executions.get(), "熔断打开后仍然发起了后端调用 —— 线程照样被占满");
        assertTrue(ex.getMessage().contains("熔断"), ex.getMessage());
    }

    @Test
    void 客户端4xx不算失败_不会把熔断打开() {
        // 用户连续输错订单号是正常业务, 不能让这批流量把正常查询也挡掉。
        // 这是最容易被写错的一条: 4xx 在 HTTP 语义里是"错误响应",
        // 但在系统语义里是"后端正常工作"。
        BackendCircuitBreaker breaker = new BackendCircuitBreaker(properties);
        AtomicInteger executions = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            assertThrows(OrderServiceException.class, () -> breaker.call(() -> {
                executions.incrementAndGet();
                throw new OrderServiceException("404", HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null));
            }));
        }

        // 仍然每次真调 —— 说明熔断没开
        assertEquals(10, executions.get());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED,
                breaker.state());
    }

    @Test
    void 半开试探成功一次就关闭() throws InterruptedException {
        properties.getCircuitBreaker().setWaitDurationInOpenStateSeconds(1);
        properties.getCircuitBreaker().setPermittedNumberOfCallsInHalfOpenState(1);
        BackendCircuitBreaker breaker = new BackendCircuitBreaker(properties);

        for (int i = 0; i < 2; i++) {
            assertThrows(OrderServiceException.class, () -> breaker.call(() -> {
                throw new OrderServiceException("5xx",
                        HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bad",
                                HttpHeaders.EMPTY, null, null));
            }));
        }
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, breaker.state());

        // 等过试探窗口。真实环境里这 1 秒是用户感知不到的 (他们在收到
        // "稍后再试"之后才会再来), 测试里只能真等。
        Thread.sleep(1200);

        // 试探调用: 这次成功
        assertEquals("ok", breaker.call(() -> "ok"));
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED,
                breaker.state(), "试探成功后应当自动恢复, 不需要人工干预");
    }

    @Test
    void 半开试探失败会重新打开() throws InterruptedException {
        properties.getCircuitBreaker().setWaitDurationInOpenStateSeconds(1);
        properties.getCircuitBreaker().setPermittedNumberOfCallsInHalfOpenState(1);
        BackendCircuitBreaker breaker = new BackendCircuitBreaker(properties);

        for (int i = 0; i < 2; i++) {
            assertThrows(OrderServiceException.class, () -> breaker.call(() -> {
                throw new OrderServiceException("5xx",
                        new ResourceAccessException("I/O", new ConnectException("refused")));
            }));
        }
        Thread.sleep(1200);

        // 试探仍然失败 -> 立刻重新打开, 不能给已经挂掉的后端持续加压
        assertThrows(OrderServiceException.class, () -> breaker.call(() -> {
            throw new OrderServiceException("还是坏的",
                    new ResourceAccessException("I/O", new ConnectException("refused")));
        }));
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, breaker.state());
    }

    @Test
    void 关闭时直通_连状态都不统计() {
        properties.getCircuitBreaker().setEnabled(false);
        BackendCircuitBreaker breaker = new BackendCircuitBreaker(properties);
        AtomicInteger executions = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            assertThrows(OrderServiceException.class, () -> breaker.call(() -> {
                executions.incrementAndGet();
                throw new OrderServiceException("x", new ConnectException("refused"));
            }));
        }

        assertEquals(10, executions.get());
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.DISABLED,
                breaker.state());
    }

    private static GatewayGuardProperties properties() {
        GatewayGuardProperties props = new GatewayGuardProperties();
        props.getCircuitBreaker().setMinimumNumberOfCalls(2);
        props.getCircuitBreaker().setFailureRateThreshold(50.0f);
        props.getCircuitBreaker().setSlidingWindowSizeSeconds(10);
        props.getCircuitBreaker().setWaitDurationInOpenStateSeconds(10);
        return props;
    }
}
