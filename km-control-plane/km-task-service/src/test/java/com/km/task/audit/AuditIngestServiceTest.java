package com.km.task.audit;

import com.km.audit.AuditEvent;
import com.km.audit.AuditEventType;
import com.km.audit.AuditLayer;
import com.km.audit.AuditProperties;
import com.km.audit.AuditPublisher;
import com.km.task.web.dto.AuditEventRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提交入口的映射测试 —— Python 发过来的报文, 落到信封上是不是同一个意思。
 *
 * <p>没有 @SpringBootTest, 也不连 broker / 数据库: 假投递器替掉了 RocketMQ,
 * 只验映射和容错这两件事。和 {@code TaskStatusMachineTest} 同一个思路 ——
 * 能在毫秒内跑完的规则测试, 不该被拖去起一个 Spring 上下文。
 *
 * <p>真正的投递行为 (序列化、采样、发 MQ) 由 km-audit-core 的
 * {@code AuditPublisherTest} 覆盖, HTTP 边界由 {@code AuditControllerTest}
 * 覆盖。这里只回答一个问题: <b>这个 DTO 有没有把字段丢在映射上</b>。
 */
class AuditIngestServiceTest {

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final AuditIngestService service = new AuditIngestService(publisher);

    // ------------------------------------------------------------------
    //  字段映射
    // ------------------------------------------------------------------

    @Test
    void 每个字段都原样落到信封上() {
        service.ingest(List.of(new AuditEventRequest(
                AuditLayer.AGENT_RUNTIME,
                AuditEventType.LLM_CALL,
                "task-1", "trace-1", "km", "u-1",
                "OK", 1840, 812, 96,
                Map.of("model", "deepseek-chat"),
                Map.of("finish_reason", "stop"),
                null,
                1_760_460_812_345L,
                "evt-fixed")));

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
    //  容错
    // ------------------------------------------------------------------

    @Test
    void 单条坏掉不连坐整批() {
        // layer 为 null 时 AuditEvent.Builder 会抛 —— 正常路径上 DTO 校验
        // 会先挡住 (400), 但这里要保证即使漏进来了, 也只丢那一条。
        // 审计是旁路: 因为一条畸形数据把整批丢掉, 是"旁路反过来伤害主路"。
        int accepted = service.ingest(List.of(
                llmCall(),
                new AuditEventRequest(null, AuditEventType.LLM_CALL,
                        "task-bad", null, null, null, null, null, null, null,
                        null, null, null, null, null),
                llmCall()));

        assertEquals(2, accepted);
        assertEquals(2, publisher.all().size());
        // 丢的是哪一条也要说清楚 —— 只报"总共丢了 1 条", 事后没法知道是哪个任务。
        assertEquals("task-1", publisher.all().get(0).taskId());
        assertEquals("task-1", publisher.all().get(1).taskId());
    }

    @Test
    void 列表里的null条目被跳过而不是炸掉整批() {
        assertEquals(1, service.ingest(java.util.Arrays.asList(llmCall(), null)));
        assertEquals(1, publisher.all().size());
    }

    @Test
    void 空列表返回0而不是抛异常() {
        // Python 的 worker 醒来时队列可能是空的 —— 那是一次正常轮询, 不是错误。
        assertEquals(0, service.ingest(List.of()));
    }

    @Test
    void null当作没有() {
        assertEquals(0, service.ingest(null));
    }

    @Test
    void 返回的是真正投出去的条数() {
        assertEquals(3, service.ingest(List.of(llmCall(), llmCall(), llmCall())));
        assertEquals(3, publisher.all().size());
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
