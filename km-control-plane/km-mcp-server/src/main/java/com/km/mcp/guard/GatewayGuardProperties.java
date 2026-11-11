package com.km.mcp.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关的两道保护: 限流和熔断。前缀 {@code km.gateway}。
 *
 * <h3>为什么这两件事放在网关, 而不是业务服务或任务服务</h3>
 *
 * <p>网关是所有工具调用的<b>唯一入口</b>。放在这里, 存量服务一行代码不用改,
 * 换任何一个后端服务也自动获得同样的保护; 放在任务服务里则管不到别的调用方
 * (比如将来接入的其它 Agent), 放在业务服务里又要求每个团队各写一遍。
 *
 * <h3>★ 两者保护的是不同的东西, 不能互相替代 ★</h3>
 *
 * <ul>
 *   <li><b>限流</b>保护<b>后端容量</b>: 流量在到达后端之前就被挡住, 后端永远
 *       只看到被允许的那部分。它防的是"太多请求", 不管这些请求成功与否。</li>
 *   <li><b>熔断</b>保护<b>调用方</b>: 后端已经坏了的时候, 让后续请求快速失败,
 *       不再把线程和超时预算花在注定失败的调用上 —— 否则一个挂掉的后端会
 *       把网关自己的线程池拖死, 连健康的工具一起不可用。</li>
 * </ul>
 *
 * <p>一个简单的事实可以说明区别: 限流开着的时候, 被挡掉的请求<b>不会</b>让
 * 熔断器计数; 熔断打开的时候, 请求<b>不会</b>消耗限流令牌 (根本不发出去)。
 * 顺序是: 先限流 (便宜, 一次 Redis 往返), 再熔断 (连 Redis 都不需要)。
 */
@Component
@ConfigurationProperties(prefix = "km.gateway")
public class GatewayGuardProperties {

    private final RateLimit rateLimit = new RateLimit();

    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * 限流配置。
     *
     * <p>用<b>令牌桶</b>而不是固定窗口计数: 固定窗口在窗口边界会放行两倍流量
     * (上一窗口最后 1ms 和下一窗口最初 1ms 各打满一次), 而令牌桶在同样配置下
     * 的放行速率是平滑的, 只需要额外记一个"上次补充时间"。
     *
     * <p>两个维度都要:
     * <ul>
     *   <li><b>任务维度</b> —— 防一个失控的 Agent 任务 (死循环重试、模型抽风)
     *       把共享的工具配额吃光, 拖垮其它任务。</li>
     *   <li><b>工具维度</b> —— 防所有任务加起来超过后端承受能力。一个任务
     *       再克制, 一百个任务同时打过来后端一样会挂。</li>
     * </ul>
     */
    public static class RateLimit {

        /**
         * 总开关。默认打开: 限流失效的代价 (后端被打挂) 远大于多一次 Redis
         * 往返的代价。Redis 不可用时是 <b>fail-open</b> (放行) —— 见
         * {@link ToolRateLimiter}: 限流器不该成为新的单点。
         */
        private boolean enabled = true;

        /** 单个工具每秒补充的令牌数 (稳态 QPS)。 */
        private double perToolRefillPerSecond = 5.0;

        /** 单个工具的桶容量 (允许多大突发)。 */
        private int perToolBurst = 10;

        /** 单个任务每秒补充的令牌数。 */
        private double perTaskRefillPerSecond = 2.0;

        /** 单个任务的桶容量。 */
        private int perTaskBurst = 5;

        /** Redis key 前缀。多环境共用一个 Redis 时靠它隔离。 */
        private String keyPrefix = "km:gateway:rl";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getPerToolRefillPerSecond() {
            return perToolRefillPerSecond;
        }

        public void setPerToolRefillPerSecond(double perToolRefillPerSecond) {
            this.perToolRefillPerSecond = perToolRefillPerSecond;
        }

        public int getPerToolBurst() {
            return perToolBurst;
        }

        public void setPerToolBurst(int perToolBurst) {
            this.perToolBurst = perToolBurst;
        }

        public double getPerTaskRefillPerSecond() {
            return perTaskRefillPerSecond;
        }

        public void setPerTaskRefillPerSecond(double perTaskRefillPerSecond) {
            this.perTaskRefillPerSecond = perTaskRefillPerSecond;
        }

        public int getPerTaskBurst() {
            return perTaskBurst;
        }

        public void setPerTaskBurst(int perTaskBurst) {
            this.perTaskBurst = perTaskBurst;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    /**
     * 熔断配置。
     *
     * <p>统计窗口用<b>基于时间</b>而不是基于次数: 基于次数的窗口在流量低时
     * 可能几小时都凑不够最少调用数, 熔断器形同虚设; 基于时间的窗口让
     * "最近 N 秒失败率"始终有定义。
     */
    public static class CircuitBreaker {

        /**
         * 总开关。默认打开。注意熔断打开时<b>不会</b>调用后端, 所以它也是
         * "后端已经挂了"时唯一能让调用快速失败、不拖垮网关的机制。
         */
        private boolean enabled = true;

        /** 失败率阈值 (百分比)。达到就开闸。 */
        private float failureRateThreshold = 50.0f;

        /** 统计窗口长度, 秒。 */
        private int slidingWindowSizeSeconds = 30;

        /** 窗口内至少这么多次调用才计算失败率 —— 否则 1 次失败就是 100%。 */
        private int minimumNumberOfCalls = 5;

        /** 打开后保持多久再放行试探请求, 秒。 */
        private int waitDurationInOpenStateSeconds = 10;

        /** 半开状态下允许同时放行几次试探调用。 */
        private int permittedNumberOfCallsInHalfOpenState = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public int getSlidingWindowSizeSeconds() {
            return slidingWindowSizeSeconds;
        }

        public void setSlidingWindowSizeSeconds(int slidingWindowSizeSeconds) {
            this.slidingWindowSizeSeconds = slidingWindowSizeSeconds;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public int getWaitDurationInOpenStateSeconds() {
            return waitDurationInOpenStateSeconds;
        }

        public void setWaitDurationInOpenStateSeconds(int waitDurationInOpenStateSeconds) {
            this.waitDurationInOpenStateSeconds = waitDurationInOpenStateSeconds;
        }

        public int getPermittedNumberOfCallsInHalfOpenState() {
            return permittedNumberOfCallsInHalfOpenState;
        }

        public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
        }
    }
}
