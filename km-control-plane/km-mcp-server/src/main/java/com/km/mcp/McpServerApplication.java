package com.km.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP 工具网关。
 *
 * <p>启动后 Streamable HTTP 端点默认在 {@code http://localhost:8081/mcp}。
 *
 * <p>这个模块是整个项目的<b>安全边界</b>: Python 侧的 Agent 不允许直连业务微服务,
 * 所有对存量系统的调用都必须从这里过。所以 Phase2 的鉴权、参数白名单、
 * 限流、熔断、审计全部加在这一层, 而不是散落在各个 Agent 里。
 */
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
