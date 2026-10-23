package com.km.audit;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AuditPublisher} 的规则测试。
 *
 * <p>没有 @SpringBootTest, 也不连 broker —— 假生产者替掉了 RocketMQ。
 * 这里测的不是"能不能发到 MQ"(那是集成测试的事, 已经手工验过), 而是
 * <b>发不发、什么时候发、发成什么样</b>这些纯逻辑规则。
 *
 * <p>重点是三条容易被改坏、改坏了又不会立刻暴露的规则:
 * <ol>
 *   <li>事务没提交不发 —— 否则回滚了还会留下"任务已创建"的审计</li>
 *   <li>失败事件不受采样影响 —— 否则线上偶发失败会查不到</li>
 *   <li>任何情况下都不往调用方抛异常 —— 审计是旁路, 不能反过来搞挂业务</li>
 * </ol>
 */
class AuditPublisherTest {

    private final RecordingProducer fake = new RecordingProducer();

    @AfterEach
    void 清理事务同步状态() {
        // 这几个测试会手工往线程变量里塞事务同步器。不清理的话, 同一个线程
        // 跑下一个测试时会"莫名"处于事务中, 失败信息还会指向错误的测试方法。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ------------------------------------------------------------------
    //  1. 事务边界
    // ------------------------------------------------------------------

    @Test
    void 事务里发布的事件要等提交之后才发送() {
        AuditPublisher publisher = newPublisher();
        publisher.bindProducerForTest(fake);

        TransactionSynchronizationManager.initSynchronization();
        publisher.publish(event(AuditEvent.OK));

        assertEquals(0, fake.sent.size(), "事务还没提交, 这时发出去就是'库里没有、审计里有'");

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size(), "应当恰好注册了一个提交后回调");
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        assertEquals(1, fake.sent.size(), "提交之后必须发出去");
    }

    @Test
    void 事务回滚的事件永远不会被发送() {
        AuditPublisher publisher = newPublisher();
        publisher.bindProducerForTest(fake);

        TransactionSynchronizationManager.initSynchronization();
        publisher.publish(event(AuditEvent.OK));

        // 回滚路径: Spring 不会调 afterCommit。这里只是把同步器丢掉,
        // 等价于"事务回滚了、什么都没提交"。
        TransactionSynchronizationManager.clearSynchronization();

        assertEquals(0, fake.sent.size(), "回滚的任务不该在审计里留下'已创建'");
    }

    @Test
    void 没有事务时立即发送() {
        AuditPublisher publisher = newPublisher();
        publisher.bindProducerForTest(fake);

        publisher.publish(event(AuditEvent.OK));

        assertEquals(1, fake.sent.size(), "不在事务里就没什么可等的, 直接发");
    }

    // ------------------------------------------------------------------
    //  2. 采样
    // ------------------------------------------------------------------

    @Test
    void 采样只作用于成功事件_失败事件永远上报() {
        AuditProperties props = newProps();
        props.setDefaultSampleRate(0.0); // 成功事件全丢, 用来放大规则
        AuditPublisher publisher = new AuditPublisher(props, JsonMapper.builder().build());
        publisher.bindProducerForTest(fake);

        publisher.publish(event(AuditEvent.OK));
        assertEquals(0, fake.sent.size(), "采样率 0 时成功事件应当被丢掉");

        publisher.publish(event(AuditEvent.ERROR));
        assertEquals(1, fake.sent.size(),
                "失败事件不能被采样丢掉 —— 它恰恰是最该留下的那条");
    }

    @Test
    void 分层采样_没配的层走默认值() {
        AuditProperties props = newProps();
        props.setDefaultSampleRate(1.0);
        props.setSampleRate(Map.of(AuditLayer.AGENT_RUNTIME.name(), 0.0));

        assertEquals(0.0, props.sampleRateFor(AuditLayer.AGENT_RUNTIME), 1e-9);
        assertEquals(1.0, props.sampleRateFor(AuditLayer.MCP_GATEWAY), 1e-9,
                "没单独配的层应当回落到 defaultSampleRate");
    }

    // ------------------------------------------------------------------
    //  3. 绝不抛异常
    // ------------------------------------------------------------------

    @Test
    void 生产者不可用时静默丢弃_不把异常抛回业务() {
        AuditPublisher publisher = newPublisher();
        // 故意不 bindProducerForTest: producer == null, 模拟 RocketMQ 没起来
        assertDoesNotThrow(() -> publisher.publish(event(AuditEvent.OK)));

        assertEquals(0, fake.sent.size());
        assertEquals(1, publisher.droppedCount());
    }

    @Test
    void 传null也不抛异常() {
        AuditPublisher publisher = newPublisher();
        publisher.bindProducerForTest(fake);

        assertDoesNotThrow(() -> publisher.publish(null));
        assertEquals(0, fake.sent.size());
    }

    // ------------------------------------------------------------------
    //  4. 消息形态 —— 传给下游的契约
    // ------------------------------------------------------------------

    @Test
    void 消息key同时包含taskId和traceId_标签是层名() {
        AuditPublisher publisher = newPublisher();
        publisher.bindProducerForTest(fake);

        publisher.publish(event(AuditEvent.OK));

        Message message = fake.sent.get(0);
        assertEquals("KM_AUDIT", message.getTopic());
        assertEquals(AuditLayer.TASK_SERVICE.name(), message.getTags(),
                "tags 用层名, 消费端才能按层订阅而不是全量拉下来再筛");
        assertEquals("task-1 trace-1", message.getKeys(),
                "两个 ID 都要进 key —— 否则 RocketMQ 控制台只能按其中一个维度查");
    }

