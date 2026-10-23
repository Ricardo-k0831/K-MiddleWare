package com.km.mcp.audit;

import com.km.audit.AuditEvent;
import com.km.audit.AuditEventType;
import com.km.audit.AuditHeaders;
import com.km.audit.AuditLayer;
import com.km.audit.AuditPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 工具调用的审计采集点 —— <b>MCP_GATEWAY 那一层</b>。
 *
 * <h3>为什么不用 AOP 切 {@code @McpTool}</h3>
 *
 * 这是本类存在的主要理由。用切面切工具方法看起来最省事, 但在这个项目里
 * <b>会记出全错的审计</b>:
 *
 * <p>{@code OrderMcpTools} 会把所有异常吞掉, 转成一段自然语言的错误 Map
 * 返回给 Agent (这是刻意的, 见那个类的注释 —— Agent 拿到技术堆栈没法自愈)。
 * 于是从切面的视角看, 工具方法<b>永远正常返回</b>, 审计就永远记成 OK。
 * 结果是最该被看见的那些失败, 一条都没留下。
 *
 * <p>这不是切面写错了, 是切面放错了位置: 它切在"方法有没有抛异常"上, 而
 * 这里真正要观测的边界在<b>方法内部</b> —— 后端调用那一下。
 *
 * <h3>怎么用</h3>
 *
 * 把"调后端"和"失败时怎么降级"分开交给它, 中间那些计时/组包/投递全由它包办:
 * <pre>{@code
 * return audit.invoke(identity, "get_order_by_id", Map.of("orderId", orderId),
 *         () -> orderServiceClient.getOrderById(orderId, identity),
 *         ex -> Map.of("error", true, "message", "没有找到订单号 " + orderId));
 * }</pre>
 *
 * <p>这样做还有个额外的好处: 失败路径的<b>判断依据是异常对象本身</b>,
 * 不是工具方法返回的 Map 长什么样。所以"什么算失败"是一条能写下来、
 * 能测的规则 (见 {@link CallStatus}), 而不是"返回的 map 里有没有 error 键"
 * 这种靠约定维系的隐式耦合。
 */
@Component
public class McpToolAudit {

    private static final Logger log = LoggerFactory.getLogger(McpToolAudit.class);

    /** 没带任务身份的调用, 用这个占位记日志。 */
    private static final String UNKNOWN = "(未知)";

    private final AuditPublisher publisher;

