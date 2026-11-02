package com.km.task.heartbeat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 心跳兜底的配置。前缀 {@code km.heartbeat}。
 *
 * <h3>为什么默认关闭</h3>
 *
 * <p>它会在没人操作的时候<b>自己把任务判死</b> —— 一条误判会触发后续的自动
 * 重试 (真金白银), 还会给审计里留下一条 SYSTEM_HEARTBEAT 的失败事件。
 * 和自动重试同一条原则: 能力合进来不等于默认生效, 打开它是一个显式决定。
 *
 * <h3>为什么 scanner 和 probe 的节奏是两个值</h3>
 *
 * <p>{@code interval-ms} 决定"多久检查一次", {@code stale-after-seconds}
 * 决定"多久没证据才算可疑"。前者影响发现延迟, 后者影响误判率 —— 想调快
 * 只该动前者。后者必须<b>远大于</b> Python 侧的心跳间隔 (默认 15s), 否则
 * 丢一两个心跳就会被当成死亡。
 */
@Component
@ConfigurationProperties(prefix = "km.heartbeat")
public class HeartbeatProperties {

    /** 总开关。关着的时候 {@link HeartbeatSweeper} 这个 Bean 根本不会创建。 */
    private boolean enabled = false;

    /** 两轮扫描之间的间隔。 */
    private long intervalMs = 15_000;

    /**
     * 多久没有任何心跳/派发证据才算"可疑"。
     *
     * <p>到期的执行不会立刻被关链 —— 还要主动探活, 确认 Python 侧真的
     * 没有这条执行在跑。这个值只决定"该去问了", 不决定"该判死了"。
     */
    private int staleAfterSeconds = 120;

    /** 一轮最多探活多少个执行。防止积压时把 Python 探活接口打满。 */
    private int batchSize = 20;

    /**
     * 只扫描这个租户; 空 = 所有租户。
     *
     * <p>灰度开关, 和 {@code km.retry.scheduler.tenant} 同一个理由:
     * 先在一个租户上观察误判率, 再放开。验证脚本也靠它隔离自己的任务。
     */
    private String tenant = "";

    /** Python Agent 运行时地址 (不带路径), 探活打的是 {probe-url}/tasks/{trace}/alive。 */
    private String probeUrl = "http://localhost:8000";

    /** 探活的连接超时。连不上就是"未知", 不关链, 所以短一点没关系。 */
    private int probeConnectTimeoutSeconds = 3;

    /**
     * 探活的读超时。
     *
     * <p>探活是同步 GET, 而扫描线程只有一条 —— 它卡住会拖住整轮扫描。
     * 超时的语义是"状态未知", 不是"死了", 所以宁可短。
     */
    private int probeTimeoutSeconds = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public int getStaleAfterSeconds() {
        return staleAfterSeconds;
    }

    public void setStaleAfterSeconds(int staleAfterSeconds) {
        this.staleAfterSeconds = staleAfterSeconds;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getProbeUrl() {
        return probeUrl;
    }

    public void setProbeUrl(String probeUrl) {
        this.probeUrl = probeUrl;
    }

    public int getProbeConnectTimeoutSeconds() {
        return probeConnectTimeoutSeconds;
    }

    public void setProbeConnectTimeoutSeconds(int probeConnectTimeoutSeconds) {
        this.probeConnectTimeoutSeconds = probeConnectTimeoutSeconds;
    }

    public int getProbeTimeoutSeconds() {
        return probeTimeoutSeconds;
    }

    public void setProbeTimeoutSeconds(int probeTimeoutSeconds) {
        this.probeTimeoutSeconds = probeTimeoutSeconds;
    }
}