    @Test
    void 事件可以被原样反序列化回来_中文payload不乱码() {

        AuditPublisher publisher = newPublisher();
        publisher.bindProducerForTest(fake);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("phase", "CREATED");
        requestBody.put("intent", "查一下订单 12345 的物流");
        publisher.publish(AuditEvent.builder()
                .taskId("task-1").traceId("trace-1").tenantId("km")
                .layer(AuditLayer.TASK_SERVICE).eventType(AuditEventType.TASK_LIFECYCLE)
                .actor("u-1").status(AuditEvent.OK)
                .requestBody(requestBody)
                .responseBody(Map.of("status", "PENDING"))
                .occurredAt(1750000000000L)
                .build());

        String body = new String(fake.sent.get(0).getBody(), StandardCharsets.UTF_8);
        AuditEvent decoded = JsonMapper.builder().build().readValue(body, AuditEvent.class);

        assertEquals("查一下订单 12345 的物流", decoded.requestBody().get("intent"));
        assertEquals("PENDING", decoded.responseBody().get("status"));
        assertEquals("task-1", decoded.taskId());
        assertEquals("km", decoded.tenantId());
        assertEquals(AuditLayer.TASK_SERVICE, decoded.layer());
        assertEquals(AuditEventType.TASK_LIFECYCLE, decoded.eventType());
        assertEquals(1750000000000L, decoded.occurredAt());
    }

    @Test
    void 每条事件都带唯一eventId_且能原样反序列化回来() {
        AuditPublisher publisher = newPublisher();
        publisher.bindProducerForTest(fake);

        publisher.publish(event(AuditEvent.OK));
        publisher.publish(event(AuditEvent.OK));

        String first = eventIdOf(fake.sent.get(0));
        String second = eventIdOf(fake.sent.get(1));

        // 消费端靠 eventId 做幂等 —— 两条相同内容的事件如果 ID 撞了,
        // 第二条就会被当成重投丢掉, 审计直接少一条。
        assertTrue(first != null && !first.isBlank(), "eventId 不能为空");
        assertTrue(!first.equals(second), "两次发布的事件 ID 必须不同");
    }

    private static String eventIdOf(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            return JsonMapper.builder().build().readValue(body, AuditEvent.class).eventId();
        } catch (Exception ex) {
            throw new AssertionError("消息体反序列化失败: " + body, ex);
        }
    }

    // ------------------------------------------------------------------
    //  5. 信封本身的约束
    // ------------------------------------------------------------------

    @Test
    void 缺少layer或eventType时构造直接失败() {
        // 这两个字段缺失的话, 事件落进 audit_log 后既分不出采集层也分不出类型,
        // 就是一条废数据。宁可让采集点在测试阶段就炸, 也别让它悄悄上线。
        assertThrows(IllegalStateException.class, () -> AuditEvent.builder()
                .taskId("task-1").eventType(AuditEventType.TASK_LIFECYCLE).build());
        assertThrows(IllegalStateException.class, () -> AuditEvent.builder()
                .taskId("task-1").layer(AuditLayer.MCP_GATEWAY).build());
    }

    @Test
    void 不指定occurredAt时默认取构造时刻() {
        long before = System.currentTimeMillis();
        AuditEvent built = AuditEvent.builder()
                .layer(AuditLayer.TASK_SERVICE).eventType(AuditEventType.LLM_CALL).build();
        long after = System.currentTimeMillis();

        assertTrue(built.occurredAt() >= before && built.occurredAt() <= after,
                "默认应当是'现在', 实际取到 " + built.occurredAt());
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    /** 不连 broker 的假生产者: 只把发出去的消息留在内存里。 */
    private static final class RecordingProducer extends DefaultMQProducer {

        final List<Message> sent = new ArrayList<>();

        RecordingProducer() {
            // group 只是个名字, 构造它不会碰网络 —— 碰网络的是 start()
            super("test-audit-group");
        }

        @Override
        public void start() {
            // 刻意不实现: 真去连 namesrv 的话, 这个测试在有 broker 的机器上
            // 会悄悄变成集成测试, 在没有 broker 的机器上会直接红。
        }

        @Override
        public void shutdown() {
            // 没起过, 没什么可关的
        }

        @Override
        public void send(Message msg, SendCallback callback) {
            sent.add(msg);
            // 真实实现是异步回调, 这里同步触发 —— 让断言能在 publish 返回后立刻做
            callback.onSuccess(null);
        }
    }

    private static AuditPublisher newPublisher() {
        return new AuditPublisher(newProps(), JsonMapper.builder().build());
    }

    private static AuditProperties newProps() {
        AuditProperties props = new AuditProperties();
        props.setTopic("KM_AUDIT");
        props.setDefaultSampleRate(1.0);
        return props;
    }

    private static AuditEvent event(String status) {
        return AuditEvent.builder()
                .taskId("task-1").traceId("trace-1").tenantId("km")
                .layer(AuditLayer.TASK_SERVICE).eventType(AuditEventType.TASK_LIFECYCLE)
                .actor("u-1").status(status)
                .requestBody(Map.of("phase", "CREATED"))
                .occurredAt(1750000000000L)
                .build();
    }
}
