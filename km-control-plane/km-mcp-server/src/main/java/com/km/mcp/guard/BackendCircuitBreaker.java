package com.km.mcp.guard;

import com.km.mcp.audit.CallStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 包住"调存量服务"这一步的熔断器。
 *
 * <h3>它要解决的故障模式</h3>
 *
 * <p>后端挂了的时候, 每一次调用都要等满连接超时 (默认几秒) 才失败。假设网关
 * 只有 20 个请求线程、一次调用卡 3 秒、每秒来 10 个请求 —— 半分钟内线程池
 * 就被"注定失败"的调用占满。此时<b>其它健康的工具也会一起不可用</b>,
 * 因为请求根本进不来。这就是级联故障: 一个依赖的故障扩散成整个网关的故障。
 *
 * <p>熔断器在失败率达到阈值时<b>直接拒绝后续调用, 不再发起网络请求</b>
 * (0ms 失败), 让网关的线程去服务那些还有救的请求。等一段时间后放几个
 * 试探请求过去, 成功就恢复, 失败就继续拒绝 —— 恢复是自动的, 不需要人工
 * 重启任何东西。
 *
 * <h3>★ 4xx 不算失败 ★</h3>
 *
 * <p>和 {@link CallStatus} 是同一条规则, 这里直接复用
 * {@link CallStatus#isBackendFailure}: 后端回 404 "订单不存在"说明它<b>活着</b>
 * 而且工作正常。如果把这算成失败, 一批"用户输错订单号"的流量就能把熔断器
 * 打开, 然后真正正常的查询被拒绝 —— 熔断器被正常业务触发, 这是最糟糕的
 * 误伤。429 例外: 那是后端在喊"我扛不住了", 必须计数。
 *
 * <h3>为什么自己 new 而不是引入 starter</h3>
 *
 * <p>见 {@code km-mcp-server/pom.xml} 里 resilience4j 那段注释。核心是:
 * Spring Boot 4 上 starter 的自动配置若没生效, 表现是"熔断看着配置了,
 * 实际一次都没拦" —— 静默失效比报错更危险, 所以这里选择完全显式装配。
 */
@Component
public class BackendCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(BackendCircuitBreaker.class);

    /** 熔断器名。只有一条后端调用链, 用一个名字就够了。 */
    static final String NAME = "order-service";

    private final boolean enabled;
    private final CircuitBreaker breaker;

    public BackendCircuitBreaker(GatewayGuardProperties properties) {
        // 从配置中获取熔断器配置
        GatewayGuardProperties.CircuitBreaker config = properties.getCircuitBreaker();
        // 检查是否启用熔断器
        this.enabled = config.isEnabled();
        if (!enabled) {
            log.warn("★ 网关熔断已关闭 (km.gateway.circuit-breaker.enabled=false) —— "
                    + "后端持续故障时会拖慢/拖垮网关自身, 只应在本地调试时这样配");
            this.breaker = null;
            return;
        }

        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                // 基于时间的窗口: 流量低时也能按"最近 N 秒"算失败率,
                // 不会因为凑不够调用数而永远不熔断。
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(Math.max(1, config.getSlidingWindowSizeSeconds()))
                .minimumNumberOfCalls(Math.max(1, config.getMinimumNumberOfCalls()))
                .failureRateThreshold(config.getFailureRateThreshold())
                .waitDurationInOpenState(
                        Duration.ofSeconds(Math.max(1, config.getWaitDurationInOpenStateSeconds())))
                .permittedNumberOfCallsInHalfOpenState(
                        Math.max(1, config.getPermittedNumberOfCallsInHalfOpenState()))
                // 怎么才算计入熔断的失败阈值?
                // 4xx 不算失败 。除开429限流异常
                // 谓词返回 true 才会被计为失败; 429 ERROR TIMEOUT 都会计入失败
                .recordException(CallStatus::isBackendFailure)
                .build();

        this.breaker = CircuitBreakerRegistry.of(breakerConfig).circuitBreaker(NAME);

        // 状态变化只在这里记一次日志: 熔断打开/恢复是运维最需要知道的事件,
        // 但如果让每次调用都打日志, 日志会被"请求被拒"淹没, 反而看不见
        // 它是什么时候打开的。
        this.breaker.getEventPublisher().onStateTransition(event -> {
            switch (event.getStateTransition()) {
                case CLOSED_TO_OPEN -> log.error(
                        "[熔断] 后端服务失败率过高, 熔断器打开 —— 后续调用将快速失败, "
                                + "{}s 后自动试探恢复", config.getWaitDurationInOpenStateSeconds());
                case OPEN_TO_HALF_OPEN -> log.warn(
                        "[熔断] 开始试探性放行 (半开), 最多 {} 次",
                        config.getPermittedNumberOfCallsInHalfOpenState());
                case HALF_OPEN_TO_CLOSED -> log.info("[熔断] 试探成功, 后端已恢复, 熔断器关闭");
                case HALF_OPEN_TO_OPEN -> log.error(
                        "[熔断] 试探仍然失败, 熔断器重新打开 —— 后端还没恢复");
                default -> {
                    // CLOSED_TO_HALF_OPEN / DISABLED 等, 无需单独提示
                }
            }
        });
    }

    /**
     * 执行一次后端调用。
     *
     * @throws BackendUnavailableException 熔断器打开时 —— 注意此时 {@code action}
     *                                     <b>根本不会执行</b>, 后端不会被访问
     */
    public <T> T call(Supplier<T> action) {
        if (!enabled) {
            return action.get();
        }
        try {
            return breaker.executeSupplier(action);
        } catch (CallNotPermittedException ex) {
            // 换成自己的异常类型: 工具层只需要认识 DegradedCallException 家族,
            // 不必知道底层熔断库是谁。
            throw new BackendUnavailableException(
                    "订单服务当前处于熔断状态 (连续失败), 本次调用未发出", ex);
        }
    }

    /** 当前状态, 供日志/排查用。关闭时返回 DISABLED。 */
    public CircuitBreaker.State state() {
        return enabled ? breaker.getState() : CircuitBreaker.State.DISABLED;
    }
}
