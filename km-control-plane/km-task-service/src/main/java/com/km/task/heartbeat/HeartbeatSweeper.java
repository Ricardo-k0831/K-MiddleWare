package com.km.task.heartbeat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.km.task.domain.AgentTask;
import com.km.task.domain.AgentTaskAttempt;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.error.ExecutionMismatchException;
import com.km.task.error.IllegalTaskTransitionException;
import com.km.task.mapper.AgentTaskAttemptMapper;
import com.km.task.mapper.AgentTaskMapper;
import com.km.task.service.TaskService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 卡住的执行兜底: 找出"确实没人在跑、任务却停在中途"的执行, 把它关成 FAILED。
 *
 * <p>典型场景: Java 派发成功, Python 接到请求后进程崩了 / 被 kill / 事件循环
 * 卡死。任务停在 PENDING (或 THINKING / TOOL_CALLING), attempt 的
 * {@code ended_at} 一直为 NULL。自动重试只扫 FAILED, 所以这条任务永远不会
 * 再被推进; 而且未闭链的 attempt 还会让将来的重试被判"数据不一致"而拒绝。
 *
 * <h3>★ 判据是"探活", 不是"心跳旧了" ★</h3>
 *
 * <p>扫描只负责<b>发现可疑</b>: 距上一次证据 (心跳或派发) 超过
 * {@code stale-after-seconds}。真正能不能关链, 要看
 * {@link HeartbeatProbe} 的结论:
 *
 * <table border="1">
 *   <tr><th>探活</th><th>含义</th><th>处理</th></tr>
 *   <tr><td>ALIVE</td><td>Python 正在跑这条 trace</td>
 *       <td>刷新 {@code last_heartbeat_at} (探活本身算一次心跳), 不动状态</td></tr>
 *   <tr><td>NOT_ALIVE</td><td>Python 明确说"我这儿没有它"</td>
 *       <td>关链: 状态 -> FAILED, source=SYSTEM_HEARTBEAT</td></tr>
 *   <tr><td>UNKNOWN</td><td>问不出来</td><td><b>什么都不做</b>, 下一轮再问</td></tr>
 * </table>
 *
 * <h3>为什么 ALIVE 要刷新心跳</h3>
 *
 * <p>两个作用。其一, 避免每一轮扫描都去探活同一个长任务 (探活是同步 HTTP,
 * 会占用唯一的调度线程)。其二, Python 可能因为网络问题无法主动推心跳,
 * 但 Java 能探到它 —— 这时"Java 探到了"就是新的存活证据, 应当把时钟往后推,
 * 否则它会一直停留在到期集合里。
 *
 * <h3>为什么关成 FAILED, 不是 CANCELLED</h3>
 *
 * <p>这次执行没有完成, 也<b>不知道</b>它做到哪一步 —— 语义上是"失败"。
 * 更重要的是: FAILED 是唯一能进入重试的入口。关成 CANCELLED 会让任务
 * 永久停在"人工取消", 而用户并没有取消过。关成 FAILED 之后,
 * {@code RetryScheduler} 会按自己的退避和次数上限决定要不要再试 ——
 * "怎么处理失败"仍然只有一套策略, 兜底扫描不另立一套。
 *
 * <h3>为什么没有"卡住的 PENDING 多久就判死"的阈值</h3>
 *
 * <p>时间本身不构成证据。任务可能合法地停在 PENDING 等调用方 (首次执行
 * 由外部发起), 也可能 Python 正在跑一个很长的 LLM 调用。所以扫描范围
 * 只包含<b>要么 Java 派发过、要么 Python 心跳过</b>的执行 (见
 * {@link TaskService#markDispatched}), 关链则完全取决于探活。
 */
@Component
@ConditionalOnProperty(prefix = "km.heartbeat", name = "enabled", havingValue = "true")
public class HeartbeatSweeper {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatSweeper.class);

    private final AgentTaskAttemptMapper attemptMapper;
    private final AgentTaskMapper taskMapper;
    private final TaskService taskService;
    private final HeartbeatProbe probe;
    private final HeartbeatProperties config;

    public HeartbeatSweeper(AgentTaskAttemptMapper attemptMapper,
                            AgentTaskMapper taskMapper,
                            TaskService taskService,
                            HeartbeatProbe probe,
                            HeartbeatProperties properties) {
        this.attemptMapper = attemptMapper;
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.probe = probe;
        this.config = properties;
    }

    /**
     * 一轮扫描。{@code fixedDelay}: 上一轮没跑完不叠下一轮 —— 探活是同步
     * HTTP, 叠起来只会放大 Python 那边的压力。
     */
    // 15s 进行一次心跳 sweep
    @Scheduled(fixedDelayString = "${km.heartbeat.interval-ms:15000}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        int stale = Math.max(1, config.getStaleAfterSeconds()); //最小一秒, 配置里是120s

        List<AgentTaskAttempt> candidates;
        try {
            candidates = attemptMapper.selectList(candidateQuery(now.minusSeconds(stale)));
        } catch (RuntimeException e) {
            // 数据库抖一下不该让调度器停摆。
            log.error("心跳兜底扫描失败, 下一轮重试", e);
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }

        for (AgentTaskAttempt attempt : candidates) {
            try {
                inspect(attempt, now);
            } catch (RuntimeException e) {
                // 单条出错不能中断整轮 —— 否则排在它后面的任务永远轮不到。
                log.error("心跳兜底处理异常 task={} trace={}",
                        attempt.getAgentTaskId(), attempt.getTraceId(), e);
            }
        }
    }

    /**
     * 粗筛: 开着的、有存活证据、且证据已经过期的执行。
     *
     * <p>★ {@code COALESCE(last_heartbeat_at, dispatched_at)} 就是"最近一次
     * 存活证据" ★ 有心跳时以心跳为准 (派发可能已经过去很久), 没心跳时退回到
     * 派发时刻 (Python 可能压根没起来)。两者都为空 = 没人能为这条执行作证,
     * 直接排除 —— 新建任务等调用方发起就是这种状态, 不该被扫进来。
     */
    private LambdaQueryWrapper<AgentTaskAttempt> candidateQuery(LocalDateTime cutoff) {
        return Wrappers.<AgentTaskAttempt>lambdaQuery()
                .isNull(AgentTaskAttempt::getEndedAt)
                .apply("COALESCE(last_heartbeat_at, dispatched_at) IS NOT NULL")
                .apply("COALESCE(last_heartbeat_at, dispatched_at) <= {0}", cutoff)
                .orderByAsc(AgentTaskAttempt::getStartedAt)
                .last("LIMIT " + Math.max(1, config.getBatchSize()));
    }

    private void inspect(AgentTaskAttempt attempt, LocalDateTime now) {
        String taskId = attempt.getAgentTaskId();
        String traceId = attempt.getTraceId();

        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("执行对应的任务不存在, 跳过 (数据不一致) task={} trace={}", taskId, traceId);
            return;
        }

        TaskStatus status;
        try {
            status = TaskStatus.valueOf(task.getStatus());
        } catch (IllegalArgumentException | NullPointerException e) {
            // 库里存了枚举里没有的值 —— 数据损坏, 不在这里修。
            log.error("任务状态无法解析, 跳过 task={} status={}", taskId, task.getStatus());
            return;
        }

        if (status.isTerminal()) {
            // 终态和未闭链本应被同一个事务一起写掉。这里出现只可能是数据不一致;
            // 再写一次终态只会把"链路没关"这个真正的问题伪装成一次正常流转。
            log.warn("执行未闭链但任务已是终态, 跳过不处理 task={} trace={} status={}",
                    taskId, traceId, status);
            return;
        }

        if (!tenantMatches(task)) {
            return;
        }
        // probe() 探活 traceId 对应的任务是否还在 Python 进程里。 只是查看存活状态, 不会有后续决定。
        HeartbeatProbe.Verdict verdict = probe.probe(traceId);
        switch (verdict) {
            case ALIVE -> refresh(attempt);
            case NOT_ALIVE -> closeStale(attempt, task, status, now);
            case UNKNOWN -> log.debug("探活未知, 本轮不动 task={} trace={}", taskId, traceId);
        }
    }

    /** 探活命中 = 新的存活证据。 */
    private void refresh(AgentTaskAttempt attempt) {
        String taskId = attempt.getAgentTaskId();
        String traceId = attempt.getTraceId();
        try {
            taskService.heartbeat(taskId, traceId);
            log.info("探活命中 alive=true, 已刷新心跳 task={} trace={}", taskId, traceId);
        } catch (ExecutionMismatchException e) {
            // 探活和 Python 的终态上报赛跑: 探到 alive 之后、刷新之前执行结束了。
            // 这不是故障 —— 那次执行已经由它自己的终态上报收尾。
            log.debug("探活命中 alive=true, 但执行刚刚结束 task={} trace={}: {}",
                    taskId, traceId, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("探活命中 alive=true, 但刷新心跳失败 task={} trace={}", taskId, traceId, e);
        }
    }

    /**
     * Python 明确说没有这条执行 —— 关链。
     *
     * <p>用 {@code reportStatus} 而不是直接 update 表: 关链必须
     * <b>同时</b>完成"状态变更 + 事件落表 + 闭链 + 审计"四件事, 而且要走
     * 状态机和 CAS。绕开它自己写 SQL, 就等于在这个系统里开了第二套状态机。
     */
    private void closeStale(AgentTaskAttempt attempt, AgentTask task,
                            TaskStatus from, LocalDateTime now) {
        String taskId = attempt.getAgentTaskId();
        String traceId = attempt.getTraceId();

        LocalDateTime lastEvidence = attempt.getLastHeartbeatAt() != null
                ? attempt.getLastHeartbeatAt()
                : attempt.getDispatchedAt();
        long staleFor = lastEvidence == null
                ? -1
                : Math.max(0, Duration.between(lastEvidence, now).getSeconds());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("phase", "HEARTBEAT");
        detail.put("previousStatus", from.name());
        detail.put("reason", "心跳超时后探活确认 Python 侧没有该执行在跑");
        // error 会被 reportStatus 写进 agent_task.error_msg —— 运维在任务表上
        // 一眼就能看到"不是任务失败, 是执行丢了"。
        detail.put("error", "执行中断: 心跳超时且 Python 侧无此执行 (trace=" + traceId + ")");
        if (lastEvidence != null) {
            detail.put("lastEvidenceAt", lastEvidence.toString());
            detail.put("staleForSeconds", staleFor);
        }
        if (attempt.getDispatchedAt() != null) {
            detail.put("dispatchedAt", attempt.getDispatchedAt().toString());
        }
        if (attempt.getLastHeartbeatAt() != null) {
            detail.put("lastHeartbeatAt", attempt.getLastHeartbeatAt().toString());
        }

        try {
            taskService.reportStatus(taskId, traceId, TaskStatus.FAILED,
                    TaskEventSource.SYSTEM_HEARTBEAT, detail);
            log.warn("心跳兜底关链 task={} trace={} {} -> FAILED (证据陈旧 {}s; 探活=NOT_ALIVE)",
                    taskId, traceId, from, staleFor);
        } catch (IllegalTaskTransitionException | ExecutionMismatchException e) {
            // 从"探活"到"关链"之间, Python 的终态上报 (或另一条心跳) 赢了。
            // 结果正确, 只是不是由我们写的 —— 不算错误。
            log.debug("关链时被并发上报抢先 task={} trace={}: {}", taskId, traceId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("心跳兜底关链失败 task={} trace={}", taskId, traceId, e);
        }
    }

    private boolean tenantMatches(AgentTask task) {
        String tenant = config.getTenant();
        return !StringUtils.hasText(tenant) || tenant.trim().equals(task.getTenantId());
    }
}
