package com.km.task.heartbeat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.km.task.domain.AgentTask;
import com.km.task.domain.AgentTaskAttempt;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.error.IllegalTaskTransitionException;
import com.km.task.mapper.AgentTaskAttemptMapper;
import com.km.task.mapper.AgentTaskMapper;
import com.km.task.service.TaskService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 扫描器只做两件事: <b>发现可疑</b>和<b>转述探活结论</b>。
 *
 * <p>断言集中在"什么结论允许改状态":
 * 只有探活明确返回 NOT_ALIVE 才关链; ALIVE 刷新心跳; UNKNOWN 什么都不做。
 * 状态机、CAS、闭链这些规则在 {@code TaskService.reportStatus} 里, 这里不重测。
 */
class HeartbeatSweeperTest {

    private static final String TASK_ID = "task-1";
    private static final String TRACE_ID = "trace-1";

    private AgentTaskAttemptMapper attemptMapper;
    private AgentTaskMapper taskMapper;
    private TaskService taskService;
    private HeartbeatProbe probe;
    private HeartbeatProperties properties;
    private HeartbeatSweeper sweeper;

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
        attemptMapper = mock(AgentTaskAttemptMapper.class);
        taskMapper = mock(AgentTaskMapper.class);
        taskService = mock(TaskService.class);
        probe = mock(HeartbeatProbe.class);
        properties = new HeartbeatProperties();
        properties.setStaleAfterSeconds(120);
        properties.setBatchSize(20);
        sweeper = new HeartbeatSweeper(attemptMapper, taskMapper, taskService, probe, properties);
    }

    @Test
    void 探活确认没在跑时关链并标记来源() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(staleAttempt()));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.PENDING));
        when(probe.probe(TRACE_ID)).thenReturn(HeartbeatProbe.Verdict.NOT_ALIVE);

        sweeper.sweep();

        // 用 reportStatus 而不是自己写 SQL: 关链要同时完成状态、事件、闭链、审计。
        verify(taskService).reportStatus(eq(TASK_ID), eq(TRACE_ID),
                eq(TaskStatus.FAILED), eq(TaskEventSource.SYSTEM_HEARTBEAT),
                argThat(detail -> "HEARTBEAT".equals(detail.get("phase"))
                        && "PENDING".equals(detail.get("previousStatus"))
                        && String.valueOf(detail.get("error")).contains(TRACE_ID)));
    }

    @Test
    void 探活说还在跑时只刷新心跳不关链() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(staleAttempt()));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.THINKING));
        when(probe.probe(TRACE_ID)).thenReturn(HeartbeatProbe.Verdict.ALIVE);

        sweeper.sweep();

        // ★ 探活本身也是一次心跳 ★ 否则长任务每一轮都会被重新探活,
        // 而 Java 探到的事实没有被记下来。
        verify(taskService).heartbeat(TASK_ID, TRACE_ID);
        verify(taskService, never()).reportStatus(any(), any(), any(), any(), anyMap());
    }

    @Test
    void 探活未知时什么都不做() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(staleAttempt()));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.THINKING));
        when(probe.probe(TRACE_ID)).thenReturn(HeartbeatProbe.Verdict.UNKNOWN);

        sweeper.sweep();

        // 未知不是死亡。Python 可能只是这一瞬间不可达。
        verifyNoInteractions(taskService);
    }

    @Test
    void 任务已是终态时不做探活() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(staleAttempt()));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.COMPLETED));

        sweeper.sweep();

        // 终态 + 未闭链是数据不一致。再写一次终态只会把问题伪装成一次正常流转。
        verify(probe, never()).probe(any());
        verifyNoInteractions(taskService);
    }

    @Test
    void 租户不匹配时跳过() {
        properties.setTenant("heartbeat-e2e");
        when(attemptMapper.selectList(any())).thenReturn(List.of(staleAttempt()));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.THINKING));

        sweeper.sweep();

        verify(probe, never()).probe(any());
        verifyNoInteractions(taskService);
    }

    @Test
    void 查询失败时本轮放弃而不抛出() {
        when(attemptMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> sweeper.sweep());

        verifyNoInteractions(taskService, probe);
    }

    @Test
    void 探活异常时跳过而不是整轮中断() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(staleAttempt()));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.THINKING));
        when(probe.probe(TRACE_ID)).thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> sweeper.sweep());

        verifyNoInteractions(taskService);
    }

    @Test
    void 任务不存在时跳过() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(staleAttempt()));
        when(taskMapper.selectById(TASK_ID)).thenReturn(null);

        sweeper.sweep();

        verifyNoInteractions(taskService, probe);
    }

    @Test
    void 关链被并发上报抢先时不抛出() {
        when(attemptMapper.selectList(any())).thenReturn(List.of(staleAttempt()));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.PENDING));
        when(probe.probe(TRACE_ID)).thenReturn(HeartbeatProbe.Verdict.NOT_ALIVE);
        // 从探活到关链之间, Python 的终态上报赢了 —— 结果正确, 只是不是我们写的。
        when(taskService.reportStatus(any(), any(), any(), any(), anyMap()))
                .thenThrow(new IllegalTaskTransitionException(TASK_ID, "PENDING", "FAILED", "并发冲突"));

        assertDoesNotThrow(() -> sweeper.sweep());
    }

    @Test
    void 候选查询只含开着的且有存活证据的执行() {
        when(attemptMapper.selectList(any())).thenReturn(List.of());

        sweeper.sweep();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AgentTaskAttempt>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(attemptMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();

        // ★ 没有 dispatched_at / last_heartbeat_at 的执行不在范围内 ★
        // 新建任务可能合法地等调用方很久, 年龄本身不能作为"卡住"的证据。
        assertTrue(sql.contains("ended_at IS NULL"), sql);
        assertTrue(sql.contains("COALESCE(last_heartbeat_at, dispatched_at)"), sql);
    }

    @Test
    void 关链detail里带着证据时间和陈旧秒数() {
        AgentTaskAttempt attempt = staleAttempt();
        when(attemptMapper.selectList(any())).thenReturn(List.of(attempt));
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(TaskStatus.TOOL_CALLING));
        when(probe.probe(TRACE_ID)).thenReturn(HeartbeatProbe.Verdict.NOT_ALIVE);

        sweeper.sweep();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class) Map.class);
        verify(taskService).reportStatus(eq(TASK_ID), eq(TRACE_ID),
                eq(TaskStatus.FAILED), eq(TaskEventSource.SYSTEM_HEARTBEAT), captor.capture());

        Map<String, Object> detail = captor.getValue();
        assertTrue(detail.get("lastHeartbeatAt") instanceof String, "缺心跳时刻: " + detail);
        assertTrue(detail.get("dispatchedAt") instanceof String, "缺派发时刻: " + detail);
        assertTrue(detail.get("staleForSeconds") instanceof Long, "缺陈旧秒数: " + detail);
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    /** 一条"有派发、有心跳、但已经过期"的执行。 */
    private static AgentTaskAttempt staleAttempt() {
        LocalDateTime now = LocalDateTime.now();
        AgentTaskAttempt attempt = new AgentTaskAttempt();
        attempt.setId(1L);
        attempt.setAgentTaskId(TASK_ID);
        attempt.setTraceId(TRACE_ID);
        attempt.setAttemptNo(2);
        attempt.setStatus(AgentTaskAttempt.RUNNING);
        attempt.setStartedAt(now.minusMinutes(10));
        attempt.setDispatchedAt(now.minusMinutes(9));
        attempt.setLastHeartbeatAt(now.minusMinutes(5));
        return attempt;
    }

    private static AgentTask task(TaskStatus status) {
        AgentTask task = new AgentTask();
        task.setAgentTaskId(TASK_ID);
        task.setTenantId("km");
        task.setUserId("u-1");
        task.setTraceId(TRACE_ID);
        task.setStatus(status.name());
        task.setRetryCount(1);
        task.setMaxRetry(3);
        return task;
    }
}
