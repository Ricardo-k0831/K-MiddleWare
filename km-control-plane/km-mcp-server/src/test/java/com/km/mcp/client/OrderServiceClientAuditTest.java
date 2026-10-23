package com.km.mcp.client;

import com.km.audit.AuditEvent;
import com.km.audit.AuditLayer;
import com.km.mcp.audit.AuditCapture;
import com.km.mcp.audit.TaskIdentity;
import com.km.mcp.client.OrderServiceClient.OrderServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * LEGACY_SERVICE 那一层审计 —— 四层里唯一"网关代存量服务记"的一层。
 *
 * <p>这一层最值得测的不是"记没记", 而是<b>它和 MCP_GATEWAY 那条刻意不重复</b>:
 * 这里只记"向后端发了什么、它花了多久", 不记响应体 —— 响应体已经由
 * MCP_GATEWAY 那条记过了 (Agent 收到的就是它)。重复存一遍只会让审计表里
 * 多出一倍的大字段, 而不会多出任何信息。
 *
 * <p>用 {@link MockRestServiceServer} 假掉 HTTP: 要验的是"失败时怎么记",
 * 而失败路径 (404 / 500 / 连不上) 用真后端很难稳定复现。
 */
class OrderServiceClientAuditTest {

    private static final TaskIdentity IDENTITY = new TaskIdentity("task-1", "trace-1", "km", "u-1");

    private final AuditCapture capture = new AuditCapture();

    private MockRestServiceServer backend;
    private OrderServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        backend = MockRestServiceServer.bindTo(builder).build();
        client = new OrderServiceClient(builder, "http://backend:8082", capture.publisher());
    }

    // ------------------------------------------------------------------
    //  成功
    // ------------------------------------------------------------------

    @Test
    void 查到订单时记一条LEGACY事件() {
        backend.expect(requestTo("http://backend:8082/api/orders/SO20260901001"))
                .andRespond(withSuccess("""
                        {"orderId":"SO20260901001","status":"PAID"}""", MediaType.APPLICATION_JSON));

        Map<String, Object> order = client.getOrderById("SO20260901001", IDENTITY);

        assertEquals("SO20260901001", order.get("orderId"));

        assertEquals(1, capture.count());
        AuditEvent event = capture.event(0);
        assertEquals(AuditLayer.LEGACY_SERVICE, event.layer());
        assertEquals("task-1", event.taskId());
        assertEquals("trace-1", event.traceId());
        assertEquals("u-1", event.actor());
        assertEquals(AuditEvent.OK, event.status());
        assertNull(event.errorMsg());
    }

    @Test
    void 审计里记的是真实发出的路径_不是模板() {
        backend.expect(requestTo("http://backend:8082/api/orders/SO20260901001"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getOrderById("SO20260901001", IDENTITY);

        // 记 /api/orders/{orderId} 的话, 查审计时看不出"到底查了哪个订单号" ——
        // 而那恰恰是排查时要看的。
        assertEquals("GET /api/orders/SO20260901001",
                capture.event(0).requestBody().get("endpoint"));
    }

    @Test
    void 不记响应体_那是MCP_GATEWAY那条的事() {
        backend.expect(requestTo("http://backend:8082/api/orders/SO20260901001"))
                .andRespond(withSuccess("""
                        {"orderId":"SO20260901001"}""", MediaType.APPLICATION_JSON));

        client.getOrderById("SO20260901001", IDENTITY);

        assertNull(capture.event(0).responseBody(),
                "响应体再存一遍只会多一倍大字段, 不会多出任何信息");
    }

    @Test
    void 列表查询也能记_尽管返回值是List() {
        // List 进不了 response_body (信封里那是 Map), 但这一条本来就只记调用,
        // 所以不该因为返回值是 List 就记不下来。
        backend.expect(requestTo("http://backend:8082/api/orders?userId=U1001"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> orders = client.listOrdersByUser("U1001", IDENTITY);

        assertTrue(orders.isEmpty());
        assertEquals("GET /api/orders?userId=U1001",
                capture.event(0).requestBody().get("endpoint"));
        assertEquals(AuditEvent.OK, capture.event(0).status());
    }

    // ------------------------------------------------------------------
    //  失败
    // ------------------------------------------------------------------

    @Test
    void 后端返回404记OK但要留下错误信息() {
        // 后端活着, 明确回答"没有这个东西" —— 这是它正常工作。
        // 但异常确实抛了, 那条线索要留下, 否则分不清"不存在"和"连不上"。
        backend.expect(requestTo("http://backend:8082/api/orders/SO-NOT-EXIST"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThrows(OrderServiceException.class,
                () -> client.getOrderById("SO-NOT-EXIST", IDENTITY));

        AuditEvent event = capture.event(0);
        assertEquals(AuditEvent.OK, event.status());
        assertNotNull(event.errorMsg());
        assertTrue(event.errorMsg().contains("NotFound"),
                "error_msg 里要能看出是 404 而不是别的, 实际: " + event.errorMsg());
    }

    @Test
    void 后端返回500记ERROR() {
        backend.expect(requestTo("http://backend:8082/api/orders/SO20260901001"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThrows(OrderServiceException.class,
                () -> client.getOrderById("SO20260901001", IDENTITY));

        assertEquals(AuditEvent.ERROR, capture.event(0).status());
    }

    @Test
    void 后端连不上记ERROR() {
        // 用一个抛 IOException 的 ResponseCreator 模拟"请求根本没发出去"。
        // 不用"不设 expect"来模拟: 那样 MockRestServiceServer 抛的是
        // AssertionError (Error 不是 Exception), 会直接穿透 OrderServiceClient
        // 的 catch, 测出来的东西和真实故障不是一回事。
        backend.expect(requestTo("http://backend:8082/api/orders/SO20260901001"))
                .andRespond(request -> {
                    throw new IOException("Connection refused");
                });

        assertThrows(OrderServiceException.class,
                () -> client.getOrderById("SO20260901001", IDENTITY));

        assertEquals(AuditEvent.ERROR, capture.event(0).status());
    }

    // ------------------------------------------------------------------
    //  身份
    // ------------------------------------------------------------------

    @Test
    void 匿名调用照样记_只是归不到任务下() {
        backend.expect(requestTo("http://backend:8082/api/orders/SO20260901001"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.getOrderById("SO20260901001", TaskIdentity.ANONYMOUS);

        assertEquals(1, capture.count());
        assertNull(capture.event(0).taskId());
    }
}
