package com.km.task.web.dto;

import com.km.audit.AuditEventType;
import com.km.audit.AuditLayer;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * POST /api/audit/events 的单条事件 —— Python 运行时提交审计的报文格式。
 *
 * <p>字段和 {@link com.km.audit.AuditEvent} 一一对应, 不增不减。中间少一个
 * 字段就意味着这里要替调用方"猜"一个默认值, 而猜错一次, 以后所有历史数据
 * 都是错的。
 *
 * <pre>
 * [
 *   {
 *     "layer": "AGENT_RUNTIME",
 *     "eventType": "LLM_CALL",
 *     "taskId": "ce6756a0...", "traceId": "cffac16e...", "actor": "u-verify",
 *     "status": "OK", "latencyMs": 1840,
 *     "tokenIn": 812, "tokenOut": 96,
 *     "requestBody": { "model": "deepseek-chat", "messages": 3 },
 *     "occurredAt": 1760460812345
 *   }
 * ]
 * </pre>
 *
 * <h3>为什么 layer 由调用方声明, 而不是服务端按来源填</h3>
 *
 * <p>正常情况下当然应该由服务端认"谁调的我"来定层。但这里的调用方只有
 * Python 运行时一个, 而且 Java 和 Python 是两个进程、两种语言 —— 服务端没有
 * 可靠的办法区分它们 (同一个端口可以被任何客户端打)。与其做一套假的
 * 来源识别, 不如把这件事明说: <b>layer 是调用方自报的</b>。
 *
 * <p>这也是这个接口的边界: 它<b>不</b>防伪造。见 AuditController 的类注释。
 */
public record AuditEventRequest(

        /**
         * 采集层。Python 运行时填 AGENT_RUNTIME。
         *
         * <p>用枚举而不是字符串: 写错了在 400 就被挡住, 而不是等落库之后
         * 才发现有一批事件的 layer 是个谁也不认识的值 —— 那时只能靠 SQL
         * 去捞, 而按层统计已经不准了。
         */
        @NotNull(message = "layer 不能为空")
        AuditLayer layer,

        @NotNull(message = "eventType 不能为空")
        AuditEventType eventType,

        /** 归属任务。非任务链路下可以为空 —— 这条事件就成了匿名记录。 */
        String taskId,

        String traceId,

        String tenantId,

        /** 操作者。在 Agent 运行时这边就是任务上的 userId。 */
        String actor,

        /**
         * 结果, 缺省按 OK。
         *
         * <p>传的是字符串常量而不是枚举, 和 {@code AuditEvent} 保持一致 ——
         * 那一列在四层里是同一套 OK / ERROR / TIMEOUT / DEGRADED。
         */
        String status,

        Integer latencyMs,

        /** 以下两个只有 LLM_CALL 有值。 */
        Integer tokenIn,
        Integer tokenOut,

        Map<String, Object> requestBody,

        Map<String, Object> responseBody,

        String errorMsg,

        /**
         * 事件发生时刻, epoch millis。
         *
         * <p>不传就按服务端收到的时间算。但 Python 应该传 —— <b>HTTP 传输
         * 加上排队, 这里和服务端时间能差出好几百毫秒</b>, 而审计的时间线
         * 一旦跨层比对就露馅了 (网关那条比 Agent 那条还早, 看着像穿越)。
         */
        Long occurredAt,

        /**
         * 幂等键。不传由服务端生成。
         *
         * <p><b>生产者自己传更好</b>: 只有生产端知道"这两条是不是同一次观察"。
         * 服务端生成的 ID 每次都不一样, 于是重试 = 多一条重复记录。
         *
         * <p>MQ 那一层的重投去重不受影响 (eventId 在入口就定了), 所以这个
         * 字段现在没有也活得下去 —— 它是留给"Python 侧将来加重试"的。
         */
        String eventId
) {

    public String statusOrDefault() {
        return status == null || status.isBlank() ? "OK" : status;
    }
}
