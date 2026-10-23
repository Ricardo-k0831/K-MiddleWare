package com.km.task.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.km.audit.AuditEvent;
import com.km.audit.AuditEventType;
import com.km.audit.AuditLayer;
import com.km.audit.AuditPublisher;
import com.km.task.domain.AgentTask;
import com.km.task.domain.AgentTaskAttempt;
import com.km.task.domain.AgentTaskEvent;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.domain.TaskStatusMachine;
import com.km.task.error.ExecutionMismatchException;
import com.km.task.error.IllegalTaskTransitionException;
import com.km.task.error.TaskNotFoundException;
import com.km.task.mapper.AgentTaskAttemptMapper;
import com.km.task.mapper.AgentTaskEventMapper;
import com.km.task.mapper.AgentTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
    private final AgentTaskAttemptMapper attemptMapper;
    private final AuditPublisher audit;

    public TaskService(AgentTaskMapper taskMapper, AgentTaskEventMapper eventMapper,
                       AgentTaskAttemptMapper attemptMapper, AuditPublisher audit) {
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.attemptMapper = attemptMapper;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    //  建任务
    // ------------------------------------------------------------------

    @Transactional
    public AgentTask create(String userId, String sessionId, String intent,
                            String tenantId, Integer maxRetry) {
        AgentTask task = new AgentTask();
        task.setAgentTaskId(newTaskId());
        // ★ trace_id 的唯一生成点 —— 准确说, 是"任务第一次执行"的生成点 ★
        // 全链路 (Java 控制面 -> Python 运行时 -> MCP 网关) 用的都是这一个值。
        // 生成点放在这里而不是 Python 侧, 是因为任务服务的 Response 就是
        // Python 拿到它的地方 —— 少一次"谁先生成"的协商。
        //
        // 重试时要另生成一个 trace_id (同一个 task_id), 生成点随之从 create()
        // 移到"开始一次执行"的地方 —— 见 newTraceId() 的注释。
        task.setTraceId(newTraceId());
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

        // ★ 任务和它的第一次执行在同一个事务里出生 ★
        //
        // 这一行是 trace_id 的权威来源 —— 审计的 TASK_LIFECYCLE 事件必须从
        // 这里取 trace, 不能从 agent_task.trace_id 取 (那是会变的滚动值)。
        // trace 和任务一起提交, 所以不存在"任务在、执行不在"的中间态。
        AgentTaskAttempt attempt = new AgentTaskAttempt();
        attempt.setAgentTaskId(task.getAgentTaskId());
        attempt.setTraceId(task.getTraceId());
        attempt.setAttemptNo(1);
        attempt.setStatus(AgentTaskAttempt.RUNNING);
        attemptMapper.insert(attempt);

        // 任务的"出生"事件: fromStatus 为 null。
        // 写它是为了让 GET /events 返回的序列是自解释的 ——
        // 否则事件表的第一条是 PENDING -> THINKING, 会让人以为任务从 THINKING 才开始。
        appendEvent(task.getAgentTaskId(), null, TaskStatus.PENDING,
                TaskEventSource.JAVA_GATEWAY, Map.of());

        // 审计走 MQ 旁路。
        // 此刻事务还没提交 —— AuditPublisher 会自动把这个事件推到 afterCommit
        // 再发, 所以"库里有任务"和"审计里有创建事件"不会脱钩。
        audit.publish(AuditEvent.builder()
                .taskId(task.getAgentTaskId())
                .traceId(task.getTraceId())
                .tenantId(task.getTenantId())
                .layer(AuditLayer.TASK_SERVICE)
                .eventType(AuditEventType.TASK_LIFECYCLE)
                .actor(userId)
                .status(AuditEvent.OK)
                // requestBody = 这次创建请求的参数; responseBody = 创建出来的任务
                .requestBody(createRequest(sessionId, intent))
                .responseBody(taskSnapshot(task))
                .build());

        log.info("任务已创建 id={} traceId={} user={}",
                task.getAgentTaskId(), task.getTraceId(), userId);
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
    public AgentTask reportStatus(String agentTaskId, String traceId, TaskStatus to,
                                  TaskEventSource source, Map<String, Object> detail) {

        AgentTask task = requireTask(agentTaskId);

        // (0) ★ 执行身份校验, 必须在任何状态判断之前 ★
        //
        // 顺序很重要: traceId 错了就是"这条上报根本不该算在这个任务/这次执行头上",
        // 后面所有逻辑都不该执行 —— 包括幂等判断。否则一个拿着错 trace 的重复上报
        // 会得到 200, 调用方以为没事, 而审计里已经少了一条本该属于正确链路的记录。
        AgentTaskAttempt attempt = requireAttempt(agentTaskId, traceId);
        TaskStatus from = parseStatus(task);

        // (1) 幂等: 目标状态 == 当前状态。
        //     回调走网络, 重复投递是常态而不是异常(Python 侧超时重试、网络重放)。
        //     当成成功处理, 但【不写事件表】—— 否则事件流会被重复记录淹没。
        //
        //     注意放在执行身份校验之后、闭链检查之前: 一次已经闭链的执行,
        //     如果客户端只是重发同一条终态上报 (超时重试), 应当得到 200 而不是 409。
        if (TaskStatusMachine.isNoop(from, to)) {
            log.debug("重复上报, 忽略 task={} status={}", agentTaskId, to);
            return task;
        }

        // (1.5) 这次执行是否还活着。
        //       闭链后还来一条【不同的】状态上报, 说明是迟到的旧执行 ——
        //       绝不能让它改任务状态, 更不能让它给审计盖章 (它会带着旧 trace
        //       混进当前执行的链路里)。
        if (!attempt.isRunning()) {
            throw new ExecutionMismatchException(agentTaskId, traceId,
                    "该执行已于 " + attempt.getEndedAt() + " 结束, 不再接受状态上报");
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

        // (4.5) 终态 = 这次执行闭链。
        //       和任务状态、事件表在同一个事务里, 所以不存在"任务已 COMPLETED
        //       但这次执行还显示在跑"的中间态。闭链之后, 迟到的旧上报会在
        //       (1.5) 被挡住。
        if (to.isTerminal()) {
            closeAttempt(attempt, to, now);
        }

        AgentTask updated = requireTask(agentTaskId);

        // (5) 审计走 MQ 旁路, 和 create() 里那段同一个写法。
        //     此刻事务还没提交, AuditPublisher 会自动推到 afterCommit 再发。
        audit.publish(AuditEvent.builder()
                .taskId(agentTaskId)
                // ★ trace 来自【本次上报所声明的执行】, 不是 agent_task 表 ★
                //
                // 这不是风格问题: 任务表上那一列是"最近一次执行", 会变;
                // 而审计要回答的是"这条流转属于哪一次执行", 事后不该再依赖
                // 一张会变的表去推断。attempt 行建好后 trace 就不变了。
                // (见 AgentTaskAttempt 的类注释。)
                .traceId(attempt.getTraceId())
                .tenantId(updated.getTenantId())
                .layer(AuditLayer.TASK_SERVICE)
                .eventType(AuditEventType.TASK_LIFECYCLE)
                // actor 用任务上的 userId 而不是"谁调的这个接口" ——
                // 状态上报是 Python 运行时发的, 但审计要回答的是"哪个【用户】
                // 的任务在流转"。调用方身份在 source 里 (PYTHON_RUNTIME)。
                .actor(updated.getUserId())
                .status(AuditEvent.OK)
                .requestBody(transitionRequest(from, to, source, detail))
                .responseBody(taskSnapshot(updated))
                .build());

        log.info("状态流转 task={} {} -> {} (source={})", agentTaskId, from, to, source);
        return updated;
    }

    /**
     * 状态流转事件的 requestBody。
     *
     * <p><b>★ 为什么 to=FAILED 时 status 仍然是 OK ★</b>
     *
     * <p>{@code status} 在整个审计里只有一个含义 —— <b>这次操作算不算系统故障</b>。
     * 任务流转到 FAILED 是<b>业务结果</b>, 不是系统故障: 任务服务忠实地记录了
     * 它, 一切正常。
     *
     * <p>把业务失败也记成 ERROR 的代价是 {@code WHERE status='ERROR'} 这句话
     * 在四层里变成四个意思 —— 任务层是"任务没做成", 网关层是"后端连不上"。
     * 那一列就再也统计不出东西了, 而它是四层唯一统一的健康指标。
     * 和网关那边"4xx 记 OK"是同一条规则 (见 CallStatus)。
     *
     * <p>要查"哪些任务失败了"就查这里:
     * {@code JSON_UNQUOTE(JSON_EXTRACT(request_body,'$.to')) = 'FAILED'}。
     * 业务结果本来就该从业务字段里查, 而不是从健康指标里推断。
     */
    private static Map<String, Object> transitionRequest(TaskStatus from, TaskStatus to,
                                                         TaskEventSource source,
                                                         Map<String, Object> detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from.name());
        body.put("to", to.name());
        body.put("source", source.name());
        putIfPresent(body, "detail", detail);
        return body;
    }

    // ------------------------------------------------------------------
    //  内部
    // ------------------------------------------------------------------

    /**
     * 找到这次上报所属的执行, 并校验它确实属于这个任务。
     *
     * <p>★ 为什么用 trace 查, 而不是"肯定是当前那次执行" ★
     *
     * <p>因为后者是推断。现在一个任务只有一次执行, 所以推断永远正确;
     * 一旦有第二遍, 推断就会把第一遍的在途上报算到第二遍头上 —— 而且
     * 不会有任何报错。用 trace 查, 拿到的是调用方<b>声明</b>的身份,
     * 再由 Java 校验; 声明错了就 409, 当场暴露。
     *
     * <p>这也是"不接受调用方声明 fromStatus"的同一条原则: 状态内容以库为准,
     * 但执行身份必须由调用方明确说出来 —— 两者都是"别猜"。
     */
    private AgentTaskAttempt requireAttempt(String agentTaskId, String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new ExecutionMismatchException(agentTaskId, traceId, "traceId 为空");
        }

        AgentTaskAttempt attempt = attemptMapper.selectOne(
                Wrappers.<AgentTaskAttempt>lambdaQuery()
                        .eq(AgentTaskAttempt::getTraceId, traceId));

        if (attempt == null) {
            throw new ExecutionMismatchException(agentTaskId, traceId, "该 trace 不对应任何执行");
        }
        // trace 在库里有唯一索引, 这里再比一次任务是防御跨任务串号:
        // 把任务 A 的 trace 发给任务 B 时, 事件绝不能记到 B 的头上。
        if (!agentTaskId.equals(attempt.getAgentTaskId())) {
            throw new ExecutionMismatchException(agentTaskId, traceId,
                    "该 trace 属于任务 " + attempt.getAgentTaskId());
        }
        return attempt;
    }

    /**
     * 给这次执行闭链。
     *
     * <p>{@code WHERE ended_at IS NULL} 是 CAS: 两条并发的终态上报里只有
     * 一条能闭链, 另一条影响 0 行。那不是错误 (状态机那边会先拒绝一条),
     * 所以只记 debug, 不抛异常。
     */
    private void closeAttempt(AgentTaskAttempt attempt, TaskStatus terminal, LocalDateTime now) {
        int affected = attemptMapper.update(null,
                Wrappers.<AgentTaskAttempt>lambdaUpdate()
                        .eq(AgentTaskAttempt::getId, attempt.getId())
                        .isNull(AgentTaskAttempt::getEndedAt)
                        .set(AgentTaskAttempt::getEndedAt, now)
                        .set(AgentTaskAttempt::getStatus, terminal.name()));
        if (affected == 0) {
            log.debug("执行已闭链, 跳过 task={} trace={}",
                    attempt.getAgentTaskId(), attempt.getTraceId());
        }
    }

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

    /**
     * 生成 traceId —— 32 位小写十六进制, 和 W3C Trace Context 里
     * {@code traceparent} 的 trace-id 段完全同形。
     *
     * <p>刻意对齐 W3C 格式, 是为了将来真接 OTel 时可以直接拿它当 traceId,
     * 不用做 ID 迁移 —— 已经落库的历史数据迁移起来是最烦的。
     *
     * <p><b>任务和调用链是 1:N, 不是 1:1 —— 所以不能拿 taskId 当 traceId</b>:
     * 任务可以重试/重跑, <b>每一遍执行是一条独立的调用链</b> (新 traceId,
     * 同一个 taskId)。合并成一个 ID 的话, 第二遍执行就和第一遍混成了一条链:
     * 分不清某个工具调用发生在哪一遍, token 和耗时也会被重复累加。
     *
     * <p><b>反过来, 一条链也不跨任务</b>: 同一个会话里连问三件事是三个任务,
     * 也就是三条独立的链。串起它们的是 {@code session_id} —— 不是 traceId。
     * (会话不是一条 trace: 一条 trace 的边界是【一次执行】。见
     * docs/audit.md 第二节。)
     *
     * <p><b>现在实际上还是 1:1, 因为重试还没实现</b>: {@link #create} 同时
     * 创建了任务和它的第一次执行, 所以两个 ID 一起生成。等重试落地, 这个
     * 方法的调用点要从 create() 移到"开始一次执行"的地方, 并且
     * {@code agent_task.trace_id} 的语义固定为【最近一次执行】的 trace ——
     * 而不是任务的、也不是永久的。
     */
    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 任务创建事件的 requestBody —— 这次创建请求的原始参数。
     *
     * <p>用 LinkedHashMap 手拼而不是 {@code Map.of}: {@code sessionId} /
     * {@code intent} 都是可选入参, 而 {@code Map.of} 遇到 null 值直接抛 NPE ——
     * 一个可选参数不该有能力把请求炸掉。
     */
    private static Map<String, Object> createRequest(String sessionId, String intent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phase", "CREATED");
        putIfPresent(body, "sessionId", sessionId);
        putIfPresent(body, "intent", intent);
        return body;
    }

    /** 任务快照 —— 事件发生那一刻这个任务长什么样。 */
    private static Map<String, Object> taskSnapshot(AgentTask task) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", task.getStatus());
        snapshot.put("maxRetry", task.getMaxRetry());
        snapshot.put("retryCount", task.getRetryCount());
        return snapshot;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
