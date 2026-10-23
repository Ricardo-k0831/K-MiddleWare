package com.km.task.web;

import com.km.task.domain.AgentTask;
import com.km.task.domain.AgentTaskEvent;
import com.km.task.service.TaskService;
import com.km.task.web.dto.CreateTaskRequest;
import com.km.task.web.dto.ReportStatusRequest;
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
 * </pre>
 *
 * <p>PATCH 的 409 有两种含义, 用错误码区分: {@code ILLEGAL_TRANSITION}
 * (状态不能这么变) 和 {@code EXECUTION_MISMATCH} (traceId 不属于这个任务)。
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
}
