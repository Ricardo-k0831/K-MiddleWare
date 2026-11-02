package com.km.task.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/tasks/{id}/heartbeat 的请求体。
 *
 * <pre>
 * { "traceId": "cffac16e..." }
 * </pre>
 *
 * <h3>为什么只有 traceId</h3>
 *
 * <p>心跳要回答的只有一件事: <b>哪一次执行</b>还活着。任务 id 在路径里,
 * 状态由 Java 自己查 —— 心跳不是状态上报, 不能带 toStatus, 更不能带 detail。
 * 请求体越薄, 越不容易被当成"另一种 PATCH"来用。
 *
 * <p>traceId 必填, 理由和 PATCH 一样: 缺了就只能让 Java 去猜"大概是最新那次吧",
 * 而猜错不会报错 —— 它会给另一条执行续命。宁可 400。
 */
public record HeartbeatRequest(

        @NotBlank(message = "traceId 不能为空, 它标识这次执行")
        String traceId
) {
}
