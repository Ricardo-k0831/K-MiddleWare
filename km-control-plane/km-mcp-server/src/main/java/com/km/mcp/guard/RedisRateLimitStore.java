package com.km.mcp.guard;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 令牌桶的 Redis 实现。整段逻辑是一个 Lua 脚本。
 *
 * <h3>★ 为什么必须是 Lua, 不能在 Java 里读-改-写 ★</h3>
 *
 * <p>{@code GET -> 算 -> SET} 三步之间没有任何原子性。并发 10 个请求时,
 * 它们可能读到同一个"剩 1 个令牌"的值, 然后全部扣减成功 —— 实际放行了
 * 10 个。限流器最讽刺的失败方式就是<b>在并发下不生效</b>, 而单测和手工
 * 点击都发现不了, 只有在压力上来时后端被打挂才暴露。
 *
 * <p>Redis 执行 Lua 脚本时是单线程原子的, 整个"读 -> 补 -> 扣 -> 写"
 * 在一个原子操作里完成, 这也是用 Redis (而不是本地缓存) 做分布式限流的
 * 核心原因。
 *
 * <h3>为什么现在的时间从 Redis 取 (TIME), 不用应用服务器的时钟</h3>
 *
 * <p>多实例部署时, 各机器的时钟可能差几百毫秒甚至更多。用本地时钟算补充量,
 * 时钟快的实例会持续"多补"令牌, 整体放行量悄悄超过配置值。TIME 保证所有
 * 实例用同一个时间基准, 而且它和脚本本身在同一个原子操作里。
 *
 * <h3>TTL 的作用</h3>
 *
 * <p>桶空了之后如果键一直在, 键的数量会随"任务数 × 工具数"无限增长。
 * 一个桶从全空补满最多需要 {@code capacity / refill} 秒, 所以 TTL 取
 * 这个时间再加 1 秒 —— 期间它不可能再被需要。让 Redis 自己回收,
 * 不需要任何清理任务。
 */
@Component
public class RedisRateLimitStore implements RateLimitStore {

    /**
     * KEYS[1] = 桶键
     * ARGV[1] = 容量
     * ARGV[2] = 每秒补充令牌数
     * ARGV[3] = 键的 TTL (毫秒)
     *
     * <p>字段用 hash 而不是两个独立 key: 两个 key 无法在同一个原子操作里
     * 一起读写, 又会退化成 check-then-act。
     *
     * <p>用 {@code HSET} 而不是已废弃的 {@code HMSET}: Redis 4.0+ 支持
     * 一次写多个字段。本项目 compose 里的镜像是 7.x。
     */
    private static final RedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local refill = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])

            local nowParts = redis.call('TIME')
            local now = tonumber(nowParts[1]) * 1000 + math.floor(tonumber(nowParts[2]) / 1000)

            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])

            if tokens == nil then
              -- 第一次见到这个键: 满桶开始
              tokens = capacity
              ts = now
            end

            local elapsed = now - ts
            if elapsed < 0 then
              -- 时钟回拨 (极少见但会让 tokens 变成负数并长期锁死)
              elapsed = 0
            end
            tokens = math.min(capacity, tokens + (elapsed / 1000.0) * refill)

            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end

            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], ttl)
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryConsume(String key, int capacity, double refillPerSecond) {
        // 配置写反时 (补充速度 <= 0) 桶永远补不满, 会把调用方永久锁死。
        // 这是配置错误, 不是限流意图 —— 用最小正的速率兜底, 让故障是
        // "限得比预期狠"而不是"服务不可用"。
        double refill = refillPerSecond > 0 ? refillPerSecond : 0.1;
        int cap = Math.max(1, capacity);
        long ttlMs = (long) Math.ceil(cap / refill * 1000.0) + 1000L;

        Long allowed = redis.execute(TOKEN_BUCKET, List.of(key),
                String.valueOf(cap), String.valueOf(refill), String.valueOf(ttlMs));
        // 脚本永远返回 0/1; 返回 null 说明连接层出了问题, 按异常处理,
        // 让限流器走 fail-open —— 而不是把一个模糊的 null 当成"拒绝"。
        if (allowed == null) {
            throw new IllegalStateException("限流 Lua 脚本返回空值");
        }
        return allowed == 1L;
    }
}
