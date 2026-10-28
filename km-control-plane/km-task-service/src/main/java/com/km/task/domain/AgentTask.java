package com.km.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一个 Agent 任务。对应表 {@code agent_task}。
 *
 * <h3>agentTaskId 为什么同时是 LangGraph 的 thread_id</h3>
 *
 * 这是整个设计里最关键的一个决定。两者本来就是同一个东西 ——
 * "这单业务"和"这张图跑到哪了"。共用一个 ID 的好处:
 *
 * <ul>
 *   <li>断点续跑 = 拿同一个 ID 重新 invoke, <b>不需要任何映射表</b></li>
 *   <li>排查问题时, 一个 ID 同时能在 MySQL 和 LangGraph checkpoint 里查</li>
 * </ul>
 *
 * 反面做法是另生成一个 thread_id 存进 {@code checkpoint_ref} ——
 * 多一层映射, 多一处可能不一致的地方, 收益为零。
 * 所以 {@code checkpointRef} 字段现在【故意留空】, 留给将来
 * "一个任务对应多个 checkpoint 分支"的场景。
 *
 * <h3>代价: 对话记忆按任务隔离, 不是按会话</h3>
 *
 * <p>task_id 就是 thread_id, 意味着"会话"在运行时并不存在: 同一个
 * {@code sessionId} 下的两个任务各有一份独立的 LangGraph 记忆, 第二个任务
 * 看不见第一个任务的上下文。{@code sessionId} 现在只是一个可查询的数据库标签。
 *
 * <p>要做到会话级记忆, thread_id 应当换成 {@code sessionId}, 任务维度用
 * LangGraph 的 {@code checkpoint_ns} 区分 —— 等真需要跨任务上下文时再做。
 * 这里写明, 是为了避免按"同一会话共享记忆"这个错误前提去设计功能。
 */
@TableName("agent_task")
public class AgentTask {

    /** 全链路唯一任务 ID, 由本服务生成 (不是数据库自增) */
    @TableId(value = "agent_task_id", type = IdType.INPUT)
    private String agentTaskId;

    private String tenantId;

    private String userId;

    /** 会话 ID。跨任务的关联用它 —— 不要用 traceId 把多个任务串起来 */
    private String sessionId;

    /**
     * 【最近一次执行】的 traceId, 跨 Java/Python 透传。
     *
     * <p>它不是"任务的 ID": 任务和调用链是 1:N —— 每次重试生成一条新链
     * (见 {@code TaskService.retry()} 和 {@code newTraceId()})。这个字段是
     * 可变指针, 指向当前那次执行; 审计和状态上报的权威来源是
     * {@code agent_task_attempt}, 不是这一列。
     */
    private String traceId;

    /** 用户原始输入 (脱敏后) */
    private String intent;

    /** 见 {@link TaskStatus}。存字符串, 与数据库列一一对应。 */
    private String status;

    /** 最终结果 */
    private String result;

    /** 失败原因 */
    private String errorMsg;

    /** LangGraph checkpoint 定位 —— 当前版本刻意留空, 见类注释 */
    private String checkpointRef;

    private Integer retryCount;

    private Integer maxRetry;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 进入终态的时刻。非终态时为 null。 */
    private LocalDateTime finishedAt;

    public String getAgentTaskId() {
        return agentTaskId;
    }

    public void setAgentTaskId(String agentTaskId) {
        this.agentTaskId = agentTaskId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getCheckpointRef() {
        return checkpointRef;
    }

    public void setCheckpointRef(String checkpointRef) {
        this.checkpointRef = checkpointRef;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Integer getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(Integer maxRetry) {
        this.maxRetry = maxRetry;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
