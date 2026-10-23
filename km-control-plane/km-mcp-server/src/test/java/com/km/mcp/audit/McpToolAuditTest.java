package com.km.mcp.audit;

import com.km.audit.AuditEvent;
import com.km.audit.AuditEventType;
import com.km.audit.AuditLayer;
import com.km.audit.AuditProperties;
import com.km.audit.AuditPublisher;
import com.km.mcp.client.OrderServiceClient.OrderServiceException;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关层审计的规则测试 —— 一次工具调用应该留下什么样的一条记录。
 *
 * <p>没有 @SpringBootTest, 也不连 broker: 假生产者替掉了 RocketMQ。
 * 这里测的是<b>规则</b>, 不是连通性 —— 端到端那部分由
 * {@code scripts/verify_gateway_audit.py} 对着真服务验。
 */
class McpToolAuditTest {

    private final AuditCapture capture = new AuditCapture();
    private final McpToolAudit audit = new McpToolAudit(capture.publisher());

    private static final TaskIdentity IDENTITY =
            new TaskIdentity("task-1", "trace-1", "km", "u-1");

    // ------------------------------------------------------------------
    //  1. 成功路径
    // ------------------------------------------------------------------

    @Test
    void 成功时记一条MCP_GATEWAY事件_身份原样带上() {
        Map<String, Object> order = Map.of("orderId", "SO20260901001", "status", "PAID");

        Map<String, Object> result = audit.invoke(IDENTITY, "get_order_by_id",
                Map.of("orderId", "SO20260901001"),
                () -> order,
                ex -> Map.of("error", true));

        assertSame(order, result, "invoke 必须原样返回被包住的那个调用的结果");

        assertEquals(1, capture.count());
        AuditEvent event = capture.event(0);
        assertEquals(AuditLayer.MCP_GATEWAY, event.layer());
        assertEquals(AuditEventType.MCP_TOOL_CALL, event.eventType());
        assertEquals("task-1", event.taskId());
        assertEquals("trace-1", event.traceId());
        assertEquals("km", event.tenantId());
        assertEquals("u-1", event.actor());
        assertEquals(AuditEvent.OK, event.status());
        assertNull(event.errorMsg(), "成功时不该有错误信息");
        assertNotNull(event.latencyMs(), "耗时必须有值 (0 也要有, null 说明没测)");
    }

    @Test
    void 入参里同时记了工具名和参数() {
        audit.invoke(IDENTITY, "list_orders_by_user", Map.of("userId", "U1001"),
                () -> Map.of("count", 0), ex -> Map.of("error", true));

        Map<String, Object> request = capture.event(0).requestBody();
        assertEquals("list_orders_by_user", request.get("tool"),
                "光看参数是看不出调的哪个工具的 —— 而'哪个工具'恰恰是这条记录存在的原因");
        assertEquals(Map.of("userId", "U1001"), request.get("params"));
    }

    @Test
    void 出参就是Agent真正收到的那个对象() {
        Map<String, Object> friendly = Map.of("error", true, "message", "没有找到订单号");

        audit.invoke(IDENTITY, "get_order_by_id", Map.of("orderId", "X"),
                () -> {
                    throw new OrderServiceException("boom", notFound());
                },
                ex -> friendly);

        assertEquals(friendly, capture.event(0).responseBody(),
                "response_body 记的必须是 Agent 最后收到了什么, 不是内部调用返回了什么");
    }

    @Test
    void 成功路径不会调用降级逻辑() {
        AtomicBoolean fallbackCalled = new AtomicBoolean(false);

        audit.invoke(IDENTITY, "get_order_by_id", Map.of("orderId", "X"),
                () -> Map.of("ok", true),
                ex -> {
                    fallbackCalled.set(true);
                    return Map.of("error", true);
                });

        assertFalse(fallbackCalled.get(), "没失败就不该走降级路径");
    }

    // ------------------------------------------------------------------
    //  2. 失败路径 —— 这一层是重点
    // ------------------------------------------------------------------

    @Test
    void 后端挂了记为ERROR_且把根因写进errorMsg() {
        // 直接抛出的异常永远是包装过的 OrderServiceException, 它自己的 message
        // 是"查询失败: ...", 看不出到底是网络问题还是业务问题。
        // 能定位问题的是最里层那个异常。
        Map<String, Object> result = audit.invoke(IDENTITY, "get_order_by_id",
                Map.of("orderId", "X"),
                () -> {
                    throw new OrderServiceException("查询失败", new java.net.ConnectException("Connection refused"));
                },
                ex -> Map.of("error", true, "message", "后端异常"));

        assertEquals(Map.of("error", true, "message", "后端异常"), result);

        AuditEvent event = capture.event(0);
        assertEquals(AuditEvent.ERROR, event.status());
        assertNotNull(event.errorMsg());
        assertTrue(event.errorMsg().contains("ConnectException"),
                "error_msg 里要能看到根因类型, 实际是: " + event.errorMsg());
    }

