package com.km.task.audit;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.km.task.domain.AgentTaskAttempt;
import com.km.task.mapper.AgentTaskAttemptMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AttemptLookup} 的默认实现: 查 {@code agent_task_attempt}, 带一层
 * <b>很小的有界缓存</b>。
 *
 * <h3>为什么必须缓存</h3>
 *
 * <p>一次 ReAct 循环里, LLM 调用和工具调用加起来通常是几条到几十条审计事件,
 * 它们的 {@code traceId} <b>全都一样</b> —— 同一次执行。不缓存的话, 每条事件
 * 都要往 MySQL 打一次同样的查询, 而审计是旁路, 它没有资格在原主链旁边再压
 * 一倍 QPS。缓存命中率在这里近乎 100%, 代价只有几 KB。
 *
 * <h3>为什么是有界 + 会过期, 而不是直接 {@code @Cacheable}</h3>
 *
 * <p>因为这是一个<b>进程内、可丢的</b>优化, 不是业务缓存:
 *
 * <ul>
 *   <li><b>有界</b> (最多 {@value #MAX_ENTRIES} 条): 长期运行的服务里,
 *       traceId 是无限的。一个只增不减的 Map 就是内存泄漏, 而且症状
 *       (服务跑几天后 OOM) 和原因隔得非常远。满了先清理过期项, 再不够就
 *       淘汰任意一条 —— <b>淘汰谁都不影响正确性</b>, 最坏只是多查一次库</li>
 *   <li><b>会过期</b>: 执行是会闭链的。若永久缓存 {@code endedAt = null},
 *       一条在闭链之后才到的迟到事件会被当成"正常"放行, 迟到指标从此失真。
 *       取 60 秒是在"少查库"和"及时看见闭链"之间的折中</li>
 *   <li><b>查无也缓存, 但只缓存 5 秒</b>: 未知 trace 多半意味着调用方传错了 /
 *       还没建 attempt 就来了。缓存短一点, 让"先到的审计, 后建的行"这种情况
 *       自己恢复, 不必等一分钟</li>
 *   <li><b>异常不缓存</b>: 数据库抖一下不能变成"这个 trace 不存在"的证据。
 *       异常原样上抛, 由 {@link AuditIngestService} 走降级</li>
 * </ul>
 *
 * <h3>缓存值里带着 {@code endedAt} 的副作用</h3>
 *
 * <p>正缓存命中时拿到的是 60 秒前的快照。如果这次执行正好在这 60 秒内闭链,
 * 迟到判断会晚一步。这是可接受的: 迟到事件本来就不会被丢, 只是计数和日志
 * 里少标一条 —— 用一次数据库往返换这个精度, 不划算。
 */
@Component
public class CachedAttemptLookup implements AttemptLookup {

    /** 查到之后缓存多久。见类注释里"会过期"那一段。 */
    static final Duration POSITIVE_TTL = Duration.ofSeconds(60);

    /** 查无缓存多久。短, 是为了让"审计先于建行到达"能自动恢复。 */
    static final Duration NEGATIVE_TTL = Duration.ofSeconds(5);

    /** 上限。一条 entry 大约一百来字节, 一万条也就是 1MB 量级。 */
    static final int MAX_ENTRIES = 10_000;

    private final AgentTaskAttemptMapper attemptMapper;
    private final Duration positiveTtl;
    private final Duration negativeTtl;

    /**
     * 值允许为"没有" ({@link CacheEntry#attempt()} 为 null), 用
     * {@code ConcurrentHashMap} 而不是 Caffeine: 依赖已经够多了, 而这里只
     * 需要"按 key 存、按时间过期、有上限"三件事, 四十行就写完了。
     */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    public CachedAttemptLookup(AgentTaskAttemptMapper attemptMapper) {
        this(attemptMapper, POSITIVE_TTL, NEGATIVE_TTL);
    }

    /**
     * 测试用: 把 TTL 缩到毫秒级, 否则验过期要真等一分钟。
     *
     * <p>两个构造器里只有这一个标了 {@link Autowired} —— Spring 在有多个
     * 构造器时必须知道挑哪个, 而"测试专用"这个意图不该靠参数个数猜。
     */
    CachedAttemptLookup(AgentTaskAttemptMapper attemptMapper,
                        Duration positiveTtl,
                        Duration negativeTtl) {
        this.attemptMapper = attemptMapper;
        this.positiveTtl = positiveTtl;
        this.negativeTtl = negativeTtl;
    }

    // 从缓存里查 traceId 对应的 AttemptRef, 如果没有则从校验表里找出并且缓存起来
    // 并未对异常做处理, 上抛到熔断保护器
    @Override
    public AttemptRef findByTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }

        long now = System.nanoTime();
        CacheEntry cached = cache.get(traceId);
        // 1.缓存里查找到了值并且没过期
        if (cached != null && !cached.isExpired(now)) {
            // 获取到CacheEntry里的组件AttemptRef
            return cached.attempt();
        }

        // 2.缓存里没找到, 就从校验表里找出 traceId 对应的 AgentTaskAttempt, 然后改装为 AttemptRef
        // 这是唯一一次查询DB的操作
        AttemptRef fresh = load(traceId);

        // 根据是否从校验表里查到 fresh, 决定缓存多久(查到就缓存60秒, 查无就缓存5秒)
        // 校验表里面都没查到, 就缓存5秒. 这表示的事件是 create()还没来得及登记,审计事件就发过来了
        Duration ttl = fresh == null ? negativeTtl : positiveTtl;
        // 将真实值缓存起来
        cache.put(traceId, new CacheEntry(fresh, now + ttl.toNanos()));
        // 清除缓存里的过期记录
        evictIfNeeded(now);
        return fresh;
    }

    // 从校验表里找出 traceId 对应的 AgentTaskAttempt, 然后改装为 AttemptRef
    private AttemptRef load(String traceId) {
        AgentTaskAttempt attempt = attemptMapper.selectOne(
                Wrappers.<AgentTaskAttempt>lambdaQuery()
                        .eq(AgentTaskAttempt::getTraceId, traceId));
        if (attempt == null) {
            return null;
        }
        return new AttemptRef(attempt.getAgentTaskId(), attempt.getTraceId(), attempt.getEndedAt());
    }

    /**
     * 超过上限时先清过期, 还不够就淘汰任意一条。
     *
     * <p>不是"每次 put 都全表扫描": 只有到上限才做, 而到上限本身就意味着
     * 缓存已经被塞满了 —— 那时多花一次 O(n) 是合理的。
     */
    private void evictIfNeeded(long now) {
        // 还没超过缓存空间上限, 就不淘汰任何条目
        if (cache.size() <= MAX_ENTRIES) {
            return;
        }
        // 内部用 Iterator 遍历缓存, 逐个检查过期时间, 过期了就删除过期的条目
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        if (cache.size() > MAX_ENTRIES) {
            // 淘汰任意一条。谁的命中率下降都无所谓 —— 未命中的代价只是一次
            // 按唯一索引的查询, 不影响任何判断结果。
            cache.keySet().stream().findFirst().ifPresent(cache::remove);
        }
    }

    /**
     * @param attempt     为 null 表示"明确查无"
     * @param expiresAtNanos 用 {@link System#nanoTime()} 的刻度 —— 它不受系统
     *                       时钟回拨影响, 而 {@code currentTimeMillis} 会:
     *                       回拨一次, 缓存里那些"未来"的条目就要多活很久
     */
    private record CacheEntry(AttemptRef attempt, long expiresAtNanos) {

        boolean isExpired(long now) {
            // 用减法而不是 now > expiresAt: nanoTime 的绝对值可能为负,
            // 但差值在 292 年尺度内是可靠的。
            return now - expiresAtNanos > 0;
        }
    }
}
