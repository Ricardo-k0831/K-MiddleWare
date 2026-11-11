package com.km.task.auth;

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
import java.util.ArrayList;
import java.util.List;

/**
 * 控制面接口的认证 + 粗粒度授权, 只拦 {@code /api/tasks/**}。
 *
 * <p>审计入口的 {@code /api/audit/**} 有它自己的 {@code AuditIngestAuthFilter},
 * 两个过滤器的路径不重叠 —— 一个入口一套信任模型, 不要合并成"全局鉴权",
 * 那会让"某条路径漏配"变成"整个服务裸奔"。
 *
 * <h3>它回答两个问题, 只回答两个</h3>
 *
 * <ol>
 *   <li><b>你是谁</b> —— 密钥表里有没有这个 {@code X-KM-Api-Key};
 *       查到之后把 {@link CallerIdentity} 放进 request attribute。</li>
 *   <li><b>这类接口你能碰吗</b> —— 状态上报和心跳只允许 SERVICE。
 *       这是路径级的硬边界, 不需要看具体任务。</li>
 * </ol>
 *
 * <p>"你能不能看这个任务"是资源级的判断, 必须拿到任务之后才能做,
 * 所以不在过滤器里 —— 在 Controller 里调
 * {@link CallerIdentity#requireTaskVisible}。放在这里查库会把一次
 * HTTP 请求变成两次, 而且过滤器一旦开始懂业务, 它会慢慢长成第二个服务。
 *
 * <h3>为什么比较密钥用 MessageDigest.isEqual</h3>
 *
 * <p>和审计入口同一个理由: {@code String.equals} 的耗时和"猜对了几个字符"
 * 相关, 是一个可以被逐位试探的信号。这里对每个配置的密钥逐一做定长比较
 * (密钥数量很小, 没必要上哈希表 —— 哈希表反而会把"命中了哪一条"的时序
 * 暴露在桶查找上)。
 *
 * <h3>密钥不会出现在日志里</h3>
 *
 * <p>被拒时只记路径、来源地址和"缺失或不匹配"。把收到的值打进日志,
 * 等于把别人的密钥收集到自己更容易被看到的地方。
 */
