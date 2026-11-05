package com.km.task.web.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /api/tasks/{id}/cancel 的请求体。整个请求体<b>可省略</b>。
 *
 * <pre>
 * { "reason": "用户改主意了 / 上游超时, 人工停掉" }
 * </pre>
 *
 * <p>和 {@link RetryTaskRequest} 同一个理由: 触发取消这个动作已经足够表达
 * 意图, 强制填理由只会让人写 "cancel"。理由会原样进事件 detail 和审计
 * requestBody —— 事后靠它回答"这个任务是谁、为什么停的"。
 *
 * <p><b>没有 force 之类的字段</b>: 能不能取消由状态机决定 (任何非终态都可),
 * 让调用方用参数绕过规则, 等于把状态机变成建议。真要处理异常态, 应当先
 * 查明为什么它停不到正常状态, 而不是加一个后门。
 */
public record CancelTaskRequest(

        @Size(max = 512, message = "reason 太长 (最多 512 字符)")
        String reason
) {
}
