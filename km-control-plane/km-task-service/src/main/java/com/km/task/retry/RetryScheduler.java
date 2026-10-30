package com.km.task.retry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.km.task.domain.AgentTask;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.error.MaxRetryExceededException;
import com.km.task.error.RetryNotAllowedException;
import com.km.task.mapper.AgentTaskMapper;
import com.km.task.service.TaskService;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 自动重试的扫描器: 定期找出"该重试且退避已到期"的 FAILED 任务, 开新链并派发。
 *
 * <h3>为什么是"扫描 + 退避"而不是失败时立刻排一个定时任务</h3>
 *
 * <p>失败那一刻往内存里排定时器, 进程一重启就全丢了, 而且多实例部署时
 * 每个实例都会排一份。状态本身已经在 {@code agent_task} 里 (status=FAILED,
 * finished_at, retry_count), 由数据库当唯一事实来源、定时器只负责"看看
 * 谁到期了", 重启/多实例都不会丢重试。代价是最多晚一个扫描周期才发起,
 * 对兜底重试完全可以接受。
 *
 * <h3>★ 退避从 failure 的时间算, 不是从上次重试算 ★</h3>
 *
 * <p>{@code finished_at} 是"这一轮失败"落库的时间; {@code retry_count} 是
 * "已经重试过几次"。两者合起来就能算出 <b>这一轮失败后该等多久</b>:
 * {@code min(base * 2^retry_count, max)}。不需要额外存 {@code next_retry_at}
 * —— 多一列就多一个可能写错、可能和事实不一致的地方。
 *
 * <h3>与手动重试的边界</h3>
 *
 * <p>扫描器只<b>发现</b>候选, 所有校验 (状态、次数、并发) 仍在
 * {@link TaskService#retry} 里做一遍。候选查询只是"粗筛", 不是权威 ——
 * 从查出到调用之间状态可能已经变了, 拿 409 跳过即可, 不用在这里再实现
 * 一套规则 (两套规则迟早会不一致)。
 */
@Component
@ConditionalOnProperty(prefix = "km.retry.scheduler", name = "enabled", havingValue = "true")
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    /**
     * 自动重试写进审计的 reason。
     *
     * <p>不带"第几次" —— 次数在事件的 attemptNo 里, 一句话只表达一个事实。
     */
    static final String AUTO_RETRY_REASON = "自动重试";

    private final AgentTaskMapper taskMapper;
    private final TaskService taskService;
    private final RetryDispatcher dispatcher;
    private final RetryProperties.Scheduler config;

    public RetryScheduler(AgentTaskMapper taskMapper, TaskService taskService,
                          RetryDispatcher dispatcher, RetryProperties properties) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.dispatcher = dispatcher;
        this.config = properties.getScheduler();
    }

    /**
     * 一轮扫描。{@code fixedDelay} 而不是 {@code fixedRate}: 上一轮没跑完
     * 就不要再叠一轮进来 (默认调度线程只有一个, 但语义上也别积压)。
     */
    @Scheduled(fixedDelayString = "${km.retry.scheduler.interval-ms:10000}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        // 退避时间范围: [base, max]
        int base = Math.max(1, config.getBaseBackoffSeconds());
        int maxBackoff = Math.max(base, config.getMaxBackoffSeconds());

        List<AgentTask> candidates;
        try {
            candidates = taskMapper.selectList(candidateQuery(now.minusSeconds(base)));
        } catch (RuntimeException e) {
            // 查库失败 (数据库抖一下) 不该让调度器停摆: 记录后下一轮再来。
            log.error("自动重试扫描失败, 下一轮重试", e);
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }

        int retried = 0;
        for (AgentTask task : candidates) {
            int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
            // 粗筛只按 base 过滤, 这里再按这一任务自己的退避判断。
            // (查询里没有按 retry_count 逐行算退避的写法 —— MySQL 里能做,
            //  但把策略写进 SQL 后, 改退避就得改 SQL, 不值。)
            long backoff = backoffSeconds(retryCount, base, maxBackoff);
            LocalDateTime notBefore = task.getFinishedAt().plusSeconds(backoff);
            if (notBefore.isAfter(now)) {
                continue;
            }
            try {
                AgentTask updated = taskService.retry(
                        task.getAgentTaskId(), AUTO_RETRY_REASON, TaskEventSource.SYSTEM_RETRY);
                dispatcher.dispatch(updated);
                retried++;
                log.info("自动重试 task={} retryCount={}/{} backoff={}s trace={}",
                        task.getAgentTaskId(), retryCount + 1,
                        task.getMaxRetry(), backoff, updated.getTraceId());
            } catch (RetryNotAllowedException | MaxRetryExceededException e) {
                // 从"查到"到"重试"之间被别人抢先了 (人工重试 / 状态变了 /
                // 次数刚好用尽)。这是正常的竞争结果, 不是故障。
                log.debug("跳过自动重试 task={}: {}", task.getAgentTaskId(), e.getMessage());
            } catch (RuntimeException e) {
                // 单个任务出错 (比如库连接闪断) 不能中断整轮扫描, 否则
                // 排在前面的任务会一直被它挡住。
                log.error("自动重试失败 task={}", task.getAgentTaskId(), e);
            }
        }
        if (retried > 0) {
            log.info("自动重试本轮发起 {} 个任务 (候选 {})", retried, candidates.size());
        }
    }

    private LambdaQueryWrapper<AgentTask> candidateQuery(LocalDateTime cutoff) {
        LambdaQueryWrapper<AgentTask> query = Wrappers.<AgentTask>lambdaQuery()
                .eq(AgentTask::getStatus, TaskStatus.FAILED.name())
                // finished_at 为空说明这一轮还没结束 —— 不是"失败待重试"。
                .isNotNull(AgentTask::getFinishedAt)
                .le(AgentTask::getFinishedAt, cutoff)
                // 次数上限是"业务规则", 放在粗筛里能少捞一堆注释废数据。
                .apply("retry_count < max_retry")
                // 没有 intent 就重建不出 /chat 的请求体, 重试也无从谈起。
                // 这类任务需要人工补上下文, 不是自动重试能解决的。
                .apply("intent IS NOT NULL AND TRIM(intent) <> ''");
        if (StringUtils.hasText(config.getTenant())) {
            query.eq(AgentTask::getTenantId, config.getTenant().trim());
        }
        return query.orderByAsc(AgentTask::getFinishedAt)
                .last("LIMIT " + Math.max(1, config.getBatchSize()));
    }

    /**
     * 第 {@code retryCount} 次失败后要等多久: {@code min(base * 2^retryCount, max)}。
     *
     * <p>retryCount=0 → base, 1 → 2*base, 2 → 4*base …… 封顶 max。
     * 用 long 计算并提前判溢出: 次数上限是用户可配的, 不能假设它很小。
     * max 小于 base 时按 base 算 —— 配置写反了也不该让重试间隔变成 0。
     */
    static long backoffSeconds(int retryCount, int base, int max) {
        int cap = Math.max(base, max);
        if (retryCount <= 0) {
            return base;
        }
        if (retryCount >= 31) {
            return cap;
        }
        long value = (long) base << retryCount;
        return Math.min(value, cap);
    }
}
