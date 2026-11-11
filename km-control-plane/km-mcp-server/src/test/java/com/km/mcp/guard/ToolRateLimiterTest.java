package com.km.mcp.guard;

import com.km.mcp.audit.TaskIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流器的决策逻辑 —— 不含 Redis。
 *
 * <p>这里测的是<b>本项目自己的选择</b>: 先查哪个维度、超限了报什么、Redis
 * 挂了怎么办、任务 ID 怎么变成 key。这些和 Redis 无关, 用假的
 * {@link RateLimitStore} 就能测 —— 起一个 Redis 只会让测试变慢变脆。
 * 真正和 Redis 打交道的 Lua 脚本由 E2E 脚本验证。
 */
class ToolRateLimiterTest {

    private static final TaskIdentity IDENTITY =
            new TaskIdentity("task-1", "trace-1", "km", "u-1");

    private final FakeStore store = new FakeStore();
    private final GatewayGuardProperties properties = new GatewayGuardProperties();
    private final ToolRateLimiter limiter = new ToolRateLimiter(properties, store);

    @Test
    void 放行时不抛异常() {
        assertDoesNotThrow(() -> limiter.check("get_order_by_id", IDENTITY));
        assertEquals(2, store.calls.size(), "任务维度和工具维度各要查一次");
    }

    @Test
    void 任务桶先查_超限时只报task() {
        // 第二个 key 是工具桶。故意让工具桶有令牌 —— 如果实现先查工具桶,
        // 这个用例就会测到 scope=tool, 而我们要的正是"任务先被拒"。
        store.denyKeys.add(properties.getRateLimit().getKeyPrefix() + ":task:task-1");

        ToolRateLimitedException ex = assertThrows(ToolRateLimitedException.class,
                () -> limiter.check("get_order_by_id", IDENTITY));

        assertEquals("task", ex.getScope());
        assertEquals(1, store.calls.size(),
                "任务桶已经拒了, 不该再去消耗共享的工具桶令牌");
    }

    @Test
    void 任务桶通过后查工具桶_超限报tool() {
        store.denyKeys.add(properties.getRateLimit().getKeyPrefix() + ":tool:get_order_by_id");

        ToolRateLimitedException ex = assertThrows(ToolRateLimitedException.class,
                () -> limiter.check("get_order_by_id", IDENTITY));

        assertEquals("tool", ex.getScope());
    }

    @Test
    void 存储故障时fail_open() {
        // ★ 这是本类最重要的用例 ★
        // 限流器自己坏了不能变成"整个平台不可用"。Redis 抖动时的正确行为
        // 是放行, 让后端去承受 (它扛得住正常流量), 而不是拒绝所有请求。
        store.failure = new IllegalStateException("redis down");

        assertDoesNotThrow(() -> limiter.check("get_order_by_id", IDENTITY));
    }

    @Test
    void 关闭时完全不碰存储() {
        properties.getRateLimit().setEnabled(false);

        assertDoesNotThrow(() -> limiter.check("get_order_by_id", IDENTITY));
        assertTrue(store.calls.isEmpty(), "关了还去查 Redis, 等于没关");
    }

    @Test
    void key带前缀和维度() {
        limiter.check("get_order_by_id", IDENTITY);

        assertTrue(store.calls.contains("km:gateway:rl:task:task-1"), store.calls.toString());
        assertTrue(store.calls.contains("km:gateway:rl:tool:get_order_by_id"), store.calls.toString());
    }

    @Test
    void 匿名调用共用一个桶() {
        limiter.check("get_order_by_id", TaskIdentity.ANONYMOUS);

        assertTrue(store.calls.contains("km:gateway:rl:task:anonymous"), store.calls.toString());
    }

    @Test
    void 特殊字符的任务ID会被清洗_但不同ID不会撞桶() {
        limiter.check("t", new TaskIdentity("a/b c", null, null, null));
        limiter.check("t", new TaskIdentity("a:b-c", null, null, null));

        List<String> taskKeys = store.calls.stream()
                .filter(k -> k.contains(":task:"))
                .toList();
        // 两个原始 ID 不同, 清洗后哪怕"看起来像", 也必须落在两个桶里 ——
        // 否则互不相干的任务会共享配额, 一个任务能把另一个限死。
        assertNotEquals(taskKeys.get(0), taskKeys.get(1), taskKeys.toString());
        assertFalse(taskKeys.get(0).contains("/") || taskKeys.get(0).contains(" "),
                "清洗后的 key 不该带斜杠和空格: " + taskKeys.get(0));
    }

    /**
     * 按 key 决定放行/拒绝的假存储。
     *
     * <p>{@link #failure} 非空时, 每次调用都抛它 —— 用来模拟 Redis 挂了。
     */
    private static final class FakeStore implements RateLimitStore {

        private final List<String> calls = new ArrayList<>();
        private final List<String> denyKeys = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public boolean tryConsume(String key, int capacity, double refillPerSecond) {
            calls.add(key);
            if (failure != null) {
                throw failure;
            }
            return !denyKeys.contains(key);
        }
    }
}
