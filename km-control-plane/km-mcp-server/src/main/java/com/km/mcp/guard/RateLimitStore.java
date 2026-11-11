package com.km.mcp.guard;

/**
 * 令牌桶的存储抽象。
 *
 * <h3>为什么要有这一层接口, 而不是直接在限流器里用 RedisTemplate</h3>
 *
 * <p>两个理由, 都和"信任"有关:
 *
 * <ol>
 *   <li><b>单测</b> —— 限流器的逻辑 (先查哪个维度、超限了怎么报错、Redis
 *       挂了怎么办) 是本项目自己的决策, 必须能脱离 Redis 快速测。用一个
 *       假实现就能把 Redis 从测试里拿掉, 不用起容器。</li>
 *   <li><b>存储是可替换的</b> —— 现在的答案是 Redis (多实例共享计数),
 *       但"限流状态放在哪"是部署形态决定的。单机部署完全可以用内存实现,
 *       那时不该为了换存储去改限流逻辑。</li>
 * </ol>
 *
 * <p>实现方<b>允许抛异常</b>: 存储故障怎么处理 (放行还是拒绝) 是限流器的
 * 决策, 不是存储的。见 {@link ToolRateLimiter} —— 选择 fail-open。
 */
public interface RateLimitStore {

    /**
     * 尝试从桶里取一个令牌。
     *
     * <p>必须是<b>原子</b>的: "读剩余令牌 -> 计算补充 -> 扣减 -> 写回"
     * 四步之间不能被别的请求插进来, 否则并发下放行量会超过配置值
     * (经典 check-then-act)。
     *
     * @param key             桶的键 (已经包含前缀和维度)
     * @param capacity        桶容量, 也就是允许的突发峰值
     * @param refillPerSecond 每秒补充的令牌数
     * @return true = 取到了, 放行; false = 桶空了, 拒绝
     * @throws RuntimeException 存储不可用 (连接失败 / 脚本报错)。由调用方决定
     *                          是 fail-open 还是 fail-closed。
     */
    boolean tryConsume(String key, int capacity, double refillPerSecond);
}
