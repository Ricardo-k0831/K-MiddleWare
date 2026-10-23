package com.km.task.web;

import com.km.audit.AuditEvent;
import com.km.audit.AuditEventType;
import com.km.audit.AuditLayer;
import com.km.audit.AuditProperties;
import com.km.audit.AuditPublisher;
import com.km.task.audit.AuditIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提交入口的 HTTP 边界测试 —— JSON 进来到信封出去, 中间那一截。
 *
 * <p>用 standalone MockMvc, 不起 Spring 上下文: 这里没有 bean 装配要验
 * (那是 @SpringBootTest 的事), 要验的是<b>报文形状和状态码</b> —— 而这
 * 恰恰是跨语言契约里最容易悄悄烂掉的一层。Python 那边拼错一个字段名,
 * 表现不是报错, 而是那一列永远是 null。
 *
 * <p>所以这个类里的断言分两种:
 * <ul>
 *   <li><b>形状</b>: 字段名就是信封上的名字, 多一个少一个都不行</li>
 *   <li><b>边界</b>: 坏报文要在门口被挡掉, 而不是变成一条谁也不认识的审计</li>
 * </ul>
 */
class AuditControllerTest {

    private MockMvc mvc;
    private RecordingPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        mvc = MockMvcBuilders
                .standaloneSetup(new AuditController(new AuditIngestService(publisher)))
                .build();
    }

    // ------------------------------------------------------------------
    //  正常路径
    // ------------------------------------------------------------------

    @Test
    void 批量提交返回202和真实计数() throws Exception {
        mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"layer":"AGENT_RUNTIME","eventType":"LLM_CALL",
                                   "taskId":"task-1","traceId":"trace-1",
                                   "status":"OK","latencyMs":1840,"tokenIn":812,"tokenOut":96},
                                  {"layer":"AGENT_RUNTIME","eventType":"LLM_CALL",
                                   "taskId":"task-2","traceId":"trace-2","status":"OK"}
                                ]
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.received").value(2));

        assertEquals(2, publisher.all().size());
    }

    @Test
    void 字段名和信封一一对应_一个都不许漏在映射上() throws Exception {
        // Python 侧把这个报文拼出来, 拼错一个键名的后果不是 400, 而是那一列
        // 永远是 null —— 而且看起来完全正常。所以这里逐个字段钉死。
        mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"layer":"AGENT_RUNTIME","eventType":"LLM_CALL",
                                   "taskId":"task-1","traceId":"trace-1","tenantId":"km",
                                   "actor":"u-1","status":"OK","latencyMs":1840,
                                   "tokenIn":812,"tokenOut":96,
                                   "requestBody":{"model":"deepseek-chat"},
                                   "responseBody":{"finish_reason":"stop"},
                                   "occurredAt":1760460812345,
                                   "eventId":"evt-1"}
                                ]
                                """))
                .andExpect(status().isAccepted());

        AuditEvent event = publisher.single();
        assertEquals(AuditLayer.AGENT_RUNTIME, event.layer());
        assertEquals(AuditEventType.LLM_CALL, event.eventType());
        assertEquals("task-1", event.taskId());
        assertEquals("trace-1", event.traceId());
        assertEquals("km", event.tenantId());
        assertEquals("u-1", event.actor());
        assertEquals("OK", event.status());
        assertEquals(Integer.valueOf(1840), event.latencyMs());
        assertEquals(Integer.valueOf(812), event.tokenIn());
        assertEquals(Integer.valueOf(96), event.tokenOut());
        assertEquals(Map.of("model", "deepseek-chat"), event.requestBody());
        assertEquals(Map.of("finish_reason", "stop"), event.responseBody());
        assertEquals(1_760_460_812_345L, event.occurredAt());
        // 生产者自报的 ID 必须原样穿过 HTTP 边界 —— 这是重发去重的前提
        assertEquals("evt-1", event.eventId());
    }

    @Test
    void status没给时按OK落下去() throws Exception {
        mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"layer":"AGENT_RUNTIME","eventType":"LLM_CALL","taskId":"task-1"}]
                                """))
                .andExpect(status().isAccepted());

        assertEquals(AuditEvent.OK, publisher.single().status());
    }

    @Test
    void 空数组是正常轮询不是错误() throws Exception {
        // Python 的 worker 醒来时队列可能是空的。回 400 的话, 一个正常的
        // 空轮询会在日志里长得和"提交失败"一模一样。
        mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.received").value(0));

        assertEquals(0, publisher.all().size());
    }

    @Test
    void 没有taskId的匿名事件也能提交() throws Exception {
        mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"layer":"AGENT_RUNTIME","eventType":"LLM_CALL","status":"ERROR",
                                  "errorMsg":"额度超限"}]
                                """))
                .andExpect(status().isAccepted());

        AuditEvent event = publisher.single();
        assertNull(event.taskId());
        assertEquals("额度超限", event.errorMsg());
    }

    // ------------------------------------------------------------------
    //  坏报文
    // ------------------------------------------------------------------

    @Test
    void 认不出的layer应当在门口被挡掉() throws Exception {
        // 枚举反序列化失败 -> HttpMessageNotReadableException -> 400。
        // 这条比 @NotNull 那条重要: 它挡的是"Python 拼错了一个枚举名",
        // 而这种错如果放过, 落库的 layer 是个谁也不认识的值, 按层统计就废了。
        mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"layer":"AGENT_RUNTIM","eventType":"LLM_CALL","taskId":"task-1"}]
                                """))
                .andExpect(status().isBadRequest());

        assertEquals(0, publisher.all().size());
    }

    @Test
    void 缺layer时也应当在门口被挡掉而不是悄悄落一条废数据() throws Exception {
        assertBadRequestOfAnyKind("""
                [{"eventType":"LLM_CALL","taskId":"task-1"}]
                """);
    }

    @Test
    void 缺eventType时同样被挡掉() throws Exception {
        assertBadRequestOfAnyKind("""
                [{"layer":"AGENT_RUNTIME","taskId":"task-1"}]
                """);
    }

    @Test
    void 显式传null的layer不能悄悄放过去() throws Exception {
        // ★ 这条在验一个具体的机制, 不是重复上面那条 ★
        //
        // 「字段缺失」和「字段是 null」在反序列化阶段是两回事: 前者有可能在
        // 读 JSON 那一步就被拒 (HttpMessageNotReadableException), 而显式 null
        // 一定活得下来, 只能靠 @NotNull 拦。所以坏报文那几条测试里, 只有这条
        // 能证明【元素级校验真的生效了】。
        //
        // 实测结果 (Spring Framework 7 + Hibernate Validator 9): 抛的是
        // HandlerMethodValidationException —— 也就是说拦住它的是 Spring 的
        // 【处理方法的参数校验】, 不是 RequestBody 那套 MethodArgumentNotValidException。
        // @Valid 放在 List 参数上, 元素上的约束确实会被级联到, 不需要写成
        // List<@Valid AuditEventRequest>。这个结论光看代码看不出来, 所以钉在这里。
        //
        // 如果哪天它开始返回 202 并且被投递出去, 说明校验链路断了 —— 症状是
        // 库里出现 layer 为 null 的行, 按层统计从此不准, 而且没有任何东西会报警。
        MvcResult result = mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"layer":null,"eventType":"LLM_CALL","taskId":"task-1"}]
                                """))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInstanceOf(HandlerMethodValidationException.class, result.getResolvedException(),
                "@NotNull 没有被级联到 List 元素上 —— layer 为 null 的事件会悄悄落库");
        assertEquals(0, publisher.all().size());
    }

    /**
     * 坏报文必须是 400, 走反序列化还是走校验不重要 —— 不该进业务就行。
     *
     * <p><b>注意这里是整批拒绝, 不是逐条跳过。</b>校验在 HTTP 边界上是全有
     * 全无的: 50 条里有 1 条 layer 为 null, 整批 400, 那 49 条好的一起没了。
     * 这是有意的 —— 生产者是我们自己的 Python 代码, 报文是机器拼的, 出现
     * 一条畸形几乎必然意味着拼装逻辑坏了, 那么剩下 49 条同样不可信。反过来
     * "悄悄丢掉 1 条、照常收下 49 条"才是真正的危险: 丢的那条没人会知道。
     *
     * <p>真正的容错在 {@code AuditIngestService} 里 —— 那一层挡的是校验看不见
     * 的错 (映射代码自己的 bug), 两条防线管的是两类问题, 不重复。
     */
    private void assertBadRequestOfAnyKind(String json) throws Exception {
        MvcResult result = mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertTrue(
                result.getResolvedException() instanceof HttpMessageNotReadableException
                        || result.getResolvedException() instanceof HandlerMethodValidationException,
                "400 了, 但不是「读不出来」或「校验不过」中的任何一种, 实际: "
                        + result.getResolvedException());
        assertEquals(0, publisher.all().size(), "坏报文不该有任何一条被投递出去");
    }

    @Test
    void 不是数组的请求体被挡掉() throws Exception {
        // 契约是"数组", 单发一个对象应当是 400。Python 侧如果发成单个对象,
        // 这条会立刻告诉他, 而不是让他对着一个 202 猜为什么库里没有数据。
        mvc.perform(post("/api/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"layer":"AGENT_RUNTIME","eventType":"LLM_CALL"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

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

        AuditEvent single() {
            assertEquals(1, captured.size(), "期望恰好投出一条, 实际 " + captured.size());
            return captured.get(0);
        }
    }
}
