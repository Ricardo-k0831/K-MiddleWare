package com.km.task.retry;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.km.task.domain.AgentTask;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.error.RetryNotAllowedException;
import com.km.task.mapper.AgentTaskMapper;
import com.km.task.service.TaskService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 扫描器只负责"谁到期了" —— 校验仍在 TaskService 里。
 *
 * <p>所以这里的断言集中在两件事: <b>该不该发起</b> (退避/次数/租户) 和
 * <b>发起了之后有没有派发</b>。规则本身 (状态机、CAS) 在 TaskServiceTest。
 */
class RetrySchedulerTest {

    private static final String TASK_ID = "task-1";

    private AgentTaskMapper taskMapper;
    private TaskService taskService;
    private RetryDispatcher dispatcher;
    private RetryProperties properties;
    private RetryScheduler scheduler;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, AgentTask.class);
    }

    @BeforeEach
    void setUp() {
        taskMapper = mock(AgentTaskMapper.class);
        taskService = mock(TaskService.class);
        dispatcher = mock(RetryDispatcher.class);
        properties = new RetryProperties();
        properties.getScheduler().setBaseBackoffSeconds(30);
        properties.getScheduler().setMaxBackoffSeconds(300);
        properties.getScheduler().setBatchSize(20);
        scheduler = new RetryScheduler(taskMapper, taskService, dispatcher, properties);
    }

    @Test
    void 退避未到期时不重试() {
        when(taskMapper.selectList(any())).thenReturn(List.of(
                failedTask(TASK_ID, 0, 3, LocalDateTime.now().minusSeconds(10))));

        scheduler.sweep();

        verifyNoInteractions(taskService, dispatcher);
    }

    @Test
    void 到期后开新链并派发() {
        AgentTask candidate = failedTask(TASK_ID, 0, 3, LocalDateTime.now().minusSeconds(31));
        AgentTask opened = openedTask("trace-new");
        when(taskMapper.selectList(any())).thenReturn(List.of(candidate));
        when(taskService.retry(TASK_ID, RetryScheduler.AUTO_RETRY_REASON,
                TaskEventSource.SYSTEM_RETRY)).thenReturn(opened);

        scheduler.sweep();

        // ★ 自动重试必须用 SYSTEM_RETRY ★ 否则审计里分不清"人点的"和"系统救的"
        verify(taskService).retry(TASK_ID, RetryScheduler.AUTO_RETRY_REASON,
                TaskEventSource.SYSTEM_RETRY);
        // 派发的是 retry() 返回的对象 —— 它带着新 trace
        verify(dispatcher).dispatch(opened);
    }

    @Test
    void 重试次数越多等得越久() {
        // retryCount=1 → 等 60s。50s 前失败的那条还没到期, 61s 前的到期。
        AgentTask notDue = failedTask("task-not-due", 1, 5,
                LocalDateTime.now().minusSeconds(50));
        AgentTask due = failedTask("task-due", 1, 5,
                LocalDateTime.now().minusSeconds(61));
        when(taskMapper.selectList(any())).thenReturn(List.of(notDue, due));
        when(taskService.retry(eq("task-due"), anyString(), any())).thenReturn(
                openedTask("trace-due"));

        scheduler.sweep();

        verify(taskService, never()).retry(eq("task-not-due"), anyString(), any());
        verify(taskService).retry(eq("task-due"), anyString(), any());
        verify(dispatcher).dispatch(any());
    }

    @Test
    void 业务拒绝时跳过且不影响同轮其他任务() {
        AgentTask first = failedTask("task-1", 0, 3, LocalDateTime.now().minusSeconds(120));
        AgentTask second = failedTask("task-2", 0, 3, LocalDateTime.now().minusSeconds(120));
        when(taskMapper.selectList(any())).thenReturn(List.of(first, second));
        // 第一条在"查到"和"重试"之间被人工抢先了
        when(taskService.retry(eq("task-1"), anyString(), any()))
                .thenThrow(new RetryNotAllowedException("task-1", "并发冲突"));
        AgentTask opened = openedTask("trace-2");
        when(taskService.retry(eq("task-2"), anyString(), any())).thenReturn(opened);

        assertDoesNotThrow(() -> scheduler.sweep());

        verify(dispatcher).dispatch(opened);
    }

    @Test
    void 查询失败时本轮放弃而不抛出() {
        when(taskMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> scheduler.sweep());

        verifyNoInteractions(taskService, dispatcher);
    }

    @Test
    void 配了租户时只查该租户() {
        properties.getScheduler().setTenant("retry-e2e");
        when(taskMapper.selectList(any())).thenReturn(List.of());

        scheduler.sweep();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AgentTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectList(captor.capture());
        LambdaQueryWrapper<AgentTask> query = (LambdaQueryWrapper<AgentTask>) captor.getValue();
        assertTrue(query.getSqlSegment().contains("tenant_id"));
        assertTrue(query.getParamNameValuePairs().containsValue("retry-e2e"));
    }

    @Test
    void 没配租户时不加租户条件() {
        when(taskMapper.selectList(any())).thenReturn(List.of());

        scheduler.sweep();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AgentTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper).selectList(captor.capture());
        assertFalse(captor.getValue().getSqlSegment().contains("tenant_id"));
    }

    @Test
    void 退避按2的幂增长并封顶() {
        assertEquals(30, RetryScheduler.backoffSeconds(0, 30, 300));
        assertEquals(60, RetryScheduler.backoffSeconds(1, 30, 300));
        assertEquals(120, RetryScheduler.backoffSeconds(2, 30, 300));
        assertEquals(240, RetryScheduler.backoffSeconds(3, 30, 300));
        assertEquals(300, RetryScheduler.backoffSeconds(4, 30, 300));
        // 次数大到会溢出也不该崩, 直接给上限
        assertEquals(300, RetryScheduler.backoffSeconds(40, 30, 300));
        // max 配得比 base 还小: 按 base 算, 不能让间隔变成 0
        assertEquals(30, RetryScheduler.backoffSeconds(5, 30, 10));
    }

    private static AgentTask failedTask(String id, int retryCount, int maxRetry,
                                        LocalDateTime finishedAt) {
        AgentTask task = new AgentTask();
        task.setAgentTaskId(id);
        task.setStatus(TaskStatus.FAILED.name());
        task.setTraceId("trace-old-" + id);
        task.setRetryCount(retryCount);
        task.setMaxRetry(maxRetry);
        task.setFinishedAt(finishedAt);
        task.setIntent("查订单");
        task.setTenantId("km");
        return task;
    }

    private static AgentTask openedTask(String traceId) {
        AgentTask task = new AgentTask();
        task.setAgentTaskId(TASK_ID);
        task.setStatus(TaskStatus.PENDING.name());
        task.setTraceId(traceId);
        task.setIntent("查订单");
        return task;
    }
}
