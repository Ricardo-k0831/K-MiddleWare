package com.km.task.web.dto;

import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * PATCH /api/tasks/{id}/status 的请求体。
 *
 * <pre>
 * {
 *   "traceId": "cffac16e...",
 *   "toStatus": "TOOL_CALLING",
 *   "source": "PYTHON_RUNTIME",
 *   "detail": { "toolName": "get_order_by_id" }
 * }
 * </pre>
 *
 * <h3>★ traceId 是必填的, 而且必须和任务对得上 ★</h3>
 *
 * <p>它是<b>执行身份</b>: 这条状态变更属于哪一次执行。Java 拿它去
 * {@code agent_task_attempt} 里找到对应的执行, 再用那个执行上的 trace_id
 * 给 TASK_LIFECYCLE 审计事件盖章 —— <b>而不是从 {@code agent_task} 表里
 * 读"最近一次"的 trace</b>。
 *
 * <p>为什么不能从任务表读: 那是可变的滚动值。任务重试后, 第一遍执行
 * 在途的上报会被盖成第二遍的 trace, 按 trace 查链路就断了。溯源信息
 * 必须来自不可变的执行记录。
 *
 * <p>为什么不接受缺省: 缺省就只能靠推断, 而推断错的时候不会报错 ——
 * 审计里会出现一条链挂错任务, 单独看每一行都正常。宁可 400 让调用方
 * 当场发现。这和"不接受调用方声明 fromStatus"是同一条原则的另一面:
 * 前置状态以数据库为准, 但"我是哪次执行"必须由调用方证明。
 *
 * <p>注意这里【没有 fromStatus】。当前状态以数据库为准, 不接受调用方声明 ——
 * 否则一个状态过期的客户端就能把任务"改"回旧状态。真实的前置状态由
 * TaskService 自己读出, 并用 CAS 保证读到写之间没被别人改过。
 */
public record ReportStatusRequest(

        @NotBlank(message = "traceId 不能为空, 它是本次执行的身份")
        String traceId,

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
