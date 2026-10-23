package com.km.task.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.km.audit.AuditEvent;
import com.km.audit.AuditPublisher;
import com.km.task.domain.AgentTask;
import com.km.task.domain.AgentTaskAttempt;
import com.km.task.domain.AgentTaskEvent;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.error.ExecutionMismatchException;
import com.km.task.mapper.AgentTaskAttemptMapper;
import com.km.task.mapper.AgentTaskEventMapper;
import com.km.task.mapper.AgentTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 执行身份与溯源的核心规则。
 *
 * <h3>为什么不用 @SpringBootTest</h3>
 *
 * <p>这里要证明的是几条"用错时不会报错"的规则 —— 比如审计的 trace 到底来自
 * 上报还是来自任务表。起一个 Spring 上下文只会让失败变慢, 不会让它更可信。
 * 和 {@code TaskStatusMachineTest} / {@code AuditIngestServiceTest} 同一个思路。
 *
 * <h3>为什么要手工初始化 TableInfo</h3>
 *
 * <p>MyBatis-Plus 的 lambda wrapper 在 {@code .eq(Entity::getTraceId, ...)}
 * 这一步就去查列名缓存。没有 Spring 就没有人给它建缓存, 直接抛
 * "can not find lambda cache"。这不是在测 MyBatis, 只是让被测代码能跑起来。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final String TASK_ID = "task-1";
    private static final String TRACE_ID = "trace-1";

    @Mock
    private AgentTaskMapper taskMapper;

    @Mock
    private AgentTaskEventMapper eventMapper;

    @Mock
    private AgentTaskAttemptMapper attemptMapper;

    @Mock
    private AuditPublisher audit;

    private TaskService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, AgentTask.class);
        TableInfoHelper.initTableInfo(assistant, AgentTaskAttempt.class);
    }

    @BeforeEach
    void setUp() {
        service = new TaskService(taskMapper, eventMapper, attemptMapper, audit);
    }

    // ------------------------------------------------------------------
    //  执行身份
    // ------------------------------------------------------------------

    @Test
    void trace查不到时拒绝() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.PENDING, TRACE_ID));
        when(attemptMapper.selectOne(any())).thenReturn(null);

        assertThrows(ExecutionMismatchException.class,
                () -> service.reportStatus(TASK_ID, "不存在的trace",
                        TaskStatus.THINKING, TaskEventSource.PYTHON_RUNTIME, Map.of()));

        // 身份没确认之前, 一行都不该落库, 一条审计也不该发
        verifyNoInteractions(eventMapper);
        verify(audit, never()).publish(any());
    }

    @Test
    void trace属于别的任务时拒绝() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.PENDING, TRACE_ID));
        AgentTaskAttempt other = attempt("other-task", "trace-of-other");
        when(attemptMapper.selectOne(any())).thenReturn(other);

        ExecutionMismatchException ex = assertThrows(ExecutionMismatchException.class,
                () -> service.reportStatus(TASK_ID, "trace-of-other",
                        TaskStatus.THINKING, TaskEventSource.PYTHON_RUNTIME, Map.of()));

        // 错在"事件会被记到本任务头上" —— 这是跨任务串号, 必须能看出来
        assertTrue(ex.getMessage().contains("other-task"));
        verifyNoInteractions(eventMapper);
    }

    @Test
    void 已闭链的执行不再接受新的状态上报() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.THINKING, TRACE_ID));
        AgentTaskAttempt closed = attempt(TASK_ID, TRACE_ID);
        closed.setEndedAt(LocalDateTime.now());
        when(attemptMapper.selectOne(any())).thenReturn(closed);

        assertThrows(ExecutionMismatchException.class,
                () -> service.reportStatus(TASK_ID, TRACE_ID,
                        TaskStatus.TOOL_CALLING, TaskEventSource.PYTHON_RUNTIME, Map.of()));

        verifyNoInteractions(eventMapper);
    }

    @Test
    void 已闭链后的重复终态上报仍然幂等() {
        // 网络重发是同一条终态, 不该因为"闭链了"变成 409 ——
        // 否则 Python 的超时重试会把一次成功上报变成一条永久的错误日志。
        AgentTask done = task(TaskStatus.COMPLETED, TRACE_ID);
        when(taskMapper.selectById(TASK_ID)).thenReturn(done);
        AgentTaskAttempt closed = attempt(TASK_ID, TRACE_ID);
        closed.setEndedAt(LocalDateTime.now());
        when(attemptMapper.selectOne(any())).thenReturn(closed);

        AgentTask result = service.reportStatus(TASK_ID, TRACE_ID,
                TaskStatus.COMPLETED, TaskEventSource.PYTHON_RUNTIME, Map.of());

        assertEquals(TaskStatus.COMPLETED.name(), result.getStatus());
        verify(taskMapper, never()).update(any(), any());
        verifyNoInteractions(eventMapper);
    }

    // ------------------------------------------------------------------
    //  溯源: 审计的 trace 来自哪里
    // ------------------------------------------------------------------

    @Test
    void 审计的trace来自上报的执行而不是任务表() {
        // ★ 这条是整个重构的判据 ★
        //
        // 任务表故意放一个【不同的】trace。如果实现偷懒从 agent_task 取,
        // 这个用例会失败; 从 attempt 取则通过。
        // 现在重试还没实现, 两个值恰好相等, 所以很容易写成"看起来对"的版本。
        AgentTask task = task(TaskStatus.PENDING, "task-table-trace");
        AgentTask updated = task(TaskStatus.THINKING, "task-table-trace");
        when(taskMapper.selectById(TASK_ID)).thenReturn(task, updated);
        when(attemptMapper.selectOne(any())).thenReturn(attempt(TASK_ID, TRACE_ID));
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        service.reportStatus(TASK_ID, TRACE_ID,
                TaskStatus.THINKING, TaskEventSource.PYTHON_RUNTIME, Map.of());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).publish(captor.capture());
        assertEquals(TRACE_ID, captor.getValue().traceId());
    }

    @Test
    void 非终态不闭链() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(
                task(TaskStatus.PENDING, TRACE_ID), task(TaskStatus.THINKING, TRACE_ID));
        when(attemptMapper.selectOne(any())).thenReturn(attempt(TASK_ID, TRACE_ID));
        when(taskMapper.update(isNull(), any())).thenReturn(1);

        service.reportStatus(TASK_ID, TRACE_ID,
                TaskStatus.THINKING, TaskEventSource.PYTHON_RUNTIME, Map.of());

        verify(attemptMapper, never()).update(any(), any());
    }

    @Test
    void 终态给这次执行闭链() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(
                task(TaskStatus.THINKING, TRACE_ID), task(TaskStatus.COMPLETED, TRACE_ID));
        when(attemptMapper.selectOne(any())).thenReturn(attempt(TASK_ID, TRACE_ID));
        when(taskMapper.update(isNull(), any())).thenReturn(1);
        when(attemptMapper.update(isNull(), any())).thenReturn(1);

        service.reportStatus(TASK_ID, TRACE_ID,
                TaskStatus.COMPLETED, TaskEventSource.PYTHON_RUNTIME, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AgentTaskAttempt>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(attemptMapper).update(isNull(), captor.capture());

        // 只允许改这两列: 闭链时刻和终态。trace / attempt_no / started_at 是
        // 不可变的 —— 它们一旦能被改, "溯源信息不可变"这个前提就没了。
        String sqlSet = captor.getValue().getSqlSet().toString();
        assertTrue(sqlSet.contains("ended_at"), "没写闭链时刻: " + sqlSet);
        assertTrue(sqlSet.contains("status"), "没写终态: " + sqlSet);
        assertTrue(!sqlSet.contains("trace_id"), "trace_id 被更新了: " + sqlSet);
    }

    // ------------------------------------------------------------------
    //  建任务: 任务和第一次执行一起出生
    // ------------------------------------------------------------------

    @Test
    void 建任务时同时建出第一次执行() {
        AtomicReference<AgentTask> inserted = new AtomicReference<>();
        when(taskMapper.insert(any(AgentTask.class))).thenAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return 1;
        });
        when(attemptMapper.insert(any(AgentTaskAttempt.class))).thenReturn(1);
        when(eventMapper.insert(any(AgentTaskEvent.class))).thenReturn(1);
        when(taskMapper.selectById(anyString()))
                .thenAnswer(inv -> inserted.get());

        service.create("u-1", "s-1", "查订单", "km", 3);

        ArgumentCaptor<AgentTaskAttempt> captor =
                ArgumentCaptor.forClass(AgentTaskAttempt.class);
        verify(attemptMapper).insert(captor.capture());

        AgentTaskAttempt attempt = captor.getValue();
        assertEquals(inserted.get().getAgentTaskId(), attempt.getAgentTaskId());
        // ★ 同一个 trace: 任务表和执行表在出生时就必须一致 ★
        // 不一致的话, 从建任务那一刻起审计的溯源就已经断了一节。
        assertEquals(inserted.get().getTraceId(), attempt.getTraceId());
        assertEquals(1, attempt.getAttemptNo());
        assertEquals(AgentTaskAttempt.RUNNING, attempt.getStatus());
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    private static AgentTask task(TaskStatus status, String traceId) {
        AgentTask task = new AgentTask();
        task.setAgentTaskId(TASK_ID);
        task.setTraceId(traceId);
        task.setTenantId("km");
        task.setUserId("u-1");
        task.setSessionId("s-1");
        task.setIntent("查订单");
        task.setStatus(status.name());
        task.setRetryCount(0);
        task.setMaxRetry(3);
        return task;
    }

    private static AgentTaskAttempt attempt(String taskId, String traceId) {
        AgentTaskAttempt attempt = new AgentTaskAttempt();
        attempt.setId(1L);
        attempt.setAgentTaskId(taskId);
        attempt.setTraceId(traceId);
        attempt.setAttemptNo(1);
        attempt.setStatus(AgentTaskAttempt.RUNNING);
        return attempt;
    }
}
