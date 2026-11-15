package com.km.task.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.km.task.auth.CallerIdentity;
import com.km.task.domain.AgentTask;
import com.km.task.domain.AuditLog;
import com.km.task.mapper.AuditLogMapper;
import com.km.task.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 一个任务的<b>执行轨迹</b> —— 把四层审计按时间顺序拼成一条链。
 *
 * <h3>为什么不复用 Python 侧的返回值</h3>
 *
 * <p>Python 返回的 {@code tool_calls} 是"Agent 想调什么"; 这里返回的是
 * "四层各自实际看到了什么"。两者差的正是评测最需要的那部分:
 *
 * <ul>
 *   <li>Agent 发起的调用, 网关有没有收到 (MCP_GATEWAY 行);</li>
 *   <li>网关转给了后端没有 (LEGACY_SERVICE 行) —— 被限流/熔断挡掉的调用
 *       只有 DEGRADED 一行, 没有这一行;</li>
 *   <li>LLM 调用了几次、各消耗多少 token (AGENT_RUNTIME 行);</li>
 *   <li>任务状态怎么走的 (TASK_SERVICE 行)。</li>
 * </ul>
 *
 * <p>评测如果只看 Python 的自述, 就永远发现不了"Agent 以为调了、其实被网关
 * 挡了"这类问题 —— 而那恰恰是中间件最该被证明有效的地方。
 *
 * <h3>鉴权与任务查询完全一致</h3>
 *
 * <p>轨迹比任务本身更敏感 (含工具入参、错误原因、token 用量), 所以照
 * {@code GET /api/tasks/{id}} 的做法: 先跑 {@code ControlAuthFilter}, 再在
 * 这里做租户可见性判断。跨租户一律 404, 不给存在性探针。
 *
 * <h3>为什么没做"等审计落库"</h3>
 *
 * <p>审计走 RocketMQ 异步汇聚, 刚跑完的任务可能还差几行。这个接口不负责
 * 等待 —— 让读方按需轮询 (评测脚本就是这么做的), 因为"等多久"是调用方的
 * 业务判断, 写死在这里只会让接口变慢且不可解释。
 */
@RestController
@RequestMapping("/api/tasks")
public class TrajectoryController {

    private final TaskService taskService;
    private final AuditLogMapper auditLogMapper;

    public TrajectoryController(TaskService taskService, AuditLogMapper auditLogMapper) {
        this.taskService = taskService;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 按任务查轨迹。按 {@code occurred_at} 排序而不是 {@code id}:
     * id 是落库顺序, MQ 重投/多分区下与发生顺序不一致; 评测要的是因果链。
     */
    @GetMapping("/{agentTaskId}/trajectory")
    public List<AuditLog> trajectory(
            @PathVariable String agentTaskId,
            @RequestAttribute(CallerIdentity.ATTRIBUTE) CallerIdentity caller) {
        AgentTask task = taskService.requireTask(agentTaskId);
        caller.requireTaskVisible(agentTaskId, task.getTenantId());
        return auditLogMapper.selectList(
                Wrappers.<AuditLog>lambdaQuery()
                        .eq(AuditLog::getAgentTaskId, agentTaskId)
                        .orderByAsc(AuditLog::getOccurredAt)
                        .orderByAsc(AuditLog::getId));
    }
}
