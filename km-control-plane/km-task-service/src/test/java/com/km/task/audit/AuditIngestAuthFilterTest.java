package com.km.task.audit;

import com.km.audit.AuditEvent;
import com.km.audit.AuditProperties;
import com.km.audit.AuditPublisher;
import com.km.task.web.AuditController;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计入口认证的边界测试。
 *
 * <p>这个类只回答一件事: <b>没有 token 的请求能不能碰到业务代码</b>。
 * 答案是"碰不到"—— 所以断言除了 401, 还要断言投递器收到 0 条:
 * 如果只断言状态码, 一个"先写库再返回 401"的实现也能通过,
 * 而那正是审计伪造最想要的结果。
 */
class AuditIngestAuthFilterTest {

    private static final String TOKEN = "test-token";
    private static final String BODY = """
            [{"layer":"AGENT_RUNTIME","eventType":"LLM_CALL"}]
            """;

    @Test
    void 没有token时返回401且事件不进入业务() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        MockMvc mvc = mvcWith(TOKEN, publisher);

        mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        assertEquals(0, publisher.all().size(), "被认证挡掉的请求不该有任何一条被投递");
    }

    @Test
    void token错了也是401() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        MockMvc mvc = mvcWith(TOKEN, publisher);

        mvc.perform(post("/api/audit/events")
                        .header("X-KM-Audit-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        assertEquals(0, publisher.all().size());
    }

    @Test
    void token正确时正常进入业务() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        MockMvc mvc = mvcWith(TOKEN, publisher);

        mvc.perform(post("/api/audit/events")
                        .header("X-KM-Audit-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted());

        assertEquals(1, publisher.all().size());
    }

    @Test
    void 只拦审计路径_别的接口不受影响() throws Exception {
        // 过滤器是 @Component, Boot 会把它挂到所有请求上。如果 shouldNotFilter
        // 写错, 整个控制面的接口都会开始要一个审计 token —— 症状是
        // /api/tasks 全 401, 而原因藏在审计模块里, 非常难查。
        MockMvc mvc = mvcWith(TOKEN, new RecordingPublisher());

        mvc.perform(get("/api/tasks/task-1"))
                .andExpect(status().isNotFound());   // 没有这个映射 -> 404, 不是 401
    }

    @Test
    void 配置为空时关闭校验() throws Exception {
        // 空 = 明确关闭, 只应在本地开发时这样配。关闭时也要真的能进来,
        // 否则"关掉认证"就成了"服务不可用"。
        RecordingPublisher publisher = new RecordingPublisher();
        MockMvc mvc = mvcWith("", publisher);

        mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted());

        assertEquals(1, publisher.all().size());
    }

    private static MockMvc mvcWith(String configuredToken, RecordingPublisher publisher) {
        AuditIngestProperties properties = new AuditIngestProperties();
        properties.setToken(configuredToken);

        AuditIngestService service = new AuditIngestService(
                publisher, traceId -> null, properties);
        return MockMvcBuilders
                .standaloneSetup(new AuditController(service))
                .addFilters(new AuditIngestAuthFilter(properties))
                .build();
    }

    /** 只把事件收进内存的投递器, 替掉 RocketMQ。 */
    private static final class RecordingPublisher extends AuditPublisher {

        private final List<AuditEvent> captured = new ArrayList<>();

        RecordingPublisher() {
            super(new AuditProperties(), JsonMapper.builder().build());
        }

        @Override
        public void publish(AuditEvent event) {
            captured.add(event);
        }

        List<AuditEvent> all() {
            return captured;
        }
    }
}
