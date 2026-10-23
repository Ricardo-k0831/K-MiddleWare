package com.km.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计投递的配置。前缀 {@code km.audit}。
 *
 * <p>命名空间刻意用 {@code km.*} 而不是 {@code spring.*} ——
 * 这是本项目的配置, 不是 Spring 框架的配置, 混进 spring 命名空间
 * 会让人以为改了它会影响某个自动配置。
 */
@ConfigurationProperties(prefix = "km.audit")
public class AuditProperties {

    /** RocketMQ namesrv 地址。默认值和 docker-compose 里的映射一致。 */
    private String namesrvAddr = "localhost:9876";

    /** 生产者组名。同一个组名只应有一个实例在跑 (本地开发无所谓)。 */
    private String producerGroup = "km-task-service-audit";

    /**
     * 消费者组名。
     *
     * <p><b>改这个名字 = 从头重新消费一遍</b>。RocketMQ 按组名记录消费位点,
     * 换个组名就是一个全新的消费组, 位点从配置的起始位置重新开始。
     * 所以调试时改名是个有用的手段 (想把历史消息重放一遍就改它),
     * 而误改的后果是审计表里涌进一批重投 —— 幸好有 event_id 去重兜着。
     */
    private String consumerGroup = "km-audit-log-consumer";

    /**
     * 审计事件统一投递的 topic。
     *
     * <p><b>刻意只用一个 topic</b>, 而不是每层一个。理由: 现在的目标是
     * "统一收集到同一个数据源", 一个 topic 让消费端只有一个入口,
     * 顺序和运维都简单。将来某层量特别大时再按层拆分 ——
     * 拆分是消费者无感的 (加订阅即可), 提前拆则是纯粹的复杂度。
     */
    private String topic = "KM_AUDIT";

    /** 同步发送的超时。异步发送下主要影响失败判定。 */
    private int sendTimeoutMs = 3000;

    /**
     * 发送失败时的重试次数。默认 0。
     *
     * <p>审计数据的价值排序天生低于业务数据 —— 它【可以丢】。
     * 重试会把一次网络抖动放大成一段持续的阻塞, 得不偿失。
     */
    private int retryTimes = 0;

    /**
     * 是否启用审计投递。
     *
     * <p>关掉之后发布变成空操作, 但业务照常。本地起服务而没起 RocketMQ 时
     * 把它设成 false 即可, 不用去改代码。
     */
    private boolean enabled = true;

    /**
     * 各层没单独配时使用的采样率。
     *
     * <p>默认 1.0 = 全采。本地开发就该全采 —— 采样是为了在量大的时候省钱,
     * 不是为了在只有三条数据的时候也把两条扔掉。
     */
    private double defaultSampleRate = 1.0;

    /**
     * 分层采样率。key 是 {@link AuditLayer} 的名字, value 是 0~1。
     *
     * <p><b>为什么按层而不是全局</b>: 四层的量级差得非常远。任务生命周期事件
     * 一单就几条, 而 LLM_CALL 一次任务可能上百条。全局一个采样率要么把
     * 生命周期事件采没了(查不到任务怎么走的), 要么被 LLM 调用淹掉(MQ 和
     * 库都撑不住)。分层才可能同时满足这两个目标。
     *
     * <p>示例: {@code km.audit.sample-rate.AGENT_RUNTIME=0.1} 只采 10% 的
     * LLM 调用, 其余层仍然全采。
     *
     * <p>注意: 采样只在 status == OK 的事件上生效, 失败事件永远上报。
     * 理由见 {@code AuditPublisher#shouldSample}。
     */
    private Map<String, Double> sampleRate = new LinkedHashMap<>();

    /** 取某一层的采样率。没配过就用 {@link #defaultSampleRate}。 */
    public double sampleRateFor(AuditLayer layer) {
        Double configured = sampleRate.get(layer.name());
        return configured == null ? defaultSampleRate : configured;
    }

    public String getNamesrvAddr() {
        return namesrvAddr;
    }

    public void setNamesrvAddr(String namesrvAddr) {
        this.namesrvAddr = namesrvAddr;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(int sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public int getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(int retryTimes) {
        this.retryTimes = retryTimes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getDefaultSampleRate() {
        return defaultSampleRate;
    }

    public void setDefaultSampleRate(double defaultSampleRate) {
        this.defaultSampleRate = defaultSampleRate;
    }

    public Map<String, Double> getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(Map<String, Double> sampleRate) {
        this.sampleRate = sampleRate;
    }
}
