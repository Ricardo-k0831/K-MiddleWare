package com.km.task.web.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /api/tasks/{id}/retry 的请求体。整个请求体<b>可省略</b>。
 *
 * <pre>
 * { "reason": "MCP 网关 500, 人工确认后重试" }
 * </pre>
 *
 * <p>只有 {@code reason} 一个字段, 而且不是必填: 触发重试这个动作本身
 * 已经足够表达意图, 强制填理由只会让人写 "retry" 这种无信息量的值。
 * 它会被原样写进 {@code agent_task_event.detail} 和审计的 requestBody ——
 * 事后看"这次重试是谁、为什么发起"时, 有它比没有好。
 *
 * <p><b>没有 traceId / attemptNo 字段</b>: 新的 trace 由 Java 生成, 新的
 * attempt_no 由 {@code retry_count} 推导。让调用方传这些值, 等于允许它
 * 指定"这次重试属于哪条链" —— 而重试的全部意义就是<b>控制面</b>决定
 * 链的边界。这和状态上报必填 traceId 不矛盾: 上报是"自证身份",
 * 重试是"申请新身份"。
 */
public record RetryTaskRequest(

        @Size(max = 512, message = "reason 太长 (最多 512 字符)")
        String reason
) {
}
