package com.km.task.retry;

import com.km.task.domain.AgentTask;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.service.TaskService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
                // thread_id 仍给 task_id —— Python 只在 session_id 缺失时才用它
                .andExpect(jsonPath("$.thread_id").value("task-1"))
                // session_id 才是会话线程的输入: 重试落回同一个会话线程,
                // 既保住会话记忆, 又靠 metadata.task_id 只续自己那一遍
                .andExpect(jsonPath("$.session_id").value("session-1"))
                .andExpect(jsonPath("$.tenant_id").value("km"))
                .andExpect(jsonPath("$.actor").value("U1001"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        dispatcher().dispatch(task());

        backend.verify();
        // 派发前必须先留下"我派发过"的证据, 否则心跳兜底扫描永远看不到这次执行。
        verify(taskService).markDispatched("task-1", "trace-new");
        // 派发成功不代表任务成功: 结果由 Python 的 PATCH 上报, 这里不写状态。
        verify(taskService, never()).reportStatus(any(), any(), any(), any(), anyMap());
    }

    @Test
    void 连接失败时把这次执行标记为FAILED() {
        backend.expect(requestTo("http://python.test/chat"))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        dispatcher().dispatch(task());

        verify(taskService).markDispatched("task-1", "trace-new");
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

    /**
     * 真实客户端必须是 HTTP/1.1。
     *
     * <p>E2E 实测 (2026-11-04): 自动重试派发到 Python 全部 422 (FastAPI 说
     * "body 缺失"), 而同一时刻 curl 直连正常。uvicorn 日志是
     * {@code Unsupported upgrade request} + {@code Invalid HTTP request received}:
     * JDK HttpClient 默认 HTTP_2, 对明文 http:// 会先发 h2c 升级, uvicorn 不
     * 支持这种升级并丢掉请求体。所以这里用裸 socket 抓请求头, 只断言一件事:
     * <b>不许出现 Upgrade</b>。这跟服务端用什么实现无关, 是客户端侧的约束。
     */
    @Test
    void 真实客户端不发h2c升级() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            AtomicReference<String> head = new AtomicReference<>();
            Thread probe = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    InputStream in = socket.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    int b;
                    while ((b = in.read()) != -1) {
                        buf.write(b);
                        // 只需要请求头。请求体可能是 chunked, 读到这里就够了。
                        if (buf.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                            break;
                        }
                    }
                    head.set(buf.toString(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // 客户端提前关连接不影响断言 —— 我们要的是它发出去的请求头。
                }
            }, "h2c-probe");
            probe.setDaemon(true);
            probe.start();

            RetryProperties.Dispatch config = new RetryProperties.Dispatch();
            config.setChatUrl("http://127.0.0.1:" + server.getLocalPort());
            new RetryDispatcher(RetryDispatcher.buildClient(RestClient.builder(), config),
                    taskService, Runnable::run).dispatch(task());

            probe.join(5_000);
            assertNotNull(head.get(), "客户端没有发出请求");
            assertFalse(head.get().toLowerCase().contains("upgrade"),
                    "不能发 h2c 升级: uvicorn 会丢掉请求体\n" + head.get());
        }
    }

    private static AgentTask task() {
        AgentTask task = new AgentTask();
        task.setAgentTaskId("task-1");
        task.setTraceId("trace-new");
        task.setStatus(TaskStatus.PENDING.name());
        task.setIntent("查一下订单 SO1");
        task.setSessionId("session-1");
        task.setTenantId("km");
        task.setUserId("U1001");
        return task;
    }
}