    public McpToolAudit(AuditPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 包住一次工具调用: 计时 → 执行 → 采集出入参 → 投递审计 → 原样返回结果。
     *
     * <p><b>审计永远不影响返回值和异常行为</b>: 这个方法在功能上等价于
     * 直接调 {@code body.get()} —— 唯一的区别是顺带发了一条消息,
     * 而发消息这条路自己保证不抛异常 (见 {@link AuditPublisher})。
     *
     * @param identity  这次调用属于哪个任务; 匿名时字段为 null, 不影响记录
     * @param tool      MCP 工具名, 必须和 {@code @McpTool(name=...)} 一致 ——
     *                  它是审计里"Agent 到底调了什么"的唯一依据
     * @param params    给 LLM/工具看的入参。写进 {@code request_body}
     * @param body      真正的调用。抛出的异常会交给 {@code onFailure}
     * @param onFailure 失败时的降级逻辑 (通常是回一段自然语言错误)。
     *                  它拿得到异常对象, 所以能按类型给不同的话术
     * @return {@code body} 的结果, 或 {@code onFailure} 的结果
     */
    public <T> T invoke(TaskIdentity identity,
                        String tool,
                        Map<String, Object> params,
                        Supplier<T> body,
                        Function<Throwable, T> onFailure) {

        long startedAt = System.currentTimeMillis();
        long startNanos = System.nanoTime();

        T result;
        Throwable failure = null;
        try {
            // 执行对应的 MCP 工具调用
            // body是可执行代码集合, 可以是任意的调用逻辑, 也可以是异步调用等
            result = body.get();
        } catch (RuntimeException ex) {
            failure = ex;
            log.warn("[MCP] {} 后端调用失败, 已降级为自然语言错误 taskId={}",
                    tool, taskIdOrUnknown(identity), ex);

            // 先降级, 再审计
            try {
                // 降级: 把异常转成自然语言错误 Map
                result = onFailure.apply(ex);
            } catch (RuntimeException fallbackFailed) {
                // 降级逻辑自己炸了 —— 那是我们自己的 bug (它本该只是拼一个 Map)。
                //
                // 这里要挡住的是<b>病因被吃掉</b>: 不处理的话, 抛出去的就只剩
                // 降级函数那句报错, 而真正的原因 (后端为什么失败) 从日志里彻底消失。
                // 所以把它挂成 suppressed 一起带出去, 顺手还是记一条审计 ——
                // 出问题的时候, 那条记录往往比堆栈更有用。
                fallbackFailed.addSuppressed(ex);
                // 将异常类审计进行投递(result为空, 但是异常字段有值)

                publish(identity, tool, params, null, fallbackFailed, startedAt, elapsedMs(startNanos));
                throw fallbackFailed;
            }
        }

        publish(identity, tool, params, result, failure, startedAt, elapsedMs(startNanos));
        // 将 调用结果 或者 降级后异常 进行返回
        return result;
    }

    // ------------------------------------------------------------------
    //  内部
    // ------------------------------------------------------------------

    /**
     * 组包并投递。
     *
     * <p>整段裹在 catch 里: {@link AuditPublisher#publish} 自己承诺不抛异常,
     * 但<b>组包</b>这段是本类的代码, 而它拼的是任意 Map —— 万一哪个工具返回了
     * 一个 toString 会炸的对象 (或者 Map 里有自引用), 异常就会从这里冒到
     * 工具调用上。审计是旁路, 不能因为记不下来就把业务弄挂, 所以这里再兜一层。
     */
    // publish传递的不是整个固定的event, 而是结合所给字段进行内部组装AuditEvent
    private void publish(TaskIdentity identity,
                         String tool,
                         Map<String, Object> params,
                         Object result,
                         // 传入异常, 根据异常码判断本次审计事件状态
                         Throwable failure,
                         long startedAt,
                         Integer latencyMs) {
        try {
            if (identity != null && identity.isAnonymous()) {
                // 只提醒, 不拦。开发期这条日志应该很快消失; 一直在刷说明
                // Python 侧没在请求头上带 X-KM-Task-Id, 那条路上"哪个任务
                // 调了这个工具"这个核心问题就永远答不了。
                log.warn("[MCP] 工具调用缺少任务身份 (头 {} 为空), 本次审计将无法归属到任何任务 tool={}",
                        AuditHeaders.TASK_ID, tool);
            }
            // 构造 MCPServer 层发起的 MCP_TOOL_CALL 审计事件
            AuditEvent.Builder builder = AuditEvent.builder()
                    .layer(AuditLayer.MCP_GATEWAY)
                    .eventType(AuditEventType.MCP_TOOL_CALL)
                    .actor(identity == null ? null : identity.actor())
                    .status(CallStatus.of(failure))
                    .latencyMs(latencyMs)
                    // 输入: tool="get_order_by_id", params={orderId: "SO..."}
                    // 输出: {"tool": "get_order_by_id", "params": {"orderId": "SO..."}}
                    .requestBody(toolRequest(tool, params))
                    // asBody 把 result 归一化成 Map<String,Object>
                    // result = null          → {}          (空 Map, JSON 稳定)
                    // result = {orderId:...} → {orderId:...} (直接用)
                    // result = "某些字符串"    → {"value": "某些字符串"} (非 Map 包一层)
                    .responseBody(asBody(result))
                    // failure = new OrderServiceException("订单不存在", new SocketTimeoutException())
                    // 输出: "订单不存在 | 根因: java.net.SocketTimeoutException"
                    .errorMsg(messageOf(failure))
                    .occurredAt(startedAt);

            if (identity != null) {
                builder.taskId(identity.taskId())
                        .traceId(identity.traceId())
                        .tenantId(identity.tenantId());
            }

            publisher.publish(builder.build());

        } catch (Throwable ex) {
            // 用 Throwable 而不是 Exception: 这里的目标是"绝不让组包影响业务",
            // 而 NoClassDefFoundError / StackOverflowError 这类 Error 一样
            // 会把工具调用带走。
            log.error("[MCP] 组审计事件时出错, 本次事件丢失 (业务不受影响) tool={}",
                    tool, ex);
        }
    }

    /**
     * 工具调用的入参。
     *
     * <p>包一层而不是直接把参数 Map 塞进去: 这样 {@code request_body} 里
     * 永远有 {@code tool} 字段。只看一条记录时, 光凭入参是看不出调的哪个工具的
     * —— 而"哪个工具"恰恰是这条记录存在的原因。
     */
    private static Map<String, Object> toolRequest(String tool, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        // params 为 null 时也留个空对象而不是 null: 让下游的 JSON 结构稳定,
        // 不然查审计时要同时处理 {"params":{}} 和 {"params":null} 两种形状。
        body.put("params", params == null ? Map.of() : params);
        return body;
    }

    /**
     * 返回值的形状归一化。
     *
     * <p>{@code response_body} 在信封里是 {@code Map<String,Object>} ——
     * 因为审计的查询方式是"把这一列当 JSON 查", 顶层不是对象的话
     * JSON 路径查询就没法写了。所以非 Map 的返回值 (理论上 MCP 工具不该有,
     * 见 {@code OrderMcpTools} 关于 structuredContent 的注释) 在这里降级成一个
     * {@code {"value": ...}} 包装, 而不是丢掉。
     *
     * <p>返回值本身为 null 时返回空 Map: 见 {@link #toolRequest} 里同样的理由。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asBody(Object result) {
        if (result == null) {
            return Map.of();
        }
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of("value", result);
    }

    /**
     * 异常信息 -> {@code error_msg} 列。
     *
     * <p>带上了<b>根因</b>的类型名: 直接被抛出的永远是包装异常
     * ({@code OrderServiceException}), 它的 message 是自己人写的, 信息量有限。
     * 真正能定位问题的是最里层那个 —— 是 {@code ConnectException} 还是
     * {@code SocketTimeoutException}, 决定了该去查网络还是查性能。
     */
    private static String messageOf(Throwable failure) {
        if (failure == null) {
            return null;
        }
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause == failure) {
            return failure.toString();
        }
        return failure.getMessage() + " | 根因: " + cause;
    }

    private static String taskIdOrUnknown(TaskIdentity identity) {
        return identity == null || identity.taskId() == null ? UNKNOWN : identity.taskId();
    }

    /** 毫秒耗时。用 nanoTime 相减, 不受系统时钟调整影响。 */
    private static Integer elapsedMs(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }
}
