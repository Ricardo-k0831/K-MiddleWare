package com.km.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.km.task.domain.AgentTask;
import com.km.task.domain.AgentTaskEvent;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.domain.TaskStatusMachine;
import com.km.task.error.IllegalTaskTransitionException;
import com.km.task.error.TaskNotFoundException;
import com.km.task.mapper.AgentTaskEventMapper;
import com.km.task.mapper.AgentTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务生命周期的全部业务逻辑。
 *
 * <p><b>这个类是状态机的执行者。</b>Python 侧只能"上报", 能不能变由这里说了算。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private static final int DEFAULT_MAX_RETRY = 3;
    private static final String DEFAULT_TENANT = "default";

    private final AgentTaskMapper taskMapper;
    private final AgentTaskEventMapper eventMapper;

    public TaskService(AgentTaskMapper taskMapper, AgentTaskEventMapper eventMapper) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
    }

    // ------------------------------------------------------------------
    //  建任务
    // ------------------------------------------------------------------

    @Transactional
    public AgentTask create(String userId, String sessionId, String intent,
                            String tenantId, Integer maxRetry) {
        AgentTask task = new AgentTask();
        task.setAgentTaskId(newTaskId());
        task.setTenantId(isBlank(tenantId) ? DEFAULT_TENANT : tenantId);
        task.setUserId(userId);
        task.setSessionId(sessionId);
        task.setIntent(intent);
        task.setStatus(TaskStatus.PENDING.name());
        task.setRetryCount(0);
        task.setMaxRetry(maxRetry == null || maxRetry < 0 ? DEFAULT_MAX_RETRY : maxRetry);

        // created_at / updated_at 由数据库的 DEFAULT CURRENT_TIMESTAMP(3) 填,
        // 所以这里不设。insert 之后对象里还是没有这两个值, 需要重新查一次。
        taskMapper.insert(task);

        // 任务的"出生"事件: fromStatus 为 null。
        // 写它是为了让 GET /events 返回的序列是自解释的 ——
        // 否则事件表的第一条是 PENDING -> THINKING, 会让人以为任务从 THINKING 才开始。
        appendEvent(task.getAgentTaskId(), null, TaskStatus.PENDING,
                TaskEventSource.JAVA_GATEWAY, Map.of());

        log.info("任务已创建 id={} user={}", task.getAgentTaskId(), userId);
        return requireTask(task.getAgentTaskId());
    }

    // ------------------------------------------------------------------
    //  查
    // ------------------------------------------------------------------

    public AgentTask requireTask(String agentTaskId) {
        AgentTask task = taskMapper.selectById(agentTaskId);
        if (task == null) {
            throw new TaskNotFoundException(agentTaskId);
        }
        return task;
    }

    public List<AgentTaskEvent> listEvents(String agentTaskId) {
        // 先确认任务存在 —— 否则任务不存在和"任务存在但没有事件"都返回空数组,
        // 调用方分不清是打错了 ID 还是真没事件。
        requireTask(agentTaskId);

        return eventMapper.selectList(
                Wrappers.<AgentTaskEvent>lambdaQuery()
                        .eq(AgentTaskEvent::getAgentTaskId, agentTaskId)
                        // 按自增主键排序 = 按写入顺序。不要按 created_at 排 ——
                        // 同一毫秒内发生的两次流转, 时间戳可能完全相同, 顺序就丢了。
                        .orderByAsc(AgentTaskEvent::getId));
    }

    // ------------------------------------------------------------------
    //  状态上报 —— 核心
    // ------------------------------------------------------------------

    /**
     * 处理一次状态上报。
     *
     * <h3>并发安全</h3>
     *
     * "读当前状态 -> 判断 -> 写新状态"这三步之间存在窗口: 两个并发请求可能
     * 都读到 THINKING, 都认为可以变, 然后都写。结果事件表里出现两条本该
     * 互斥的记录, 状态机就形同虚设了。
     *
     * 所以真正落库用的是 <b>CAS</b>: {@code UPDATE ... WHERE status = 'THINKING'}。
     * 更新影响行数为 0 就说明在我们读完之后状态被别人改掉了, 直接判 409。
     */
    @Transactional
    public AgentTask reportStatus(String agentTaskId, TaskStatus to,
                                  TaskEventSource source, Map<String, Object> detail) {

        AgentTask task = requireTask(agentTaskId);
        TaskStatus from = parseStatus(task);

        // (1) 幂等: 目标状态 == 当前状态。
        //     回调走网络, 重复投递是常态而不是异常(Python 侧超时重试、网络重放)。
        //     当成成功处理, 但【不写事件表】—— 否则事件流会被重复记录淹没。
        if (TaskStatusMachine.isNoop(from, to)) {
            log.debug("重复上报, 忽略 task={} status={}", agentTaskId, to);
            return task;
        }

        // (2) 合法性。终态不可离开、不能跳级 —— 规则见 TaskStatusMachine。
        if (!TaskStatusMachine.isAllowed(from, to)) {
            log.warn("拒绝非法流转 task={} {} -> {}", agentTaskId, from, to);
            throw new IllegalTaskTransitionException(agentTaskId, from, to);
        }

        // (3) CAS 落库
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AgentTask> update = Wrappers.<AgentTask>lambdaUpdate()
                .eq(AgentTask::getAgentTaskId, agentTaskId)
                .eq(AgentTask::getStatus, from.name())
                .set(AgentTask::getStatus, to.name())
                .set(AgentTask::getUpdatedAt, now)
                .set(to.isTerminal(), AgentTask::getFinishedAt, now);

        // 只在真的有值时才 set, 免得把已有的 error_msg / result 覆盖成 null
        String errorText = asText(detail, "error");
        if (to == TaskStatus.FAILED && errorText != null) {
            update.set(AgentTask::getErrorMsg, errorText);
        }
        String resultText = asText(detail, "result");
        if (to == TaskStatus.COMPLETED && resultText != null) {
            update.set(AgentTask::getResult, resultText);
        }

        int affected = taskMapper.update(null, update);
        if (affected == 0) {
            // 读到写之间被人抢先改了。这不是"规则不允许", 是并发冲突 ——
            // 两者都返回 409, 但 reason 要能区分, 否则排查时会误以为是 Python 的 bug。
            log.warn("状态上报并发冲突 task={} {} -> {}", agentTaskId, from, to);
            throw new IllegalTaskTransitionException(agentTaskId, from.name(), to.name(),
                    "并发冲突: 状态在读取之后已被其他请求修改");
        }

        // (4) 事件落表。和上面的 UPDATE 在同一个事务里 ——
        //     要么"状态改了且留下了记录", 要么两者都没发生, 不存在中间态。
        appendEvent(agentTaskId, from, to, source, detail);

        log.info("状态流转 task={} {} -> {} (source={})", agentTaskId, from, to, source);
        return requireTask(agentTaskId);
    }

    // ------------------------------------------------------------------
    //  内部
    // ------------------------------------------------------------------

    private void appendEvent(String agentTaskId, TaskStatus from, TaskStatus to,
                             TaskEventSource source, Map<String, Object> detail) {
        AgentTaskEvent event = new AgentTaskEvent();
        event.setAgentTaskId(agentTaskId);
        event.setFromStatus(from == null ? null : from.name());
        event.setToStatus(to.name());
        event.setSource(source.name());
        event.setDetail(detail == null ? Map.of() : detail);
        eventMapper.insert(event);
    }

    private static TaskStatus parseStatus(AgentTask task) {
        try {
            return TaskStatus.valueOf(task.getStatus());
        } catch (IllegalArgumentException ex) {
            // 库里存了个枚举里没有的值 —— 只可能是有人手工改过库,
            // 或者枚举被删过项。这是数据损坏, 不是业务异常, 所以抛 500。
            throw new IllegalStateException(
                    "数据库中 task=" + task.getAgentTaskId()
                            + " 的 status 不是合法状态: " + task.getStatus(), ex);
        }
    }

    private static String asText(Map<String, Object> detail, String key) {
        if (detail == null) {
            return null;
        }
        Object v = detail.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String newTaskId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
