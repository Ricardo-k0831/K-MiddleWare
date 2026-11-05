package com.km.task.web;

import com.km.task.cancel.TaskCancellationService;
import com.km.task.domain.AgentTask;
import com.km.task.domain.AgentTaskEvent;
import com.km.task.service.TaskService;
import com.km.task.web.dto.CancelTaskRequest;
import com.km.task.web.dto.CancelTaskResponse;
import com.km.task.web.dto.CreateTaskRequest;
import com.km.task.web.dto.HeartbeatRequest;
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
 *   POST   /api/tasks/{id}/cancel      主动取消任务      -> 200 / 404 / 409
 *   POST   /api/tasks/{id}/heartbeat   执行心跳          -> 204 / 400 / 404 / 409
 * </pre>
 *
 * <p>心跳和状态上报是两个动作, 所以是两个接口: 心跳不改状态、不写事件,
 * 它只回答"这次执行还活着吗" (见 {@link TaskService#heartbeat})。
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
    /**
     * 取消比普通状态变更多一步"通知运行时", 所以走编排服务而不是直接用
     * {@link TaskService} —— 其他入口仍然只依赖 TaskService。
     */
    private final TaskCancellationService cancellationService;

    public TaskController(TaskService taskService, TaskCancellationService cancellationService) {
        this.taskService = taskService;
        this.cancellationService = cancellationService;
    }

    // 请求路径 /api/tasks. 创建任务
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

    // 请求路径 /api/tasks/{id}. 查询任务
    @GetMapping("/{agentTaskId}")
    public AgentTask get(@PathVariable String agentTaskId) {
        return taskService.requireTask(agentTaskId);
    }

    // 请求路径 /api/tasks/{id}/events. 查询任务状态流转历史
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

    /**
     * 主动取消任务。
     *
     * <p>任何<b>非终态</b>都可以取消 (PENDING 是"派发前反悔", THINKING /
     * TOOL_CALLING 是"跑到一半停掉")。已经 CANCELLED 的任务<b>幂等</b>返回
     * 200 —— 取消是故障时最可能被连点的按钮, 让它 409 只会制造第二层混乱。
     * COMPLETED / FAILED 返回 409 (已经结束, 没有可停的东西)。
     *
     * <p>顺序固定: 先落库 (状态 + 事件 + 闭链 + 审计, 一个事务), 再通知
     * Python 停掉运行并收尾 checkpoint 的挂起。通知失败不会让接口失败,
     * 结果写在响应的 {@code runtime} 字段里 —— 详见
     * {@link CancelTaskResponse} 的类注释。
     *
     * <p>请求体可省略: {@code {"reason": "..."}} 只是给事件/审计留个理由。
     */
    @PostMapping("/{agentTaskId}/cancel")
    public CancelTaskResponse cancel(@PathVariable String agentTaskId,
                                     @Valid @RequestBody(required = false) CancelTaskRequest request) {
        return cancellationService.cancel(agentTaskId, request == null ? null : request.reason());
    }

    /**
     * 执行心跳。Python 运行时在执行期间定时调用。
     *
     * <p>返回 204 而不是回任务快照: 心跳是高频旁路 (默认 15s 一次),
     * 调用方不需要任何返回值 —— 它只会看状态码来决定"要不要继续跳"。
     * 4xx 的含义是"别跳了" (trace 不对 / 执行已结束), 不是"重试一下"。
     */
    @PostMapping("/{agentTaskId}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@PathVariable String agentTaskId,
                          @Valid @RequestBody HeartbeatRequest request) {
        taskService.heartbeat(agentTaskId, request.traceId());
    }
}