@Component
public class ControlAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ControlAuthFilter.class);

    /** 调用方带密钥的头。Python 运行时侧有同名常量, 改名要两边一起改。 */
    public static final String API_KEY_HEADER = "X-KM-Api-Key";

    /** 这个过滤器管的路径。带结尾条件判断, 避免误伤 {@code /api/tasksxyz}。 */
    private static final String TASKS_PATH = "/api/tasks";

    /** 开发用默认密钥 —— 只用来在启动日志里提醒"你还没换"。 */
    static final String INSECURE_DEFAULT_USER_KEY = "dev-user-key-change-me";
    static final String INSECURE_DEFAULT_SERVICE_KEY = "dev-service-key-change-me";

    private final boolean enabled;

    /** 预先把 key 转成 byte[]: 每个请求只剩比较, 不用再编码。 */
    // 密钥表
    private final List<Entry> entries;

    public ControlAuthFilter(ControlAuthProperties properties) {
        //判断控制面认证是否开启
        this.enabled = properties.isEnabled();

        // 预先把 key 转成 byte[]: 每个请求只剩比较, 不用再编码。
        List<Entry> built = new ArrayList<>();
        for (ControlAuthProperties.ApiKey apiKey : properties.getKeys()) {
            //
            if (apiKey.getKey() == null || apiKey.getKey().isBlank()) {
                // 空 key 绝不入表: 否则"不带头的请求"和"带空头的请求"都可能被当成匹配, 等于认证形同虚设。
                log.warn("控制面密钥表里有一条空 key, 已跳过 (tenant={})", apiKey.getTenant());
                continue;
            }
            // key有值, 记录CallerIdentity调用方身份
            CallerIdentity identity = new CallerIdentity(
                    apiKey.getTenant(), apiKey.getUser(), apiKey.getRole());
            // 记录 Apikey.byte():CallerIdentity
            built.add(new Entry(apiKey.getKey().getBytes(StandardCharsets.UTF_8), identity));
        }
        this.entries = List.copyOf(built);

        if (!enabled) {
            log.warn("★ 控制面认证已关闭 (km.control-auth.enabled=false) —— "
                    + "任何人都能建任务、改状态、取消别人的执行, 只应在本地调试时这样配");
        } else if (entries.isEmpty()) {
            log.warn("★ 控制面认证已开启, 但密钥表为空 —— 所有 /api/tasks 请求都会被 401 拒绝");
        }
        for (Entry entry : entries) {
            // 默认密钥写死在 application.yml 里, 全世界都知道, 生产必须覆盖。
            // 直接比字节而不是比 user 名: 换了密钥但沿用同一个 user 是正常操作。
            if (MessageDigest.isEqual(entry.key(), INSECURE_DEFAULT_USER_KEY.getBytes(StandardCharsets.UTF_8))
                    || MessageDigest.isEqual(entry.key(), INSECURE_DEFAULT_SERVICE_KEY.getBytes(StandardCharsets.UTF_8))) {
                log.warn("★ 控制面仍在使用开发默认密钥 (user={}, role={}) —— 生产环境必须用 "
                                + "KM_CONTROL_USER_KEY / KM_CONTROL_SERVICE_KEY 覆盖",
                        entry.identity().userId(), entry.identity().role());
            }
        }
    }

    @Override
    // 判断请求路径是否是 /api/tasks 或 /api/tasks/*
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 获取请求路径
        String path = request.getRequestURI();
        // 获取 contextPath 前缀
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            // 去掉 contextPath 前缀
            path = path.substring(contextPath.length());
        }
        // 判断请求路径是否是 /api/tasks 或 /api/tasks/*
        return !(path.equals(TASKS_PATH) || path.startsWith(TASKS_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 开启控制面认证, 才需要后续认证
        if (!enabled) {
            // 认证关闭  设置身份: Controller 不区分"有没有鉴权", 一律从request attribute 读
            // 否则每个 Controller 都要写一遍判空,那才是真正的开关泄漏到业务层。
            request.setAttribute(CallerIdentity.ATTRIBUTE, CallerIdentity.system());
            filterChain.doFilter(request, response);
            return;
        }

        // 校验密钥API_KEY_HEADER
        // 成功, 记录CallerIdentity调用方身份
        CallerIdentity caller = authenticate(request);
        // 认证失败, 返回 401 错误响应
        if (caller == null) {
            log.warn("控制面请求被拒: {} 缺失或不匹配 method={} path={} remote={}",
                    API_KEY_HEADER, request.getMethod(), request.getRequestURI(),
                    request.getRemoteAddr());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                    "控制面接口需要有效的 " + API_KEY_HEADER + " 请求头");
            return;
        }

        // 请求路径不能是状态上报或心跳
        // 如果是,那也只有SERVICE能调用状态上报或心跳接口
        // 否则请求不批准, 返回 403 错误响应
        if (!roleAllows(caller, request)) {
            log.warn("控制面请求被拒: 角色不足 user={} role={} method={} path={}",
                    caller.userId(), caller.role(), request.getMethod(), request.getRequestURI());
            reject(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "这个接口只允许服务身份调用 (状态上报 / 心跳由运行时负责)");
            return;
        }
        //给请求request挂上CallerIdentity调用方身份
        request.setAttribute(CallerIdentity.ATTRIBUTE, caller);
        filterChain.doFilter(request, response);
    }

    // 请求头API_KEY_HEADER查验, 返回 null 表示没有匹配的密钥。
    private CallerIdentity authenticate(HttpServletRequest request) {
        // 从请求头获取 API_KEY_HEADER
        String provided = request.getHeader(API_KEY_HEADER);
        if (provided == null || provided.isEmpty()) {
            return null;
        }
        byte[] actual = provided.getBytes(StandardCharsets.UTF_8);
        // 逐个比较密钥表中的密钥是否匹配
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(entry.key(), actual)) {
                return entry.identity();
            }
        }
        return null;
    }

    /**
     * 路径级的角色边界。
     *
     * <p>用 {@code endsWith} 而不是正则匹配整条路径: 这两个后缀只可能出现在
     * {@code /api/tasks/{id}/...} 的末尾, 任务 ID 里不含斜杠。
     */

    private boolean roleAllows(CallerIdentity caller, HttpServletRequest request) {
        // 获取请求路径
        String path = request.getRequestURI();
        // 判断请求路径结尾是否是 /status 或 /heartbeat
        boolean runtimeOnly = path.endsWith("/status") || path.endsWith("/heartbeat");

        return !runtimeOnly || caller.isService();
    }

    // 拒绝请求, 返回 JSON 错误响应
    private static void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    // 密钥表中的密钥项 封装 apikey和 calleridentity
    private record Entry(byte[] key, CallerIdentity identity) {
    }
}
