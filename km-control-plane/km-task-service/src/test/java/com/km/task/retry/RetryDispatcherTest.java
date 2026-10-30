package com.km.task.retry;

import com.km.task.domain.AgentTask;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 派发语义: 发什么、失败时哪些情况可以关链、哪些不能。
 *
 * <p>用 {@link MockRestServiceServer} 假掉 Python, executor 用"当前线程直接跑",
 * 让 dispatch() 变成同步调用 —— 否则断言要在另一个线程里等, 测的是时序不是逻辑。
 */
class RetryDispatcherTest {

    private TaskService taskService;
    private MockRestServiceServer backend;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        builder = RestClient.builder().baseUrl("http://python.test");
        backend = MockRestServiceServer.bindTo(builder).build();
    }

    private RetryDispatcher dispatcher() {
        return new RetryDispatcher(builder.build(), taskService, Runnable::run);
    }

    @Test
    void 请求体带齐任务身份与原始意图() {
        backend.expect(requestTo("http://python.test/chat"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.message").value("查一下订单 SO1"))
                .andExpect(jsonPath("$.task_id").value("task-1"))
                // 用新链的 trace, 不是上一轮失败的 trace
                .andExpect(jsonPath("$.trace_id").value("trace-new"))
                // thread_id 跟 task_id 走: 一次执行一个独立会话, 不继承上一遍上下文
                .andExpect(jsonPath("$.thread_id").value("task-1"))
                .andExpect(jsonPath("$.tenant_id").value("km"))
                .andExpect(jsonPath("$.actor").value("U1001"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        dispatcher().dispatch(task());

        backend.verify();
        // 派发成功不代表任务成功: 结果由 Python 的 PATCH 上报, 这里不写状态。
        verifyNoInteractions(taskService);
    }

    @Test
    void 连接失败时把这次执行标记为FAILED() {
        backend.expect(requestTo("http://python.test/chat"))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        dispatcher().dispatch(task());

        verify(taskService).reportStatus(eq("task-1"), eq("trace-new"),
                eq(TaskStatus.FAILED), eq(TaskEventSource.SYSTEM_RETRY),
                argThat(detail -> "DISPATCH".equals(detail.get("phase"))
                        && String.valueOf(detail.get("error")).contains("Connection refused")));
    }

    @Test
    void HTTP500时不关链() {
        backend.expect(requestTo("http://python.test/chat"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        dispatcher().dispatch(task());

        // 请求可能已经在 Python 里执行了, 关链会造成"任务失败但 Python 还在写上报"。
        verify(taskService, never()).reportStatus(any(), any(), any(), any(), anyMap());
    }

    @Test
    void 线程池满时标记失败() {
        // 队列满 = 请求根本没发出去, 不关链任务会永远停在 PENDING。
        RetryDispatcher rejected = new RetryDispatcher(builder.build(), taskService,
                command -> {
                    throw new RejectedExecutionException("queue full");
                });

        rejected.dispatch(task());

        verify(taskService).reportStatus(eq("task-1"), eq("trace-new"),
                eq(TaskStatus.FAILED), eq(TaskEventSource.SYSTEM_RETRY),
                argThat(detail -> String.valueOf(detail.get("error")).contains("队列已满")));
    }

    @Test
    void 只有连接阶段的异常算没到达Python() {
        assertTrue(RetryDispatcher.neverReachedPython(
                new IllegalStateException(new ConnectException("refused"))));
        assertTrue(RetryDispatcher.neverReachedPython(
                new IllegalStateException(new java.net.UnknownHostException("python"))));
        assertTrue(RetryDispatcher.neverReachedPython(
                new java.net.http.HttpConnectTimeoutException("connect timed out")));
        // 读超时: 请求已经发出去了, 不能当成"没到达"。
        assertFalse(RetryDispatcher.neverReachedPython(
                new IllegalStateException(new SocketTimeoutException("read timed out"))));
        assertFalse(RetryDispatcher.neverReachedPython(new IllegalStateException("boom")));
    }

    private static AgentTask task() {
        AgentTask task = new AgentTask();
        task.setAgentTaskId("task-1");
        task.setTraceId("trace-new");
        task.setStatus(TaskStatus.PENDING.name());
        task.setIntent("查一下订单 SO1");
        task.setTenantId("km");
        task.setUserId("U1001");
        return task;
    }
}
