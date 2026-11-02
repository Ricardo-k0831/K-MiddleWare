package com.km.task.heartbeat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 探活结论的判定边界。
 *
 * <p>这个类只有一个职责, 但它是整个心跳兜底的"判死开关" ——
 * 把 UNKNOWN 误判成 NOT_ALIVE, 一条还在跑的任务就会被关掉。
 * 所以每个用例都在钉同一件事: <b>什么情况才有资格说"死了"</b>。
 */
class HeartbeatProbeTest {

    private MockRestServiceServer backend;
    private HeartbeatProbe probe;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://python.test");
        backend = MockRestServiceServer.bindTo(builder).build();
        probe = new HeartbeatProbe(builder.build());
    }

    @Test
    void 明确回答alive_true时判定为活着() {
        backend.expect(requestTo("http://python.test/tasks/trace-1/alive"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"alive\":true}", MediaType.APPLICATION_JSON));

        assertEquals(HeartbeatProbe.Verdict.ALIVE, probe.probe("trace-1"));
        backend.verify();
    }

    @Test
    void 明确回答alive_false时判定为没在跑() {
        backend.expect(requestTo("http://python.test/tasks/trace-1/alive"))
                .andRespond(withSuccess("{\"alive\":false}", MediaType.APPLICATION_JSON));

        assertEquals(HeartbeatProbe.Verdict.NOT_ALIVE, probe.probe("trace-1"));
    }

    @Test
    void 响应里没有布尔alive字段时是未知不是死亡() {
        // 协议没对上 (字段名改了 / 响应体是别的形状) —— 不能按 false 处理,
        // 否则一次接口改动会把所有在跑任务批量判死。
        backend.expect(requestTo("http://python.test/tasks/trace-1/alive"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        assertEquals(HeartbeatProbe.Verdict.UNKNOWN, probe.probe("trace-1"));
    }

    @Test
    void HTTP404时是未知不是死亡() {
        // 最常见的原因是 Python 还是旧版本, 没有这个接口。把它当成
        // "没有这条执行" = 升级不同步时全量误杀。
        backend.expect(requestTo("http://python.test/tasks/trace-1/alive"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertEquals(HeartbeatProbe.Verdict.UNKNOWN, probe.probe("trace-1"));
    }

    @Test
    void HTTP500时是未知() {
        backend.expect(requestTo("http://python.test/tasks/trace-1/alive"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(HeartbeatProbe.Verdict.UNKNOWN, probe.probe("trace-1"));
    }

    @Test
    void 连不上时是未知() {
        backend.expect(requestTo("http://python.test/tasks/trace-1/alive"))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        assertEquals(HeartbeatProbe.Verdict.UNKNOWN, probe.probe("trace-1"));
    }
}
