package com.km.task.retry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 自动重试的配置。前缀 {@code km.retry}。
 *
 * <h3>为什么默认关闭</h3>
 *
 * <p>自动重试会<b>自己花钱</b> (调 LLM、调工具), 还会在运维不知情的时候改变
 * 任务状态。这种能力不该因为"代码合并了"就默认生效 —— 打开它应当是一个
 * 显式决定, 所以 {@code enabled} 默认 {@code false}, 由环境变量在启动时打开。
 *
 * <h3>为什么分 scheduler / dispatch 两组</h3>
 *
 * <p>它们回答的是两个独立问题: <b>哪些任务该重试</b> (扫描节奏、退避、批量、
 * 租户灰度) 和 <b>怎么把它跑起来</b> (Python 地址、超时、并发)。混在一起时,
 * 想调"重试得太快"会不小心改到 HTTP 超时, 排查方向容易被带偏。
 */
@Component
@ConfigurationProperties(prefix = "km.retry")
public class RetryProperties {

    private final Scheduler scheduler = new Scheduler();
    private final Dispatch dispatch = new Dispatch();

    public Scheduler getScheduler() {
        return scheduler;
    }

    public Dispatch getDispatch() {
        return dispatch;
    }

    public static class Scheduler {

        /** 总开关。关着的时候 {@link RetryScheduler} 这个 Bean 根本不会创建。 */
        private boolean enabled = false;

        /** 两轮扫描之间的间隔。 */
        private long intervalMs = 10_000;

        /** 一轮最多处理多少个任务。防止一次积压把线程池和 Python 同时打满。 */
        private int batchSize = 20;

        /** 第一次重试前至少等多久。之后按 2 的幂翻倍。 */
        private int baseBackoffSeconds = 30;

        /** 退避上限。任务可能失败很多轮, 不能让间隔无限增长。 */
        private int maxBackoffSeconds = 300;

        /**
         * 只扫描这个租户; 空 = 所有租户。
         *
         * <p>这是给"会花钱的自动行为"留的灰度开关: 先在一个租户上打开,
         * 观察审计和账单, 再放开。验证脚本也靠它把自己的任务和老数据隔开。
         */
        private String tenant = "";

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

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getBaseBackoffSeconds() {
            return baseBackoffSeconds;
        }

        public void setBaseBackoffSeconds(int baseBackoffSeconds) {
            this.baseBackoffSeconds = baseBackoffSeconds;
        }

        public int getMaxBackoffSeconds() {
            return maxBackoffSeconds;
        }

        public void setMaxBackoffSeconds(int maxBackoffSeconds) {
            this.maxBackoffSeconds = maxBackoffSeconds;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }
    }

    public static class Dispatch {

        /** Python Agent 运行时的地址, 不带 /chat。 */
        private String chatUrl = "http://localhost:8000";

        /**
         * 连不上 Python 时最多等多久。
         *
         * <p>比读超时短得多: 连不上说明进程/网络不通, 多等没有意义,
         * 而这个值决定"派发失败"多久能被发现并回写 FAILED。
         */
        private int connectTimeoutSeconds = 5;

        /**
         * 一次 {@code /chat} 最多等多久。
         *
         * <p>必须<b>远大于</b>一次 Agent 运行的时间: Agent 里是 LLM + 多轮工具,
         * 几分钟很正常。读超时一旦触发, 请求可能已经在 Python 那边执行了,
         * 我们<b>不能</b>把它当成失败去关链 —— 那种情况下结果未知, 只能记日志。
         */
        private int readTimeoutSeconds = 600;

        /** 同时派发几个任务。LLM 调用是长请求, 不宜太多。 */
        private int concurrency = 2;

        /** 等待派发的队列上限。满了就拒绝, 并把这轮刚开的执行标记为失败。 */
        private int queueCapacity = 100;

        public String getChatUrl() {
            return chatUrl;
        }

        public void setChatUrl(String chatUrl) {
            this.chatUrl = chatUrl;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(int readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }
}
