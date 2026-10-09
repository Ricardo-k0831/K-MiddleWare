package com.km.task.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/tasks 的请求体。
 *
 * 用 record 而不是 class: 它是个纯数据载体, 没有行为, 不需要 setter,
 * 而且 record 天然不可变 —— 请求对象被后续代码改掉是 bug 的温床。
 */
public record CreateTaskRequest(

        @NotBlank(message = "userId 不能为空")
        @Size(max = 64, message = "userId 太长")
        String userId,

        @Size(max = 64, message = "sessionId 太长")
        String sessionId,

        /** 用户原始输入 (脱敏后)。会存进 agent_task.intent, 用于审计回溯。 */
        String intent,

        @Size(max = 64, message = "tenantId 太长")
        String tenantId,

        @Min(value = 0, message = "maxRetry 不能为负")
        Integer maxRetry
) {
}
