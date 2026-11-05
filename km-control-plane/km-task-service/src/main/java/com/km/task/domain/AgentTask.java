package com.km.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一个 Agent 任务。对应表 {@code agent_task}。
 *
 * <h3>任务与 LangGraph 线程: 不是一个 ID, 是一对身份</h3>
 *
 * <p>线程身份由 Python 侧 {@code resolve_thread_id()} 决定, 优先级:
 * {@code session:<tenantId>:<sessionId>} &gt; {@code task:<agentTaskId>} &gt; 调用方给的
 * {@code thread_id}。也就是说<b>有会话时线程属于会话, 记忆跨任务累积</b>;
 * 没有会话时才退化成"一个任务一个线程"。
 *
 * <p>任务归属不再靠线程 ID, 而是靠 checkpoint 的 metadata: Python 每次 invoke
 * 都写入 {@code metadata.task_id}, LangGraph 会把它持久化到每个 checkpoint 上。
 * 重试时只续跑 metadata.task_id 等于自己的那个挂起, 别人的挂起一律 409
 * (同一会话里两个任务并行是错误用法, 不该互相接管)。
 *
 * <p>为什么不用 {@code checkpoint_ns} 把任务隔离在同一线程里:
 * LangGraph 的 ns 只对子图有效, 根图上传自定义 ns 直接报错
 * {@code Subgraph xxx not found}; 而且不同 ns 的 messages 互相不可见,
 * 隔离了也就没有"共享记忆"可言。验证记录见 docs/task-state-machine.md。
 *
 * <p>因此 {@code checkpointRef} 仍然【故意留空】: 线程 ID 可以从本实体
 * (tenantId / sessionId / agentTaskId) 确定性推出, 不需要任何映射表;
 * 多存一份反而多一处可能不一致的地方。
 */
@TableName("agent_task")
public class AgentTask {

    /** 全链路唯一任务 ID, 由本服务生成 (不是数据库自增) */
    @TableId(value = "agent_task_id", type = IdType.INPUT)
    private String agentTaskId;

    private String tenantId;

    private String userId;

    /**
     * 会话 ID。跨任务的关联用它 —— 不要用 traceId 把多个任务串起来。
     *
     * <p>它同时是 LangGraph 线程的输入 (见类注释): 派发 /chat 时必须原样带上,
     * 否则重试会落到另一个线程上, 断点续跑和会话记忆都会断。
     */
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
