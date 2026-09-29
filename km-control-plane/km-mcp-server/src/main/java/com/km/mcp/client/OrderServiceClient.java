package com.km.mcp.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 调用"存量订单微服务"的客户端。
 *
 * <p>这一层刻意做得很薄, 但它是整个架构里最值得讲的部分之一:
 * 网关屏蔽了后端服务的具体形态。今天后端是 HTTP 的 km-mock-order-service,
 * 明天换成 Dubbo / gRPC 的存量服务, 只要改这一个类,
 * 上面的 {@link com.km.mcp.tool.OrderMcpTools 工具定义}和 Python Agent 完全无感。
 */
@Component
public class OrderServiceClient {

    private final RestClient restClient;

    public OrderServiceClient(
            RestClient.Builder builder,
            @Value("${km.backend.order-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * 按订单号查订单。
     *
     * @return 订单原始 JSON。这里返回 Map 而不是强类型对象, 是因为
     *         网关不应该对后端服务的字段做假设 —— 存量服务改字段时网关不用跟着改。
     * @throws OrderServiceException 后端返回 404 或调用失败时
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getOrderById(String orderId) {
        try {
            return restClient.get()
                    .uri("/api/orders/{orderId}", orderId)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception ex) {
            throw new OrderServiceException("查询订单失败: " + orderId, ex);
        }
    }

    /**
     * 查某个用户的所有订单。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listOrdersByUser(String userId) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/orders")
                            .queryParam("userId", userId)
                            .build())
                    .retrieve()
                    .body(List.class);
        } catch (Exception ex) {
            throw new OrderServiceException("查询用户订单失败: " + userId, ex);
        }
    }

    /** 后端调用失败的统一包装 */
    public static class OrderServiceException extends RuntimeException {
        public OrderServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
