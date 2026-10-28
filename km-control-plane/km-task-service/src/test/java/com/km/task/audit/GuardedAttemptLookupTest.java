package com.km.task.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 截止时间 + 熔断的规则测试。
 *
 * <p>这个类守的是一个实测出来的问题: 数据库停机时直接查库会卡满 Hikari 的
 * 30 秒, 而 Python 的审计上报超时只有 2 秒 —— <b>降级逻辑存在, 但来不及生效</b>。
 * 所以断言分两半: 超时必须在限时内抛, 且失败之后的调用必须<b>立刻</b>返回
 * (不能再一个个去撞 500ms)。
 */
class GuardedAttemptLookupTest {

    private static final AttemptRef ATTEMPT =
            new AttemptRef("task-1", "trace-1", null);

    private CachedAttemptLookup delegate;
    private GuardedAttemptLookup guarded;

    @BeforeEach
    void setUp() {
        delegate = mock(CachedAttemptLookup.class);
        // 测试里把 500ms / 5s 缩到 50ms / 200ms —— 规则不变, 只是跑得快。
        guarded = new GuardedAttemptLookup(delegate, 50, 200);
    }

    @AfterEach
    void tearDown() {
        guarded.shutdown();
    }

    @Test
    void 正常路径直接返回核对结果() {
        when(delegate.findByTrace("trace-1")).thenReturn(ATTEMPT);

        assertEquals(ATTEMPT, guarded.findByTrace("trace-1"));
        verify(delegate, times(1)).findByTrace("trace-1");
    }

    @Test
    void 数据库挂住时在限时内抛不可用而不是无限等() {
        when(delegate.findByTrace("trace-1")).thenAnswer(invocation -> {
            Thread.sleep(3_000);   // 模拟 Hikari 在等连接
            return null;
        });

        long start = System.nanoTime();
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
                assertThrows(AttemptLookupUnavailableException.class,
                        () -> guarded.findByTrace("trace-1")));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 1_000,
                "超时没有生效, 实际等了 " + elapsedMs + "ms (数据库故障时会把整批审计拖死)");
    }

    @Test
    void 失败后熔断_同一批里后续事件不再一个个撞超时() {
        // ★ 这条比单次超时更重要 ★
        //
        // 一批最多 50 条事件。如果每条都独立撞一次 50ms, 这一批就是 2.5 秒;
        // 真实值是 500ms, 一批会变成 25 秒。熔断让"只有第一条付代价"。
        when(delegate.findByTrace("trace-1")).thenAnswer(invocation -> {
            Thread.sleep(3_000);
            return null;
        });

        assertThrows(AttemptLookupUnavailableException.class, () -> guarded.findByTrace("trace-1"));

        long start = System.nanoTime();
        assertThrows(AttemptLookupUnavailableException.class, () -> guarded.findByTrace("trace-1"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 50, "熔断没有生效, 第二次仍等了 " + elapsedMs + "ms");
        // 第二次根本没提交到线程池 —— 底层实现只被调用了一次。
        verify(delegate, times(1)).findByTrace("trace-1");
    }

    @Test
    void 底层抛异常同样降级成不可用并熔断() {
        when(delegate.findByTrace("trace-1")).thenThrow(new IllegalStateException("连接池耗尽"));

        AttemptLookupUnavailableException ex = assertThrows(
                AttemptLookupUnavailableException.class,
                () -> guarded.findByTrace("trace-1"));

        assertNotNull(ex.getCause());
        assertTrue(ex.getCause() instanceof IllegalStateException,
                "原始异常被吞掉了, 排查时看不到真实原因: " + ex.getCause());
    }

    @Test
    void 冷却结束后会重新尝试() throws InterruptedException {
        when(delegate.findByTrace("trace-1"))
                .thenThrow(new IllegalStateException("挂了"))
                .thenReturn(ATTEMPT);

        assertThrows(AttemptLookupUnavailableException.class, () -> guarded.findByTrace("trace-1"));

        Thread.sleep(250);   // 冷却 200ms
        assertEquals(ATTEMPT, guarded.findByTrace("trace-1"),
                "冷却结束后没有重新核对, 数据库恢复后审计会一直处于未核实状态");
    }
}
