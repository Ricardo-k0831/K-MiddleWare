package com.km.mcp.client;

import com.km.audit.AuditEvent;
import com.km.audit.AuditEventType;
import com.km.audit.AuditLayer;
import com.km.audit.AuditPublisher;
import com.km.mcp.audit.CallStatus;
import com.km.mcp.audit.TaskIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 调用"存量订单微服务"的客户端。
 *
 * <p>这一层刻意做得很薄, 但它是整个架构里最值得讲的部分之一:
 * 网关屏蔽了后端服务的具体形态。今天后端是 HTTP 的 km-mock-order-service,
 * 明天换成 Dubbo / gRPC 的存量服务, 只要改这一个类,
 * 上面的 {@link com.km.mcp.tool.OrderMcpTools 工具定义}和 Python Agent 完全无感。
 *
 * <h3>本类是 LEGACY_SERVICE 那一层审计的采集点</h3>
 *
 * 四层审计里最特别的一层, 因为它的采集方式和其它三层不同:
 * 存量服务<b>一行代码都不用改</b>, 也不可能自己去写审计 —— 是网关
 * "代它记"的。这是"零改造接入"能被审计证明的关键。
 *
 * <p>为什么采集点在这里而不是在 {@code OrderMcpTools} 里:
 * 只有本类知道<b>真正发出的 HTTP 请求</b>长什么样 (路径、参数、真实状态码),
 * 也只有它测得到<b>后端自己的</b>耗时。工具那层的耗时包含网关开销,
 * 两者一减才知道慢在谁身上。
 *
 * <p>所以一次工具调用会产生<b>两条</b>审计: {@code MCP_GATEWAY} 一条
 * (Agent 调了什么、拿到了什么) 和 {@code LEGACY_SERVICE} 一条
 * (向后端发了什么、它花了多久)。它们不是重复 —— 现在工具和后端是一对一,
 * 看起来像; 等出现"一个工具扇出到三个后端服务"时, 区别就出来了。
 *
 * <h3>为什么 identity 要显式传进来, 而不是用 ThreadLocal 存</h3>
 *
 * 身份来自 HTTP 头, 天然是"请求级"的, 存在 ThreadLocal 里最省事。
 * 但工具调用和本类之间隔着线程池 —— MCP 的 streamable 传输内部要过 Reactor,
 * 一旦哪条路径换线程, ThreadLocal 就静默取到空, 症状是<b>审计时有时无</b>,
 * 且只在并发下复现。显式传参不会出这种问题, 代价只是方法签名多一个参数。
 */
@Component
public class OrderServiceClient {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceClient.class);

    private final RestClient restClient;
    private final AuditPublisher audit;

    public OrderServiceClient(
            RestClient.Builder builder,
            @Value("${km.backend.order-service.base-url}") String baseUrl,
            AuditPublisher audit) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.audit = audit;
    }

    /**
     * 按订单号查订单。
     *
     * @param orderId  订单号
     * @param identity 这次调用属于哪个任务; 允许是匿名的 ({@code TaskIdentity.ANONYMOUS}),
     *                 那只是让这条审计归不到任务下, 不影响调用本身
     * @return 订单原始 JSON。这里返回 Map 而不是强类型对象, 是因为
     *         网关不应该对后端服务的字段做假设 —— 存量服务改字段时网关不用跟着改。
     * @throws OrderServiceException 后端返回 404 或调用失败时
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getOrderById(String orderId, TaskIdentity identity) {
        // 审计里记的是【真实发出的路径】, 不是 /api/orders/{orderId} 这种模板 ——
        // 模板查不出"到底查了哪个订单号", 而那恰恰是排查时要看的。
        String endpoint = "GET /api/orders/" + orderId;
        return call(identity, endpoint, Map.of("orderId", orderId),
                () -> restClient.get()
                        .uri("/api/orders/{orderId}", orderId)
                        .retrieve()
                        .body(Map.class));
    }

    /**
     * 查某个用户的所有订单。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listOrdersByUser(String userId, TaskIdentity identity) {
        String endpoint = "GET /api/orders?userId=" + userId;
        return call(identity, endpoint, Map.of("userId", userId),
                () -> restClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/api/orders")
                                .queryParam("userId", userId)
                                .build())
                        .retrieve()
                        .body(List.class));
    }

    /**
     * 一次后端调用的完整包装: 计时 → 发起 → 记审计 → 原样返回或原样抛出。
     *
     * <p>把两个方法的 try/catch 收在这里, 顺带把审计也做了 —— 比起在两个方法里
     * 各抄一遍计时和组包, 这样反而<b>更短</b>, 也不会出现"其中一个方法忘了记"。
     *
     * <p>语义上这个方法对调用方是透明的: 返回值、抛出的异常类型、异常链
     * 都和直接调 {@code httpCall.get()} 一样。审计是加在旁边的, 不是加在中间的。
     *
     * @param endpoint 人类可读的调用描述, 写进 {@code request_body}
     */
    private <T> T call(TaskIdentity identity,
                       String endpoint,
                       Map<String, Object> params,
                       Supplier<T> httpCall) {

        long startedAt = System.currentTimeMillis();
        long startNanos = System.nanoTime();

        try {
            T body = httpCall.get();
            publishLegacy(identity, endpoint, params, null, startedAt, startNanos);
            return body;
        } catch (Exception ex) {
            publishLegacy(identity, endpoint, params, ex, startedAt, startNanos);
            throw new OrderServiceException("查询失败: " + endpoint, ex);
        }
    }

    /**
     * 投递一条 LEGACY_SERVICE 审计。
     *
     * <p><b>刻意不记响应体</b>。响应体在同一轮里已经由 {@code MCP_GATEWAY} 那条
     * 事件记过了 (Agent 收到的就是它), 再存一份只会让审计表里多出一倍的
     * 大字段, 而不会多出任何信息。这一条要回答的是另一个问题:
     * <b>"存量服务被调用了, 成没成, 花了多久"</b> —— 详情不在这条里。
     *
     * <p>整段裹在 catch 里, 理由和别处一样: 审计不许把业务弄挂。
     */
    private void publishLegacy(TaskIdentity identity,
                               String endpoint,
                               Map<String, Object> params,
                               Throwable failure,
                               long startedAt,
                               long startNanos) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("endpoint", endpoint);
            request.put("params", params == null ? Map.of() : params);

            String status = CallStatus.of(failure);
            if (!AuditEvent.OK.equals(status)) {
                log.warn("存量服务调用异常 endpoint={} status={} 原因={}",
                        endpoint, status, failure.toString());
            }

            AuditEvent.Builder builder = AuditEvent.builder()
                    .layer(AuditLayer.LEGACY_SERVICE)
                    .eventType(AuditEventType.MCP_TOOL_CALL)
                    .actor(identity == null ? null : identity.actor())
                    .status(status)
                    .latencyMs((int) Math.min((System.nanoTime() - startNanos) / 1_000_000L,
                            Integer.MAX_VALUE))
                    .requestBody(request)
                    .errorMsg(failure == null ? null : failure.toString())
                    .occurredAt(startedAt);

            if (identity != null) {
                builder.taskId(identity.taskId())
                        .traceId(identity.traceId())
                        .tenantId(identity.tenantId());
            }

            audit.publish(builder.build());

        } catch (Throwable ex) {
            log.error("组 LEGACY_SERVICE 审计事件时出错, 本次事件丢失 (业务不受影响) endpoint={}",
                    endpoint, ex);
        }
    }

    /** 后端调用失败的统一包装 */
    public static class OrderServiceException extends RuntimeException {
        public OrderServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
