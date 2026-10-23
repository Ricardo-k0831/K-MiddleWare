package com.km.mcp.config;

import com.km.audit.AuditHeaders;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 让网关"看得见任务身份"的装配 —— 本项目里<b>唯一</b>替换 MCP 传输层的地方。
 *
 * <h3>问题: 网关凭什么知道这次调用属于哪个任务</h3>
 *
 * MCP 协议里没有任务这个概念, 只有会话和 {@code tools/call}。而
 * {@code audit_log.agent_task_id} 这一列要求每条网关审计事件都得知道自己
 * 属于哪个任务。
 *
 * <p>协议给了出口但没有给默认值: {@code WebMvcStreamableServerTransportProvider}
 * 的 builder 上有一个 {@code contextExtractor} —— 每个 HTTP 请求进来时被调一次,
 * 用来把传输层的信息抽成 {@code McpTransportContext}, 之后这个上下文会
 * 顺着 Reactor context 流到工具方法里 (工具方法声明一个
 * {@code McpTransportContext} 参数就能拿到)。
 *
 * <p>它的默认值是 {@code request -> McpTransportContext.EMPTY}, 也就是
 * <b>"什么都不抽"</b>。所以不配这个 bean 的话, 工具方法里拿到的永远是空上下文,
 * 审计出来的 taskId 全是 null —— 而且不报任何错。
 *
 * <h3>为什么要整个替换掉 transport provider bean</h3>
 *
 * 因为 SDK 没给"只加一个 extractor"的口子 —— {@code contextExtractor} 只能在
 * builder 上设。Spring AI 的
 * {@code McpServerStreamableHttpAutoConfiguration} 会建一个默认的 provider,
 * 它上面裹了 {@code @ConditionalOnMissingBean} —— 只要这里先注册一个同类型的
 * bean, 那边就会自动让路。
 *
 * <p>代价是本类必须<b>原样复刻</b>那几条 builder 参数 (jsonMapper /
 * mcpEndpoint / keepAliveInterval / disallowDelete)。漏掉哪条, 哪条的
 * 配置项就静默失效 —— 比如漏了 {@code mcpEndpoint}, {@code /mcp} 就会变成
 * 默认路径, Python 客户端连不上, 而错误现象是 404, 完全指不到这里。
 *
 * <p>{@code maxSessions} / {@code sessionIdleTimeout} 没有被自动配置设置过,
 * 所以这里也不设, 保持默认 —— 复刻的意思是"和原来一样, 只多一个 extractor"。
 */
@Configuration
public class McpGatewayAuditConfig {

    private static final Logger log = LoggerFactory.getLogger(McpGatewayAuditConfig.class);

    /**
     * 传输层 provider。参数和 Spring AI 自动配置里那个逐一对齐, 只多一条
     * {@code contextExtractor}。
     *
     * <p>注意 {@code JsonMapper} 是构造函数注入的 —— 它来自 Spring AI 的
     * {@code McpServerJsonMapperAutoConfiguration}, 那里专门为 MCP 调过
     * 序列化行为, 不能在这里 {@code new JsonMapper()} 另起一个: 那样协议层
     * 和工具层的 JSON 语义会不一致。
     */
    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            JsonMapper jsonMapper,
            McpServerStreamableHttpProperties properties) {

        log.info("MCP 传输层已接管: endpoint={} 已挂载 KM 身份头抽取 "
                        + "(缺失的头不报错, 只会让该次调用在审计里没有 taskId)",
                properties.getMcpEndpoint());

        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(McpGatewayAuditConfig::extractKmIdentity)
                .build();
    }

    /**
     * 从 HTTP 头里把 KM 身份抽出来。
     *
     * <p>方法签名里的 {@code ServerRequest} 是 <b>Spring MVC 的函数式请求对象</b>,
     * 不是 servlet 的 {@code HttpServletRequest} —— Spring AI 这套传输实现建在
     * WebMvc 上。这两个类型很容易混, 混了的症状是编译期就报"参数类型不匹配",
     * 属于好排查的那一类。
     *
     * <h4>每个请求都会调一次, 包括不该带头的那些</h4>
     *
     * initialize 请求、DELETE 会话请求、GET 建流请求都会走这里, 它们
     * <b>本来就不该带任务头</b>。所以这里【不】对缺失的头做任何告警 ——
     * 真要提醒"漏带头"的地方是工具调用处 ({@code McpToolAudit}),
     * 只有那里才确定"这次必须知道是哪个任务"。
     */
    private static McpTransportContext extractKmIdentity(ServerRequest request) {
        HttpHeaders headers = request.headers().asHttpHeaders();

        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, AuditHeaders.TASK_ID, headers.getFirst(AuditHeaders.TASK_ID));
        putIfPresent(metadata, AuditHeaders.TRACE_ID, headers.getFirst(AuditHeaders.TRACE_ID));
        putIfPresent(metadata, AuditHeaders.TENANT_ID, headers.getFirst(AuditHeaders.TENANT_ID));
        putIfPresent(metadata, AuditHeaders.ACTOR, headers.getFirst(AuditHeaders.ACTOR));

        // 一个都没有时返回 EMPTY 而不是 create(空Map): EMPTY 是 SDK 提供的
        // 不可变单例, 语义也更准 —— "这次请求没有携带任何传输层元数据"。
        return metadata.isEmpty() ? McpTransportContext.EMPTY : McpTransportContext.create(metadata);
    }

    /**
     * 空值不放进 map。
     *
     * <p>纯粹是为了让 {@code metadata} 里只有真正存在的东西 —— 放一个
     * null 进去, {@code get} 出来还是 null, 但 {@code metadata.equals()}
     * 的形状变了, 测试里断言"匿名请求抽出来的就是 {@code McpTransportContext.EMPTY}"
     * 会莫名其妙地失败。
     *
     * <p>顺带记一个查过源码才敢写的事实:
     * {@code McpTransportContext.create(map)} 内部<b>不复制这个 map</b>,
     * 只是存引用 ({@code DefaultMcpTransportContext} 的构造函数直接把传进来的
     * map 赋给字段)。所以传进去之后不能再改它 —— 本方法是最后一个写它的地方,
     * 之后就没人碰了。
     */
    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
