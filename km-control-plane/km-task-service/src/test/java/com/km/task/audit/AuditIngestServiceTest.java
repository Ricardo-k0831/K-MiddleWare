package com.km.task.audit;

import com.km.audit.AuditEvent;
import com.km.audit.AuditEventType;
import com.km.audit.AuditLayer;
import com.km.audit.AuditProperties;
import com.km.audit.AuditPublisher;
import com.km.task.web.dto.AuditEventRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提交入口的映射与身份核对测试 —— Python 发过来的报文, 落到信封上是不是同一个意思,
 * 以及它声称的执行身份能不能被信任。
 *
 * <p>没有 @SpringBootTest, 也不连 broker / 数据库: 假投递器替掉了 RocketMQ,
 * {@link StubLookup} 替掉了 attempt 表。能在这几毫秒内跑完的规则测试,
 * 不该被拖去起一个 Spring 上下文。
 *
 * <p>真正的投递行为 (序列化、采样、发 MQ) 由 km-audit-core 的
 * {@code AuditPublisherTest} 覆盖; 缓存的行为由 {@code CachedAttemptLookupTest}
 * 覆盖; HTTP 边界由 {@code AuditControllerTest} 覆盖。这里只回答三个问题:
 * <b>字段有没有丢在映射上</b>、<b>身份矛盾会不会被放进来</b>、
 * <b>数据库查不动时会不会误伤审计</b>。
 */
class AuditIngestServiceTest {

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final StubLookup lookup = new StubLookup();
    private final AuditIngestProperties properties = new AuditIngestProperties();
    private final AuditIngestService service = new AuditIngestService(publisher, lookup, properties);

    @BeforeEach
    void setUp() {
        // 大多数用例的夹具是 task-1 / trace-1, 默认让它核对通过 ——
        // 这样"字段映射"那组测试不会顺带把身份规则也搅进来。
        lookup.with("trace-1", "task-1", null);
    }

    // ------------------------------------------------------------------
    //  字段映射
    // ------------------------------------------------------------------

