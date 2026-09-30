package com.km.mcp.tool;

import com.km.mcp.client.OrderServiceClient;
import com.km.mcp.client.OrderServiceClient.OrderServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 把存量订单服务的能力, 声明成 MCP 工具。
 *
 * <p>这个类就是"存量微服务零改造接入 Agent"的落点: 业务代码一行没动,
 * 只是在这里加了一层声明式包装。
 *
 * <h3>写工具描述的三个要点 (这是 prompt 工程, 不是写注释)</h3>
 * <ol>
 *   <li><b>description 是给 LLM 看的</b>, 直接决定它选不选这个工具。要写清楚
 *       "什么时候该用我", 而不是"我做了什么"。</li>
 *   <li><b>参数描述要给出取值示例</b>。LLM 不知道你的订单号长什么样,
 *       写 {@code SO20260901001} 比写"订单ID"有效得多。</li>
 *   <li><b>能拆开就别合并</b>。一个工具做一件事, Agent 的规划才准。</li>
 * </ol>
 *
 * <h3>为什么所有异常都要在这里吞掉</h3>
 * 工具抛出的异常会变成 MCP 协议错误返回给 Agent。Agent 看到的是一个
 * 技术性堆栈, 它没法从中恢复, 只会把错误原样抛给用户。
 * 转成自然语言的错误描述后, Agent 才有机会降级处理
 * (比如换个参数重试, 或告诉用户"订单号可能写错了")。
 *
 * <h3>★ 返回值必须是 JSON object, 不能是数组</h3>
 * 这条是硬约束, 违反它会以一种极难看懂的方式失败。
 *
 * <p>{@code generateOutputSchema = true} 时, Spring AI 把方法返回值直接
 * 塞进 MCP 的 {@code structuredContent} 字段。而 MCP 规范规定
 * {@code structuredContent} 必须是一个 JSON <b>object</b>。
 * 返回 {@code List} 时, Java 侧照发不误, 但 Python 侧 mcp 1.x 客户端的
 * {@code CallToolResult.structuredContent} 声明是 {@code dict[str, Any]},
 * pydantic 校验直接抛:</p>
 *
 * <pre>
 *   ValidationError: 1 validation error for CallToolResult
 *   structuredContent  Input should be a valid dictionary
 *     input_value=[{...}], input_type=list
 * </pre>
 *
 * <p>注意失败点在<b>客户端解析响应时</b>, 不在调用时 —— 所以 Java 日志里
 * 一切正常, 只有 Python 侧报错, 排查时极易怀疑错方向。</p>
 *
 * <p>绕法是包一层 object: {@code Map.of("count", n, "orders", list)}。
 * 将来加"库存列表""用户列表"之类的工具时同样要包。</p>
 */
@Component
public class OrderMcpTools {

    private static final Logger log = LoggerFactory.getLogger(OrderMcpTools.class);

    private final OrderServiceClient orderServiceClient;

    public OrderMcpTools(OrderServiceClient orderServiceClient) {
        this.orderServiceClient = orderServiceClient;
    }

    @McpTool(
            name = "get_order_by_id",
            description = """
                    根据订单号精确查询单个订单的详细信息, 包括商品、金额、订单状态和下单时间。
                    当用户提供了明确的订单号(形如 SO20260901001)时才使用这个工具。
                    如果用户只给了用户ID或姓名、没有给订单号, 请改用 list_orders_by_user。
                    """,
            generateOutputSchema = true
    )
    public Map<String, Object> getOrderById(
            @McpToolParam(required = true, description = "订单号, 形如 SO20260901001")
            String orderId) {

        log.info("[MCP] get_order_by_id orderId={}", orderId);
        try {
            return orderServiceClient.getOrderById(orderId);
        } catch (OrderServiceException ex) {
            // 不抛异常, 返回结构化的自然语言错误 —— 让 Agent 有能力自我纠正
            return Map.of(
                    "error", true,
                    "message", "没有找到订单号 " + orderId + " 对应的订单。请确认订单号是否正确, "
                            + "或者改用 list_orders_by_user 按用户查询。"
            );
        }
    }

    @McpTool(
            name = "list_orders_by_user",
            description = """
                    查询指定用户ID名下的所有订单列表, 按下单时间从新到旧排序。
                    当用户说"我最近买了什么""我的订单"但没有提供具体订单号时, 使用这个工具。
                    注意: 这里的 userId 是系统内部ID(形如 U1001), 不是用户名。
                    """,
            generateOutputSchema = true
    )
    public Map<String, Object> listOrdersByUser(
            @McpToolParam(required = true, description = "用户ID, 形如 U1001")
            String userId) {

        log.info("[MCP] list_orders_by_user userId={}", userId);
        try {
            List<Map<String, Object>> orders = orderServiceClient.listOrdersByUser(userId);
            // 必须包成 object, 不能直接返回 List —— 见类注释"返回值必须是 JSON object"
            return Map.of("count", orders.size(), "orders", orders);
        } catch (OrderServiceException ex) {
            // 注意: 这里不能返回空列表。后端挂了和"该用户确实没有订单"是两回事,
            // 返回空列表会让 Agent 把故障如实转述成"你没有订单", 是在骗用户。
            log.warn("[MCP] list_orders_by_user 失败 userId={}", userId, ex);
            return Map.of(
                    "error", true,
                    "message", "查询用户 " + userId + " 的订单时后端服务异常, 这不是"
                            + "\"该用户没有订单\"的意思。请稍后重试, 或告知用户稍后再查。"
            );
        }
    }
}
