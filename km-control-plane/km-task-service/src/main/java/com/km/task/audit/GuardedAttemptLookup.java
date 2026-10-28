package com.km.task.audit;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 给身份核对加一道<b>硬截止时间</b>和一个小熔断器。
 *
 * <h3>★ 为什么需要它: 实测踩到的 30 秒 ★</h3>
 *
 * <p>最初的写法是直接在请求线程里查库。数据库正常时这没问题, 但把 MySQL
 * 停掉之后实测: {@code POST /api/audit/events} <b>整整卡了 30.02 秒</b>才返回
 * (Hikari 的 {@code connectionTimeout} 默认值), 然后才走到"降级放行"。
 *
 * <p>这 30 秒把降级的意义全吃掉了:
 *
 * <ul>
 *   <li>Python 侧的审计上报超时是 <b>2 秒</b> —— 客户端早就放弃并丢掉整批了,
 *       服务端 30 秒后再"收下"没有任何意义</li>
 *   <li>每一个事件都占着一个 Tomcat 工作线程, 数据库一挂, 审计入口会反过来
 *       拖垮整个任务服务 —— 旁路伤害主路, 正是要避免的事</li>
 *   <li>STRICT 模式下更糟: 本该"查不动就放行", 实际变成"先卡 30 秒再说"</li>
 * </ul>
 *
 * <h3>做法</h3>
 *
 * <p>把查库扔进一个<b>小的、有界的</b>线程池, 当前线程只等
 * {@value #TIMEOUT_MILLIS} 毫秒:
 *
 * <ul>
 *   <li><b>超时</b> → 取消任务, 抛 {@link AttemptLookupUnavailableException},
 *       上游按未核实放行。JDBC 的 30 秒仍会在后台线程里走完, 但没人等它</li>
 *   <li><b>失败后熔断 5 秒</b> → 这 5 秒内直接抛, 不再提交任务。否则一批
 *       50 条事件就是 50 × 500ms 的排队等待。数据库恢复后自动重新放行
 *       (没有"半开"状态: 冷却结束后的第一次查询就是探测)</li>
 *   <li><b>线程池有界</b> (2 线程 / 32 队列) → 满了直接拒绝并熔断。宁可
 *       少核对几条, 也不能让积压的查询把内存和线程吃光</li>
 * </ul>
 *
 * <p>500ms 这个数不是拍的: 它是"Python 2 秒超时"的四分之一, 留足了回包余量;
 * 同时正常查库是毫秒级 (还有缓存兜着), 500ms 只会在真的出问题时才用满。
 *
 * <h3>为什么是装饰器而不是塞进 {@link CachedAttemptLookup}</h3>
 *
 * <p>缓存负责"少查", 这里负责"查不到时别拖着" —— 两件事的生命周期和失败语义
 * 都不一样。混在一个类里, 测试要同时 mock 时间和线程, 改一个会碰到另一个。
 */
@Component
@Primary
public class GuardedAttemptLookup implements AttemptLookup {

    /** 单次核对最多等多久。见类注释里"为什么是 500ms"。 */
    static final long TIMEOUT_MILLIS = 500;

    /** 失败之后多久内不再尝试。够挡住一次故障的绝大多数事件, 又不至于恢复得太慢。 */
    static final long COOLDOWN_MILLIS = 5_000;

    /**
     * 2 个线程足够: 命中缓存时根本不进这里, 而真的查库是毫秒级。
     * 数据库挂住时最多占住 2 个线程, 不会扩散。
     */
    private static final int THREADS = 2;

    /** 有界队列, 满了就拒绝 —— 积压比失败更危险。 */
    private static final int QUEUE_CAPACITY = 32;

    private final CachedAttemptLookup delegate;
    private final long timeoutMillis;
    private final long cooldownNanos;
    private final ThreadPoolExecutor executor;

    /** 熔断截止时刻 (nanoTime 刻度); 0 = 未熔断。 */
    private final AtomicLong openUntilNanos = new AtomicLong();

    @Autowired
    public GuardedAttemptLookup(CachedAttemptLookup delegate) {
        this(delegate, TIMEOUT_MILLIS, COOLDOWN_MILLIS);
    }

    /** 测试用: 把超时/冷却缩到毫秒级。 */
    GuardedAttemptLookup(CachedAttemptLookup delegate, long timeoutMillis, long cooldownMillis) {
        this.delegate = delegate;
        this.timeoutMillis = timeoutMillis;
        this.cooldownNanos = Duration.ofMillis(cooldownMillis).toNanos();
        // 2 个线程, 32 队列, 60 秒超时, 拒绝并熔断
        this.executor = new ThreadPoolExecutor(
                THREADS, THREADS,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    // daemon: 数据库挂住时, 这些线程可能在 JDBC 里躺 30 秒;
                    // 不该因为它们让 JVM 关不掉。
                    Thread thread = new Thread(runnable, "audit-attempt-lookup");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
    // 接口的唯一实现方法, 也是对CachedAttemptLookup的装饰
    // 我必须在500ms之内完成本次身份核对, 不然就熔断5秒
    @Override
    public AttemptRef findByTrace(String traceId) {
        long now = System.nanoTime();
        if (now - openUntilNanos.get() < 0) {
            // 熔断中: 直接告诉上游"核对不可用", 由它按未核实放行。
            throw new AttemptLookupUnavailableException(
                    "身份核对熔断中 (最近一次查询失败, 冷却 " + cooldownNanos / 1_000_000 + "ms)");
        }

        Future<AttemptRef> future;
        // 将身份核对的工作交给线程池执行, 并且对执行过程future进行监控
        try {
            future = executor.submit(() -> delegate.findByTrace(traceId));
        } catch (RejectedExecutionException ex) {
            // 线程池已满, 直接拒绝并熔断
            open();
            throw new AttemptLookupUnavailableException("身份核对线程池已满", ex);
        }

        try {
            // 当前执行任务的线程最多只执行 500ms, 这一行代码可能抛出超时异常
            AttemptRef result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            // 只要成功就熔断置零
            openUntilNanos.set(0);
            return result;
        } catch (TimeoutException ex) {
            // 1.查找超时 (从缓存或者数据库里找) 那就中断


            // 我的任务执行正常, 只是超时了, 需要手动终止任务, 防止线程还在后台跑任务被占用
            future.cancel(true);
            // 打开熔断开关
            open();
            //返回超时的异常信息
            throw new AttemptLookupUnavailableException(
                    "身份核对超时 (" + timeoutMillis + "ms), 数据库可能不可用", ex);
        } catch (ExecutionException ex) {
            // 2.查数据库出了问题(Mysql连不上, mysql查询超时, mysql表不存在/字段填错了 ... )


            // 打开熔断开关
            // 没有future.cancel()因为抛出的异常已经打断了查询过程, 不用手动终止任务
            open();
            // 返回失败的异常信息
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new AttemptLookupUnavailableException(
                    "身份核对失败: " + cause.getClass().getSimpleName(), cause);
        } catch (InterruptedException ex) {
            // 3.查询被中断, 就中断任务


            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AttemptLookupUnavailableException("身份核对被中断", ex);
        }
    }
    // 打开熔断 冷却 cooldownNanos 时间
    private void open() {
        // 用新的 now: 超时分支进来时已经过去了 timeoutMillis, 用旧值会少熔断一截。
        openUntilNanos.set(System.nanoTime() + cooldownNanos);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
