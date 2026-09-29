package com.km.mock.controller;

import com.km.mock.model.Order;
import com.km.mock.model.Order.OrderStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 存量订单服务的 HTTP 接口。
 *
 * <p>数据用内存 Map 造, 不接数据库 —— Phase1 的目的是验证「Agent 能否安全调用
 * 存量业务接口」这条链路, 不是写业务。数据库接进来会让调试面变大但没有信息量。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /** 造几条假订单, 保证 Agent 一定能查到东西 */
    private static final Map<String, Order> SEED = new ConcurrentHashMap<>();

    static {
        SEED.put("SO20260901001", new Order("SO20260901001", "U1001", "机械键盘 87 键",
                new BigDecimal("499.00"), OrderStatus.PAID, LocalDateTime.now().minusDays(2)));
        SEED.put("SO20260901002", new Order("SO20260901002", "U1001", "人体工学椅",
                new BigDecimal("1899.00"), OrderStatus.SHIPPED, LocalDateTime.now().minusDays(5)));
        SEED.put("SO20260902003", new Order("SO20260902003", "U1002", "27寸 4K 显示器",
                new BigDecimal("2499.00"), OrderStatus.PENDING_PAYMENT, LocalDateTime.now().minusHours(6)));
        SEED.put("SO20260903004", new Order("SO20260903004", "U1002", "USB-C 扩展坞",
                new BigDecimal("299.00"), OrderStatus.CANCELLED, LocalDateTime.now().minusDays(9)));
    }

    /**
     * 按订单号查询订单。
     *
     * <p>注意返回值刻意不包一层 {code, data, msg} 统一响应体 ——
     * 存量系统常见那种包装会让 LLM 多理解一层, 也容易让工具描述更难写。
     */
    @GetMapping("/{orderId}")
    public Order getOrderById(@PathVariable String orderId) {
        Order order = SEED.get(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    /**
     * 查询某个用户的所有订单。
     *
     * <p>这个接口用来演示「工具描述决定 Agent 选工具的准确率」:
     * 用户说"我最近买了啥", Agent 该选 listOrdersByUser 而不是 getOrderById。
     */
    @GetMapping
    public List<Order> listOrdersByUser(@RequestParam String userId) {
        return SEED.values().stream()
                .filter(o -> o.userId().equals(userId))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    /** 存量系统的异常处理, 让找不到订单返回 404 而不是 500 */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(OrderNotFoundException ex) {
        return ResponseEntity.status(404)
                .body(Map.of("error", ex.getMessage()));
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(String orderId) {
            super("订单不存在: " + orderId);
        }
    }
}
