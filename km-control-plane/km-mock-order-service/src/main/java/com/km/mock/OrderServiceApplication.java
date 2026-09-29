package com.km.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 模拟存量订单微服务。
 *
 * <p>存在的意义: 证明「业务微服务零改造就能被 Agent 调用」。
 * 这个类里看不到任何 MCP / AI 相关的代码, 这正是设计目标 ——
 * 适配 MCP 协议的成本全部由 km-mcp-server 网关承担。
 */
@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
