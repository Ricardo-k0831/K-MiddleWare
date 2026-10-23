package com.km.mcp.audit;

import com.km.audit.AuditEvent;
import com.km.audit.AuditProperties;
import com.km.audit.AuditPublisher;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.common.message.Message;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试夹具: 让审计事件"发出去"但不真的进 broker, 然后能一条条读回来。
 *
 * <h3>为什么让事件走完整的序列化, 而不是直接抓 AuditEvent 对象</h3>
 *
 * 这里刻意绕了一圈 —— 事件被序列化成字节、塞进 {@code Message}, 测试再
 * 从字节反序列化回对象, 而不是在内存里直接把 {@code AuditEvent} 存下来。
 *
 * <p>因为真正要验的东西有一半在序列化里: 信封能不能过一遍 JSON 往返
 * (record + Map 字段, 加上 Jackson 3)、中文字节会不会坏、枚举会不会
 * 被写成下标而不是名字。直接抓对象的话, 这些全部验不到 —— 而它们恰恰是
 * 跨语言契约 (Python 那边也要按同样的格式发) 上最容易出问题的地方。
 *
 * <p>消费端做的是完全相同的一次往返, 所以这里看到的坏法, 就是线上会有的坏法。
 */
public final class AuditCapture {

    private final RecordingProducer producer = new RecordingProducer();
    private final AuditPublisher publisher;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public AuditCapture() {
        AuditProperties props = new AuditProperties();
        props.setTopic("KM_AUDIT");
        // 测试要看到每一条, 采样率必须是 1 —— 否则 MOCK_GATEWAY 那层的
        // 事件会被随机丢掉, 测试变成偶发失败。
        props.setDefaultSampleRate(1.0);
        this.publisher = new AuditPublisher(props, jsonMapper);
        this.publisher.bindProducerForTest(producer);
    }

    /** 交给被测对象的投递器。 */
    public AuditPublisher publisher() {
        return publisher;
    }

    /** 已经发出去的事件, 按发送顺序。 */
    public List<AuditEvent> events() {
        List<AuditEvent> decoded = new ArrayList<>();
        for (Message message : producer.sent) {
            decoded.add(decode(message));
        }
        return decoded;
    }

    /** 第 n 条 (从 0 开始)。越界时给出比 IndexOutOfBounds 更有用的信息。 */
    public AuditEvent event(int index) {
        List<AuditEvent> all = events();
        if (index >= all.size()) {
            throw new AssertionError(
                    "期望至少有 " + (index + 1) + " 条审计事件, 实际只有 " + all.size() + " 条");
        }
        return all.get(index);
    }

    public int count() {
        return producer.sent.size();
    }

    /** 原始消息体 —— 需要断言"字节里到底有什么"时用。 */
    public String rawBody(int index) {
        return new String(producer.sent.get(index).getBody(), StandardCharsets.UTF_8);
    }

    private AuditEvent decode(Message message) {
        try {
            return jsonMapper.readValue(message.getBody(), AuditEvent.class);
        } catch (Exception ex) {
            throw new AssertionError("消息体反序列化失败: " + new String(message.getBody(),
                    StandardCharsets.UTF_8), ex);
        }
    }

    /**
     * 不连 broker 的假生产者。
     *
     * <p>{@code start()} 被覆盖成空实现是关键: 真去连 namesrv 的话, 这套
     * 单元测试在有 broker 的机器上会悄悄变成集成测试, 在没有 broker 的机器上
     * 会直接红 —— 同一次提交在两个人的机器上表现不同, 是最难查的一类测试问题。
     */
    private static final class RecordingProducer extends DefaultMQProducer {

        final List<Message> sent = new ArrayList<>();

        RecordingProducer() {
            super("test-audit-group");
        }

        @Override
        public void start() {
            // 刻意不实现, 理由见类注释
        }

        @Override
        public void shutdown() {
            // 没起过, 没什么可关的
        }

        @Override
        public void send(Message msg, SendCallback callback) {
            sent.add(msg);
            // 真实实现是异步的, 这里同步回调, 让断言能在方法返回后立刻做
            callback.onSuccess(null);
        }
    }
}
