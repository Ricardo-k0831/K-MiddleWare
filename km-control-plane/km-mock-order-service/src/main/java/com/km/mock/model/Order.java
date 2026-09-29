package com.km.mock.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单领域对象。
 *
 * <p>用 record 是为了让 Java 侧的对象结构天然贴近 MCP 工具返回的 JSON Schema,
 * 减少后面写 inputSchema / outputSchema 时的心智负担。
 *
 * @param orderId     订单号
 * @param userId      下单用户
 * @param productName 商品名
 * @param amount      金额
 * @param status      订单状态, 见 {@link OrderStatus}
 * @param createdAt   下单时间
 */
public record Order(
        String orderId,
        String userId,
        String productName,
        BigDecimal amount,
        OrderStatus status,
        LocalDateTime createdAt
) {

    public enum OrderStatus {
        /** 待支付 */
        PENDING_PAYMENT,
        /** 已支付, 待发货 */
        PAID,
        /** 已发货 */
        SHIPPED,
        /** 已完成 */
        COMPLETED,
        /** 已取消 */
        CANCELLED
    }
}
