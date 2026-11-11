                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 package com.km.task.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 控制面 (任务服务) 的接口认证配置。前缀 {@code km.control-auth}。
 *
 * <h3>为什么现在才做, 以及为什么是这个形状</h3>
 *
 * <p>在此之前 {@code /api/tasks/**} 是完全开放的: 任何人能建任务、改状态、
 * 取消别人的执行。审计里记的 {@code actor} 也只能取任务归属人 ——
 * 它回答"这是谁的任务", 回答不了"刚才是谁动的"。
 *
 * <p>没有统一认证中心的前提下, 这里选择<b>共享密钥 + 密钥表</b>:
 * 每个密钥映射一个租户 (隔离边界) 和一个操作者名字 (审计用)。
 * 它不是用户登录态, 而是"调用方身份" —— 和审计入口的
 * {@code X-KM-Audit-Token} 是同一类东西, 只是粒度更细。
 *
 * <h3>为什么区分 USER / SERVICE 两种角色</h3>
 *
 * <p>控制面上有两类调用方, 它们能做的事<b>本来就不同</b>:
 *
 * <ul>
 *   <li><b>USER</b> —— 人 (或人的脚本): 建任务、查自己的任务、取消、重试。
 *       不能上报状态, 因为状态必须由真正执行的那一方报。</li>
 *   <li><b>SERVICE</b> —— Python Agent 运行时: 上报状态、发心跳。
 *       它的租户是通配的, 因为它替所有租户执行任务。</li>
 * </ul>
 *
 * <p>如果不分角色, 一个拿到了用户密钥的脚本就能伪造"任务已 COMPLETED",
 * 状态机的事实源就不再是事实源。角色边界比租户边界更硬。
 *
 * <h3>默认值为什么是"开启 + 开发密钥"</h3>
 *
 * <p>和审计入口同一个理由 (见 {@code AuditIngestProperties}): 默认打开,
 * 才会在本地就跑起来, 而不是"生产记得开"。开发密钥写在被 git 跟踪的
 * 配置里, 不是秘密, 启动日志会专门 WARN 一次; 生产用环境变量覆盖。
 *
 * <p>{@code enabled=false} 是明确关闭 (本地临时调试用), 同样会 WARN ——
 * 安全开关不能静默地失效。
 */
@Component
@ConfigurationProperties(prefix = "km.control-auth")
public class ControlAuthProperties {

    /** 是否校验 {@code X-KM-Api-Key}。关闭时所有调用方被视为系统身份。 */
    private boolean enabled = true;

    /** 证书表。key 是明文比较的共享密钥, 其余字段是它换来的身份。 */
    private List<ApiKey> keys = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<ApiKey> getKeys() {
        return keys;
    }

    public void setKeys(List<ApiKey> keys) {
        this.keys = keys;
    }

    /** 一条密钥。用可变类而不是 record: Spring Boot 的 YAML 绑定走 setter。 */
    public static class ApiKey {

        /** 共享密钥本身。空值在装配时会被跳过 (不会变成"空 key 就能进")。 */
        private String key;

        /**
         * 这个密钥能看见哪个租户。
         *
         * <p>{@code "*"} = 通配 (只应给 SERVICE)。建任务时如果请求体也带了
         * tenantId, 必须和这里一致; 不一致就是越权尝试。
         */
        private String tenant = "*";

        /** 审计里的操作者名字。缺省 "unknown", 宁可难看也不要编一个名字。 */
        private String user = "unknown";

        /** {@code USER} 或 {@code SERVICE}。 */
        private Role role = Role.USER;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public Role getRole() {
            return role;
        }

        public void setRole(Role role) {
            this.role = role;
        }
    }

    public enum Role {
        /** 人 / 人的脚本: 建任务、查、取消、重试。 */
        USER,
        /** 服务间调用方 (Python 运行时): 上报状态与心跳, 租户通配。 */
        SERVICE
    }
}