    @Test
    void 后端明确回答404时_status是OK_但errorMsg仍然留着() {
        // ★ 这条最反直觉, 所以单独钉住 ★
        //
        // status 和 errorMsg 回答的是两个不同的问题:
        //   status   —— 这次调用算不算系统故障 (404 不算)
        //   errorMsg —— 这次调用到底抛了什么 (抛了就记)
        //
        // 硬把 errorMsg 清空的代价是丢掉 HttpClientErrorException$NotFound
        // 这条线索 —— 而它恰恰是区分"订单不存在"和"后端挂了"的唯一依据。
        audit.invoke(IDENTITY, "get_order_by_id", Map.of("orderId", "SO-NOT-EXIST"),
                () -> {
                    throw new OrderServiceException("查询失败", notFound());
                },
                ex -> Map.of("error", true, "message", "没有找到订单号"));

        AuditEvent event = capture.event(0);
        assertEquals(AuditEvent.OK, event.status(),
                "后端明确回了 404, 这是它正常工作, 不是故障");
        assertNotNull(event.errorMsg(),
                "但异常确实抛了, 这条线索要留下 —— 否则分不清'不存在'和'连不上'");
    }

    // ------------------------------------------------------------------
    //  3. 身份缺失
    // ------------------------------------------------------------------

    @Test
    void 没有任务身份时照样记录_只是taskId为空() {
        // 客户端漏带头, 或者有人直接 curl 调试。审计是旁路, 不能因为
        // 采集不到身份就让工具调不通 —— 该做的是让它显眼, 不是让它失败。
        Map<String, Object> result = audit.invoke(TaskIdentity.ANONYMOUS, "get_order_by_id",
                Map.of("orderId", "X"),
                () -> Map.of("ok", true),
                ex -> Map.of("error", true));

        assertEquals(Map.of("ok", true), result, "匿名调用必须照常返回");
        assertEquals(1, capture.count(), "匿名也要留下记录 —— 否则丢了线索还查不到");

        AuditEvent event = capture.event(0);
        assertNull(event.taskId());
        assertNull(event.traceId());
        assertNull(event.actor());
        assertEquals(AuditEvent.OK, event.status());
    }

    @Test
    void 传null身份也不炸() {
        assertDoesNotThrow(() -> audit.invoke(null, "get_order_by_id", Map.of("orderId", "X"),
                () -> Map.of("ok", true), ex -> Map.of("error", true)));
        assertNull(capture.event(0).taskId());
    }

    // ------------------------------------------------------------------
    //  4. 审计不许影响业务
    // ------------------------------------------------------------------

    @Test
    void 投递器炸了也不影响返回值() {
        // 审计是旁路。MQ 挂了、broker 抽风、序列化炸了 —— 业务该怎么走还怎么走。
        // AuditPublisher 自己承诺不抛异常, 但组包这段是 McpToolAudit 的代码,
        // 拼的是任意 Map, 所以这里还要再兜一层。
        McpToolAudit brokenAudit = new McpToolAudit(new ExplodingPublisher());

        Map<String, Object> result = assertDoesNotThrow(() ->
                brokenAudit.invoke(IDENTITY, "get_order_by_id", Map.of("orderId", "X"),
                        () -> Map.of("ok", true),
                        ex -> Map.of("error", true)));

        assertEquals(Map.of("ok", true), result);
    }

    @Test
    void 降级逻辑自己炸了_原异常要挂成suppressed_且仍然记一条审计() {
        // 降级函数本该只是拼一个 Map, 它抛异常说明是我们自己的 bug。
        // 这时两件事都不能丢: 病因 (后端为什么失败) 和证据 (记一条审计)。
        IllegalStateException backend = new IllegalStateException("后端炸了");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                audit.invoke(IDENTITY, "get_order_by_id", Map.of("orderId", "X"),
                        () -> {
                            throw backend;
                        },
                        ex -> {
                            throw new IllegalArgumentException("降级也炸了");
                        }));

        assertEquals(1, thrown.getSuppressed().length,
                "原始异常必须挂成 suppressed, 否则日志里只剩降级函数的报错, 病因消失");
        assertSame(backend, thrown.getSuppressed()[0]);

        assertEquals(1, capture.count(), "降级炸了也要留下审计 —— 出问题时它比堆栈有用");
        assertEquals(AuditEvent.ERROR, capture.event(0).status());
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    private static HttpClientErrorException notFound() {
        return HttpClientErrorException.create(
                HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, null, null);
    }

    /** publish 直接抛异常的投递器 —— 用来验证"审计炸了业务也不受影响"。 */
    private static final class ExplodingPublisher extends AuditPublisher {

        ExplodingPublisher() {
            super(new AuditProperties(), JsonMapper.builder().build());
        }

        @Override
        public void publish(AuditEvent event) {
            throw new IllegalStateException("模拟投递器内部崩溃");
        }
    }
}
