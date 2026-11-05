package com.km.task.cancel;

import com.km.task.domain.AgentTask;
import com.km.task.web.dto.CancelTaskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 通知 Python 取消: 发什么、怎么读结果、失败时怎么表现。
 *
 * <p>这一层全部是"尽力而为"的语义, 所以断言的重点不是"成功", 而是
 * <b>失败也绝不抛异常</b> —— 抛出去会让一个已经生效的取消变成 500。
 */
class PythonCancelNotifierTest {

    private MockRestServiceServer backend;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://python.test");
        backend = MockRestServiceServer.bindTo(builder).build();
    }

    private PythonCancelNotifier notifier() {
        return new PythonCancelNotifier(builder.build());
    }

    @Test
    void 请求体带齐定位线程的身份() {
        backend.expect(requestTo("http://python.test/tasks/task-1/cancel"))
                .andExpect(method(HttpMethod.POST))
                // session_id / tenant_id 是 Python 算线程的输入; 少了 session_id
                // 它会去收尾任务线程, 而运行其实在会话线程上。
                .andExpect(jsonPath("$.session_id").value("s-1"))
                .andExpect(jsonPath("$.tenant_id").value("km"))
                .andExpect(jsonPath("$.trace_id").value("trace-1"))
                .andExpect(jsonPath("$.reason").value("用户改主意了"))
                .andRespond(withSuccess(
                        "{\"stopped\":true,\"released\":true,\"thread_id\":\"session:km:s-1\"}",
                        MediaType.APPLICATION_JSON));

        CancelTaskResponse.RuntimeStop result = notifier().notify(task(), "用户改主意了");

        backend.verify();
        assertTrue(result.reached());
        assertTrue(result.stopped());
        assertTrue(result.released());
        assertEquals("session:km:s-1", result.threadId());
        assertNull(result.error());
    }

    @Test
    void 没有session时请求体里不出现空串() {
        AgentTask task = task();
        task.setSessionId(null);
        backend.expect(requestTo("http://python.test/tasks/task-1/cancel"))
                // 空值字段压根不传: 传 "" 会被 Python 当成"真的有个空会话",
                // 线程名会变成 session:km:, 收尾打在一个不存在的线程上。
                .andExpect(jsonPath("$.session_id").doesNotHaveJsonPath())
                .andRespond(withSuccess(
                        "{\"stopped\":false,\"released\":false}", MediaType.APPLICATION_JSON));

        CancelTaskResponse.RuntimeStop result = notifier().notify(task, null);

        assertTrue(result.reached());
        assertFalse(result.stopped());
        assertNull(result.threadId());
    }

    @Test
    void 连不上Python时返回未到达而不是抛异常() {
        backend.expect(requestTo("http://python.test/tasks/task-1/cancel"))
                .andRespond(request -> {
                    throw new ConnectException("Connection refused");
                });

        CancelTaskResponse.RuntimeStop result = notifier().notify(task(), null);

        assertFalse(result.reached());
        assertFalse(result.stopped());
        assertFalse(result.released());
        assertTrue(result.error().contains("Connection refused"),
                "错误原因要能看出是网络问题: " + result.error());
    }

    @Test
    void Python返回500时同样不抛异常() {
        backend.expect(requestTo("http://python.test/tasks/task-1/cancel"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        CancelTaskResponse.RuntimeStop result = notifier().notify(task(), null);

        assertFalse(result.reached(), "非 2xx 视为没完成通知");
        assertFalse(result.released());
    }

    private static AgentTask task() {
        AgentTask task = new AgentTask();
        task.setAgentTaskId("task-1");
        task.setTraceId("trace-1");
        task.setTenantId("km");
        task.setSessionId("s-1");
        task.setUserId("u-1");
        task.setStatus("CANCELLED");
        return task;
    }
}
