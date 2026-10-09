package com.km.task.web.dto;

import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * PATCH /api/tasks/{id}/status 的请求体。
 *
 * <pre>
 * {
 *   "toStatus": "TOOL_CALLING",
 *   "source": "PYTHON_RUNTIME",
 *   "detail": { "toolName": "get_order_by_id" }
 * }
 * </pre>
 *
 * 注意这里【没有 fromStatus】。当前状态以数据库为准, 不接受调用方声明 ——
 * 否则一个状态过期的客户端就能把任务"改"回旧状态。真实的前置状态由
 * TaskService 自己读出, 并用 CAS 保证读到写之间没被别人改过。
 */
public record ReportStatusRequest(

        @NotNull(message = "toStatus 不能为空")
        TaskStatus toStatus,

        /** 缺省按 PYTHON_RUNTIME 处理 —— 这个接口的主要调用方就是 Agent 运行时。 */
        TaskEventSource source,

        /** 变更上下文。会被原样写进 agent_task_event.detail (JSON 列)。 */
        Map<String, Object> detail
) {

    /** source 的缺省值。让调用方不必每次都重复写一个显然的值。 */
    public TaskEventSource sourceOrDefault() {
        return source == null ? TaskEventSource.PYTHON_RUNTIME : source;
    }
}
