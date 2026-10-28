package com.km.task.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 审计<b>提交入口</b>的配置。前缀 {@code km.audit.ingest}。
 *
 * <p>和 km-audit-core 的 {@code AuditProperties} ({@code km.audit}) 分开放,
 * 是因为那一个是"投递器"的配置 (namesrv / topic / 采样), 三个服务共用;
 * 而这里的两项只对<b>任务服务的 intake 接口</b>有意义 —— MCP 网关不接收审计,
 * 它本不该因为这两个配置而重启。
 *
 * <h3>为什么要有 token</h3>
 *
 * <p>{@code POST /api/audit/events} 之前是一条完全开放的写入通道: 任何能访问
 * 8083 的进程都能伪造任意 layer / taskId / actor 的记录, 包括一条"任务已完成"。
 * 审计的价值建立在"记录可信"上, 一条能被无声篡改的审计比没有审计更糟 ——
 * 因为你会在出事之后相信它。
 *
 * <p>这是<b>服务间</b>认证, 不是对外 API 的认证: 共享密钥放在两边配置里,
 * 不经过用户。将来上 mTLS 时这个字段可以退化成"开发期开关"。
 */
@Component
@ConfigurationProperties(prefix = "km.audit.ingest")
public class AuditIngestProperties {

    /**
     * 提交审计时必须带的共享密钥。空白 = <b>关闭校验</b>。
     *
     * <p>默认给一个开发用的占位值, 而不是留空: 留空意味着"默认不安全,
     * 而且没人会注意到"; 占位值至少让校验在本地就跑起来, 生产覆盖成
     * 环境变量即可。它不是秘密 (就写在这份被 git 跟踪的文件里), 所以
     * 不能当作生产凭据 —— 启动日志会在默认值上专门警告一次。
     */
    private String token = "dev-audit-token-change-me";

    /**
     * 查不到 trace 对应的执行时怎么办。
     *
     * <p>{@link Mode#WARN} (默认): 收下, 计入 {@code unverified}。
     * <b>不丢</b>是刻意的 —— 查不到可能是数据库抖动, 也可能是这条事件来自
     * 一个还没建 attempt 的执行; 审计是旁路, 宁可收下一条待核实的记录,
     * 也不要因为一次查询失败丢掉整批。
     *
     * <p>{@link Mode#STRICT}: 拒收。适合"审计必须干净"的场合 ——
     * 代价是数据库抖一下就丢一批。
     *
     * <p>注意: {@code taskId} 与 trace 指向的任务<b>不一致</b>时, 两种模式下
     * 都拒收。那不是"不知道", 是"两条身份互相矛盾", 放进去会污染链路。
     */
    private Mode identityCheck = Mode.WARN;

    public enum Mode {
        WARN,
        STRICT
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Mode getIdentityCheck() {
        return identityCheck;
    }

    public void setIdentityCheck(Mode identityCheck) {
        this.identityCheck = identityCheck;
    }
}
