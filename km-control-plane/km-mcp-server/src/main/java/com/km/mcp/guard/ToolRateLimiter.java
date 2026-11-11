package com.km.mcp.guard;

import com.km.mcp.audit.TaskIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 工具调用的限流闸门。在<b>工具方法内部、真正调后端之前</b>调用。
 *
 * <h3>★ 先说最重要的决策: Redis 不可用时 fail-open (放行) ★</h3>
 *
 * <p>限流的目的是保护后端不被流量打挂。如果"保护器自己坏了就拒绝所有请求",
 * 那么 Redis 抖一下的后果是<b>整个 Agent 平台不可用</b> —— 保护器变成了
 * 比它要防的问题更严重的单点。放行时最坏的结果是短时间流量略超预期,
 * 而且此时后端大概率还扛得住 (它扛过正常流量)。
 *
 * <p>反过来选 fail-closed 的理由通常是"安全" —— 但限流不是鉴权。
 * 鉴权失败必须拒绝 (fail-closed), 因为放行意味着越权; 限流失效只是
 * 容量问题, 拒绝意味着可用性问题。两类开关的默认方向本来就不同。
 *
 * <h3>★ 两个维度的检查顺序: 先任务, 后工具 ★</h3>
 *
 * <p>先查任务桶, 因为一次拒绝如果只能记一个原因, "这个任务自己在刷"比
 * "全局流量大"更有诊断价值。顺序还有资源上的理由: 任务桶被拒时
 * <b>不会</b>去消耗工具桶的令牌, 否则一个失控任务可以把共享配额耗光,
 * 让其它任务明明很克制也被拒 —— 那正是限流最该防止的事。
 *
 * <h3>为什么匿名调用也要限</h3>
 *
 * <p>不带任务身份的调用归到同一个 {@code anonymous} 桶里。它不能区分调用方,
 * 但它至少能防止"匿名调用把后端的配额全吃光"。代价是一批匿名调用会互相
 * 影响 —— 这是没有身份时必然的取舍, 脚本/调试时带个身份就能避开。
 */
@Component
public class ToolRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ToolRateLimiter.class);

    /** 匿名调用共用的桶名。理由见类注释。 */
    private static final String ANONYMOUS = "anonymous";

    /** 存储故障日志的节流间隔: Redis 挂了的时候每个请求都会失败, 不节流会刷屏。 */
    private static final long STORE_ERROR_LOG_INTERVAL_MS = 10_000L;

    private final GatewayGuardProperties properties;
    private final RateLimitStore store;

    /** 上次打印"限流存储不可用"的时间; 和 suppressed 一起构成一条可读的告警。 */
    private volatile long lastStoreErrorLogAt = 0L;
    private volatile long suppressedStoreErrors = 0L;

    public ToolRateLimiter(GatewayGuardProperties properties, RateLimitStore store) {
        this.properties = properties;
        this.store = store;
        if (!properties.getRateLimit().isEnabled()) {
            log.warn("★ 网关限流已关闭 (km.gateway.rate-limit.enabled=false) —— "
                    + "后端将直接承受全部流量, 只应在本地调试时这样配");
        }
    }

    /**
     * 检查这次调用能不能放行。
     *
     * @param tool     工具名, 作为工具维度的桶键
     * @param identity 调用方身份; 匿名时所有调用共用一个桶
     * @throws ToolRateLimitedException 任一维度超限。异常里带 scope, 便于
     *                                  区分"任务太猛"和"整体流量太大"
     */
    public void check(String tool, TaskIdentity identity) {
        GatewayGuardProperties.RateLimit config = properties.getRateLimit();
        if (!config.isEnabled()) {
            return;
        }
        // taskKey = 限流器前缀 + task + taskID.replaceAll(). shortHash()
        String taskKey = config.getKeyPrefix() + ":task:" + taskBucketName(identity);
        // 检查任务桶
        if (!acquire(taskKey, config.getPerTaskBurst(), config.getPerTaskRefillPerSecond())) {
            log.warn("[MCP] 任务维度限流 tool={} taskId={} burst={} refill={}/s",
                    tool, taskBucketName(identity), config.getPerTaskBurst(),
                    config.getPerTaskRefillPerSecond());
            throw new ToolRateLimitedException(
                    "调用过于频繁, 当前任务已超出工具调用配额", "task");
        }
        // toolKey = 限流器前缀 + tool + toolName
        String toolKey = config.getKeyPrefix() + ":tool:" + tool;
        // 检查工具桶
        if (!acquire(toolKey, config.getPerToolBurst(), config.getPerToolRefillPerSecond())) {
            log.warn("[MCP] 工具维度限流 tool={} burst={} refill={}/s",
                    tool, config.getPerToolBurst(), config.getPerToolRefillPerSecond());
            throw new ToolRateLimitedException(
                    "调用过于频繁, 工具 " + tool + " 当前整体压力过大", "tool");
        }
    }

    /** @return true = 放行; 存储故障也放行 (fail-open, 见类注释) */
    private boolean acquire(String key, int capacity, double refillPerSecond) {
        try {
            // 尝试取出令牌
            return store.tryConsume(key, capacity, refillPerSecond);
        } catch (RuntimeException ex) {
            noteStoreFailure(ex);
            return true;
        }
    }

    private void noteStoreFailure(RuntimeException ex) {
        long now = System.currentTimeMillis();
        if (now - lastStoreErrorLogAt < STORE_ERROR_LOG_INTERVAL_MS) {
            suppressedStoreErrors++;
            return;
        }
        log.error("限流存储不可用, 本段时间内已 fail-open (放行) {} 次。"
                        + "限流暂时失效, 但业务不受影响; 检查 Redis 连接。",
                suppressedStoreErrors + 1, ex);
        lastStoreErrorLogAt = now;
        suppressedStoreErrors = 0;
    }

    /**
     * 任务 ID -> Redis key 里的一段。
     *
     * <p>任务 ID 来自 HTTP 头, 是不可信输入。直接拼进 key 有两个问题:
     * 超长 (几千字节) 会让 key 膨胀, 特殊字符让 key 难以人工排查。
     * 所以清洗成安全字符并截断; <b>但清洗后可能撞车</b> (两个不同 ID 洗成
     * 同一段), 那会让互不相干的任务共用一个桶 —— 于是原始值不同时再补一段
     * 哈希, 保证不同任务不会共享配额。
     */
    // 传入identity, 处理taskId
    private static String taskBucketName(TaskIdentity identity) {
        if (identity == null || identity.isAnonymous() || identity.taskId() == null) {
            return ANONYMOUS;
        }
        String raw = identity.taskId();
        // 清洗任务 ID, 只保留安全字符
        String clean = raw.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (clean.length() > 48) {
            // 截断到 48 字符
            clean = clean.substring(0, 48);
        }
        // 清洗后有变化
        if (!clean.equals(raw)) {
            //防止清洗后撞车
            clean = clean + "-" + shortHash(raw);
        }
        return clean;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法, 这里不可能发生。
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }
}
