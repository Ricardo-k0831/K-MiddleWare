package com.km.task.web;

import com.km.task.domain.AgentTask;
import com.km.task.domain.AgentTaskEvent;
import com.km.task.service.TaskService;
import com.km.task.web.dto.CreateTaskRequest;
import com.km.task.web.dto.ReportStatusRequest;
import com.km.task.web.dto.RetryTaskRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务服务的 HTTP 入口。
 *
 * <pre>
 *   POST   /api/tasks                  创建任务          -> 201
 *   GET    /api/tasks/{id}             查询任务          -> 200 / 404
 *   GET    /api/tasks/{id}/events      状态流转历史      -> 200 / 404
 *   PATCH  /api/tasks/{id}/status      状态上报          -> 200 / 400 / 404 / 409
 *   POST   /api/tasks/{id}/retry       失败任务重试      -> 200 / 404 / 409
 * </pre>
 *
 * <p>PATCH 的 409 有两种含义, 用错误码区分: {@code ILLEGAL_TRANSITION}
 * (状态不能这么变) 和 {@code EXECUTION_MISMATCH} (traceId 不属于这个任务)。
 *
 * <p>POST /retry 的 409 也有两种: {@code RETRY_NOT_ALLOWED} (不是 FAILED /
 * 还有执行未闭链 / 并发冲突) 和 {@code MAX_RETRY_EXCEEDED} (次数用尽)。
 * 它<b>不是</b>一次状态上报: Python 运行时不能自己重试, 只有控制面能开
 * 下一代执行 (新 traceId, 见 {@link TaskService#retry})。
 *
 * 职责边界: 这一层只做参数绑定和校验, 任何"能不能"的判断都在
 * {@link TaskService} / 状态机里。Controller 里出现 if 判断状态就是设计跑偏了。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    // 并且自动生成 trace_id
    public AgentTask create(@Valid @RequestBody CreateTaskRequest request) {
        return taskService.create(
                request.userId(),
                request.sessionId(),
                request.intent(),
                request.tenantId(),
                request.maxRetry());
    }

    @GetMapping("/{agentTaskId}")
    public AgentTask get(@PathVariable String agentTaskId) {
        return taskService.requireTask(agentTaskId);
    }

    @GetMapping("/{agentTaskId}/events")
    public List<AgentTaskEvent> events(@PathVariable String agentTaskId) {
        return taskService.listEvents(agentTaskId);
    }

    /**
     * 状态上报。Python 侧的 TaskStateReporter 调的就是这个。
     *
     * 返回更新后的整个任务而不是 204 —— 调用方(Agent 运行时)拿到最新状态
     * 可以顺手记日志, 省一次往返。上报本身是旁路, 多传一点数据不亏。
     */
    @PatchMapping("/{agentTaskId}/status")
    public AgentTask reportStatus(@PathVariable String agentTaskId,
                                  @Valid @RequestBody ReportStatusRequest request) {
        return taskService.reportStatus(
                agentTaskId,
                request.traceId(),
                request.toStatus(),
                request.sourceOrDefault(),
                request.detail());
    }

    /**
     * 失败任务重试: 回到 PENDING 并生成新的 traceId。
     *
     * <p>返回<b>更新后的整个任务</b>而不是只回一个 traceId。调用方 (人工脚本 /
     * 将来接收益处的调度器) 拿到的应当是"重试后的完整事实", 而不是一个还需要
     * 再 GET 一次的半成品 —— 多一次往返就多一个"重试成功但没读到新 trace"
     * 的失败窗口。
     *
     * <p>请求体可省略: "重试"这个动作本身就是全部输入。要记理由时再传
     * {@code {"reason": "..."}}。
     *
     * <p>用 200 而不是 201/202: 重试没有创建新资源 (同一个 taskId), 而且
     * 状态变更已经同步完成 —— 不是"已排队, 稍后执行"。
     */
    @PostMapping("/{agentTaskId}/retry")
    public AgentTask retry(@PathVariable String agentTaskId,
                           @Valid @RequestBody(required = false) RetryTaskRequest request) {
        return taskService.retry(agentTaskId, request == null ? null : request.reason());
    }
}