    @Test
    void 每个字段都原样落到信封上() {
        AuditIngestService.IngestResult result = service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME,
                AuditEventType.LLM_CALL,
                "task-1", "trace-1", "km", "u-1",
                "OK", 1840, 812, 96,
                Map.of("model", "deepseek-chat"),
                Map.of("finish_reason", "stop"),
                null,
                1_760_460_812_345L,
                "evt-fixed")));

        assertEquals(1, result.accepted());
        assertEquals(0, result.unverified(), "身份核对通过的事件不该被标成未核实");

        AuditEvent event = publisher.single();
        assertEquals(AuditLayer.AGENT_RUNTIME, event.layer());
        assertEquals(AuditEventType.LLM_CALL, event.eventType());
        assertEquals("task-1", event.taskId());
        assertEquals("trace-1", event.traceId());
        assertEquals("km", event.tenantId());
        assertEquals("u-1", event.actor());
        assertEquals(AuditEvent.OK, event.status());
        assertEquals(Integer.valueOf(1840), event.latencyMs());
        assertEquals(Integer.valueOf(812), event.tokenIn());
        assertEquals(Integer.valueOf(96), event.tokenOut());
        assertEquals(Map.of("model", "deepseek-chat"), event.requestBody());
        assertEquals(Map.of("finish_reason", "stop"), event.responseBody());
        assertNull(event.errorMsg());
        assertEquals(1_760_460_812_345L, event.occurredAt());
    }

    @Test
    void 没给eventId时服务端补一个非空的() {
        service.ingest(List.of(llmCall()));

        String id = publisher.single().eventId();
        assertNotNull(id);
        assertEquals(32, id.length(), "事件 ID 要和 taskId / traceId 同形 (32 位无横线 UUID)");
    }

    @Test
    void 两次生成的eventId不相同() {
        service.ingest(List.of(llmCall()));
        service.ingest(List.of(llmCall()));

        assertEquals(2, publisher.all().size());
        assertTrue(
                !publisher.all().get(0).eventId().equals(publisher.all().get(1).eventId()),
                "服务端补的 ID 撞了 —— 唯一键会把第二条真实事件当成重投丢掉");
    }

    @Test
    void 生产者给了eventId就必须原样用() {
        // ★ 这条是唯一键去重能跨过 HTTP 边界的前提 ★
        //
        // 生产者自己定 ID, 它重发时才有稳定的去重依据。这里如果改成
        // "服务端总是重新生成", 表现是重发一次就多一条重复审计 —— 而且
        // 看起来完全正常, 没有任何东西会报警。
        service.ingest(List.of(llmCall(null, "evt-from-python")));

        assertEquals("evt-from-python", publisher.single().eventId());
    }

    @Test
    void status缺省为OK() {
        service.ingest(List.of(llmCall()));
        assertEquals(AuditEvent.OK, publisher.single().status());
    }

    @Test
    void status是空串时也按缺省处理() {
        service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                "task-1", "trace-1", null, null, "   ", null, null, null,
                null, null, null, null, null)));

        assertEquals(AuditEvent.OK, publisher.single().status());
    }

    @Test
    void occurredAt为空时补上服务端当前时间() {
        long before = System.currentTimeMillis();
        service.ingest(List.of(llmCall()));

        long actual = publisher.single().occurredAt();
        assertTrue(actual >= before, "occurredAt 没有补上, 实际: " + actual);
    }

    @Test
    void 给了occurredAt就用给的_不被服务端时间覆盖() {
        // ★ 反向的那一半, 同样重要 ★
        //
        // 时间线一旦跨层比对就露馅: 网关那条如果比 Agent 那条还早, 看着像
        // 穿越。所以生产者说的发生时刻必须被尊重, 而不是"反正差不多就填现在"。
        service.ingest(List.of(llmCall(1_750_000_000_000L, null)));

        assertEquals(1_750_000_000_000L, publisher.single().occurredAt());
    }

    @Test
    void 允许没有taskId的匿名事件() {
        // 建任务之前就失败的调用, 或者将来某层的探活上报 —— 都是真实存在的
        // 事件。硬要求 taskId 的话, 这些要么被丢掉, 要么被塞一个假的。
        service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                null, null, null, null, "ERROR", null, null, null,
                null, null, "额度超限", null, null)));

        AuditEvent event = publisher.single();
        assertNull(event.taskId());
        assertEquals(AuditEvent.ERROR, event.status());
        assertEquals("额度超限", event.errorMsg());
    }

    // ------------------------------------------------------------------
    //  身份核对
    // ------------------------------------------------------------------

    @Test
    void taskId缺失时用执行记录补齐() {
        // ★ 这不是编造, 是从权威来源取 ★
        //
        // traceId 已经唯一确定了这条事件属于哪次执行, 而那次执行上写着它
        // 属于哪个任务。事件没带 taskId 时, 查表补上比留 null 更有用 ——
        // 留 null 的话, 这条记录在 audit_log 里按 task_id 查永远查不到。
        service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                null, "trace-1", null, null, "OK", null, null, null,
                null, null, null, null, null)));

        AuditEvent event = publisher.single();
        assertEquals("task-1", event.taskId());
        assertEquals("trace-1", event.traceId());
    }

    @Test
    void taskId与trace矛盾时一律拒收() {
        // ★ 这是整套规则里唯一没有"降级"方向的判断 ★
        //
        // 查不到可以是数据库抖动, 但"事件说它在 task-2, trace 说它属于
        // task-1"是两条身份互相矛盾 —— 放进去会同时污染两条链路, 而且
        // 事后无法判断哪一边是真的。默认 WARN 模式也必须拒。
        AuditIngestService.IngestResult result = service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                "task-2", "trace-1", null, null, "OK", null, null, null,
                null, null, null, null, null)));

        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(0, publisher.all().size());
    }

    @Test
    void 未知trace在WARN模式下放行并计入unverified() {
        // 默认模式: 宁可收下一条待核实的记录, 也不要因为一次查询失败丢掉整批。
        AuditIngestService.IngestResult result = service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                "task-x", "trace-unknown", null, null, "OK", null, null, null,
                null, null, null, null, null)));

        assertEquals(1, result.accepted());
        assertEquals(0, result.rejected());
        assertEquals(1, result.unverified());
        // 自报的 taskId 原样保留 —— WARN 模式只是"不核实", 不是"不信任"。
        assertEquals("task-x", publisher.single().taskId());
    }

    @Test
    void 未知trace在STRICT模式下拒收() {
        properties.setIdentityCheck(AuditIngestProperties.Mode.STRICT);

        AuditIngestService.IngestResult result = service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                "task-x", "trace-unknown", null, null, "OK", null, null, null,
                null, null, null, null, null)));

        assertEquals(0, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(0, publisher.all().size());
    }

    @Test
    void 查库失败时两种模式都放行_审计入口不额外依赖数据库() {
        // ★ 这条钉的是降级策略的核心论点 ★
        //
        // "查不动"和"查无"必须分开: 数据库一抖, STRICT 模式若把整批真实
        // 事件拒掉, 故障期间恰是最需要审计的时候, 却一条都记不下来。
        // 所以这里把模式调到最严格的 STRICT, 断言仍然放行。
        properties.setIdentityCheck(AuditIngestProperties.Mode.STRICT);
        lookup.failing("trace-1", new IllegalStateException("数据库连接池耗尽"));

        AuditIngestService.IngestResult result = service.ingest(List.of(llmCall()));

        assertEquals(1, result.accepted());
        assertEquals(0, result.rejected());
        assertEquals(1, result.unverified());
        assertEquals("task-1", publisher.single().taskId());
    }

    @Test
    void 没有trace的事件不查表_无从不等于未核实() {
        // "带了 trace 但没核对上"才叫 unverified。"无从核对"混进去的话,
        // 这个指标会被匿名事件永久顶高, 真正的问题就淹没在里面了。
        service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                "task-9", null, null, null, "OK", null, null, null,
                null, null, null, null, null)));

        assertEquals(0, lookup.queries(), "没有 traceId 还去查库, 纯属浪费一次往返");
        assertEquals(1, publisher.all().size());
    }

    @Test
    void 迟到事件仍然投递只是被标记() {
        // 迟到不等于假。执行闭链之后还在排队的事件是真实发生过的,
        // 丢掉它等于篡改历史; 但也不能当没发生过 —— 所以只计数。
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 1, 12, 0);
        lookup.with("trace-1", "task-1", endedAt);
        long afterClosure = endedAt.atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli() + 1_000;

        AuditIngestService.IngestResult result = service.ingest(List.of(llmCall(afterClosure, null)));

        assertEquals(1, result.accepted());
        assertEquals(1, result.late());
        assertEquals(1, publisher.all().size());
    }

    @Test
    void 闭链之前发生的事件不算迟到() {
        LocalDateTime endedAt = LocalDateTime.of(2026, 10, 1, 12, 0);
        lookup.with("trace-1", "task-1", endedAt);
        long beforeClosure = endedAt.atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli() - 1_000;

        AuditIngestService.IngestResult result = service.ingest(List.of(llmCall(beforeClosure, null)));

        assertEquals(0, result.late());
    }

    @Test
    void 计数之间可以互相验算() {
        // 派生指标如果各自独立累加, 迟早会出现"总数对不上"的情况,
        // 而那时没人知道该信哪一个。这条钉住它们的关系。
        AuditIngestService.IngestResult result = service.ingest(List.of(
                llmCall(),                                          // 核实通过
                new AuditEventRequest(AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                        "task-2", "trace-1", null, null, "OK", null, null, null,
                        null, null, null, null, null),              // 矛盾, 拒收
                new AuditEventRequest(AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                        "task-x", "trace-unknown", null, null, "OK", null, null, null,
                        null, null, null, null, null),              // 未知, 未核实
                new AuditEventRequest(null, AuditEventType.LLM_CALL,
                        "task-bad", null, null, null, null, null, null, null,
                        null, null, null, null, null)));            // 映射失败, 拒收

        assertEquals(4, result.received());
        assertEquals(2, result.accepted());
        assertEquals(2, result.rejected());
        assertEquals(result.received(), result.accepted() + result.rejected());
        assertEquals(1, result.unverified());
        assertTrue(result.unverified() <= result.accepted());
    }

    // ------------------------------------------------------------------
    //  容错
    // ------------------------------------------------------------------

    @Test
    void 单条坏掉不连坐整批() {
        // layer 为 null 时 AuditEvent.Builder 会抛 —— 正常路径上 DTO 校验
        // 会先挡住 (400), 但这里要保证即使漏进来了, 也只丢那一条。
        // 审计是旁路: 因为一条畸形数据把整批丢掉, 是"旁路反过来伤害主路"。
        AuditIngestService.IngestResult result = service.ingest(List.of(
                llmCall(),
                new AuditEventRequest(null, AuditEventType.LLM_CALL,
                        "task-bad", null, null, null, null, null, null, null,
                        null, null, null, null, null),
                llmCall()));

        assertEquals(2, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(2, publisher.all().size());
        // 丢的是哪一条也要说清楚 —— 只报"总共丢了 1 条", 事后没法知道是哪个任务。
        assertEquals("task-1", publisher.all().get(0).taskId());
        assertEquals("task-1", publisher.all().get(1).taskId());
    }

    @Test
    void 列表里的null条目被跳过而不是炸掉整批() {
        AuditIngestService.IngestResult result =
                service.ingest(java.util.Arrays.asList(llmCall(), null));

        assertEquals(1, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(1, publisher.all().size());
    }

    @Test
    void 空列表返回全零而不是抛异常() {
        // Python 的 worker 醒来时队列可能是空的 —— 那是一次正常轮询, 不是错误。
        AuditIngestService.IngestResult result = service.ingest(List.of());

        assertEquals(AuditIngestService.IngestResult.EMPTY, result);
        assertEquals(0, lookup.queries());
    }

    @Test
    void null当作没有() {
        assertEquals(AuditIngestService.IngestResult.EMPTY, service.ingest(null));
    }

    @Test
    void 返回的是真正投出去的条数() {
        AuditIngestService.IngestResult result =
                service.ingest(List.of(llmCall(), llmCall(), llmCall()));

        assertEquals(3, result.accepted());
        assertEquals(3, publisher.all().size());
    }

    @Test
    void 尾部空白的traceId不会绕过身份核对() {
        // " trace-1 " 如果原样去查, 查无; 如果原样落库, 那一列的 trace_id
        // 带着空格, 按 trace 拼链路就永远拼不上。两端都按 trim 后的值处理。
        service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                "task-1", "  trace-1  ", null, null, "OK", null, null, null,
                null, null, null, null, null)));

        AuditEvent event = publisher.single();
        assertEquals("trace-1", event.traceId());
        assertFalse(event.traceId().contains(" "));
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    private static AuditEventRequest llmCall() {
        return llmCall(null, null);
    }

    /** LLM_CALL 的最小报文, 只覆盖本类关心的字段。 */
    private static AuditEventRequest llmCall(Long occurredAt, String eventId) {
        return new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME, AuditEventType.LLM_CALL,
                "task-1", "trace-1", "km", "u-1",
                "OK", 100, 10, 5,
                Map.of("model", "deepseek-chat"),
                Map.of("finish_reason", "stop"),
                null, occurredAt, eventId);
    }

    /** attempt 表的替身。能表达三种状态: 查到 / 查无 / 查不动。 */
    private static final class StubLookup implements AttemptLookup {

        private final Map<String, AttemptRef> attempts = new HashMap<>();
        private final Map<String, RuntimeException> failures = new HashMap<>();
        private int queries;

        StubLookup with(String traceId, String taskId, LocalDateTime endedAt) {
            attempts.put(traceId, new AttemptRef(taskId, traceId, endedAt));
            return this;
        }

        StubLookup failing(String traceId, RuntimeException ex) {
            failures.put(traceId, ex);
            return this;
        }

        int queries() {
            return queries;
        }

        @Override
        public AttemptRef findByTrace(String traceId) {
            queries++;
            RuntimeException failure = failures.get(traceId);
            if (failure != null) {
                throw failure;
            }
            return attempts.get(traceId);
        }
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

        AuditEvent single() {
            assertEquals(1, captured.size(), "期望恰好投出一条, 实际 " + captured.size());
            return captured.get(0);
        }
    }
}
