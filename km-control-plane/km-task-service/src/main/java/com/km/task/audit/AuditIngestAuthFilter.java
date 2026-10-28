package com.km.task.audit;

import com.km.audit.AuditHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 审计提交入口的服务间认证。
 *
 * <p>只拦 {@code /api/audit/**}, 其它路径一概不碰 —— 这个过滤器的职责
 * 窄到只有一件事, 否则它会慢慢长成第二个"全局鉴权层", 而那个东西应该
 * 由 Spring Security 来做, 不是塞在一个旁路模块的过滤器里。
 *
 * <h3>为什么用共享密钥, 而不是"只信内网来源"</h3>
 *
 * <p>来源 IP 在开发环境里永远是 {@code 127.0.0.1} (Python 和 Java 同机),
 * 在容器里又是另一套网段, 写死了就是"本地能跑, 部署就废"。共享密钥两边各
 * 配一个值, 和网络拓扑无关。它不是用户凭据, 所以也不走登录态。
 *
 * <h3>为什么比较用 {@link MessageDigest#isEqual}</h3>
 *
 * <p>{@code String.equals} 遇到第一个不同的字节就返回, 比较耗时因此和
 * "猜对了几个字符"相关 —— 那是可以被逐位试探的信号。{@code isEqual}
 * 对等长数组做定长比较。不完美 (长度不同会提前返回), 但比 {@code equals}
 * 强, 而且没有引入额外依赖。
 *
 * <h3>为什么空 token 是"关闭", 而且只警告不启动失败</h3>
 *
 * <p>本地开发 / 单元测试里, 强制每个请求都带 token 只会让人把 token 硬编码
 * 到脚本里到处复制。但"静默关闭"更糟 —— 那等于把安全开关藏起来。所以:
 * 配空 = 明确关闭, 并且启动时打一条 WARN, 让它在日志里显眼。
 *
 * <p>反过来, {@code token} 是开发默认值时也警告一次: 那个值被 git 跟踪,
 * 全世界都知道。生产必须用 {@code KM_AUDIT_INGEST_TOKEN} 覆盖。
 */
@Component
public class AuditIngestAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestAuthFilter.class);

    /** 这个过滤器管的路径。带结尾斜杠, 避免误伤 {@code /api/auditxyz}。 */
    private static final String AUDIT_PATH = "/api/audit";

    /** 开发用默认值。只用来在启动日志里提醒"你还没换"。 */
    static final String INSECURE_DEFAULT_TOKEN = "dev-audit-token-change-me";

    /** null = 不校验。预先把 String 转好, 每个请求就只剩一次比较。 */
    private final byte[] expectedToken;

    public AuditIngestAuthFilter(AuditIngestProperties properties) {
        String token = properties.getToken();
        if (token == null || token.isBlank()) {
            this.expectedToken = null;
            log.warn("★ 审计提交入口认证已关闭 (km.audit.ingest.token 为空) —— "
                    + "任何人都能写入/伪造审计记录, 只应在本地开发时这样配");
            return;
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
        if (INSECURE_DEFAULT_TOKEN.equals(token)) {
            log.warn("★ 审计提交入口仍在使用开发默认 token —— 生产环境必须用 "
                    + "KM_AUDIT_INGEST_TOKEN 覆盖 (这个默认值在 git 里, 不是秘密)");
        }
    }

    /**
     * 只处理 {@code /api/audit} 与其子路径。
     *
     * <p>用 {@code requestURI} 而不是 {@code servletPath}: 前者总是有值,
     * 后者在部分容器/映射方式下可能是空串。先把 contextPath 去掉再判断,
     * 这样即便应用挂在非根路径下也成立。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return !(path.equals(AUDIT_PATH) || path.startsWith(AUDIT_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 空配置 = 明确关闭 (见类注释)。放在 doFilter 里面而不是
        // shouldNotFilter 里, 是为了让"关闭"这个决定和"校验"出现在同一处。
        if (expectedToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(AuditHeaders.INGEST_TOKEN);
        byte[] actual = provided == null
                ? new byte[0]
                : provided.getBytes(StandardCharsets.UTF_8);

        if (MessageDigest.isEqual(expectedToken, actual)) {
            filterChain.doFilter(request, response);
            return;
        }

        // ★ 不把 token 的值写进日志, 也不区分"没带"和"带错了" ★
        // 两者对调用方来说都是同一个动作 (去检查配置), 而区分开只会让
        // 日志里偶尔出现真实 token 的前缀 —— 那是一种不必要的泄露。
        log.warn("审计提交被拒: {} 缺失或不匹配 path={} remote={}",
                AuditHeaders.INGEST_TOKEN, request.getRequestURI(), request.getRemoteAddr());

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("""
                {"error":"UNAUTHORIZED","message":"审计提交需要有效的 %s"}"""
                .formatted(AuditHeaders.INGEST_TOKEN));
    }
}
