package com.km.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 一次任务执行 —— 对应表 {@code agent_task_attempt}。
 *
 * <h3>★ 为什么需要这张表 ★</h3>
 *
 * 审计事件里的 {@code trace_id} 是<b>溯源信息</b>: 它回答"这条事件属于哪一次
 * 执行"。溯源信息必须<b>不可变</b> —— 事件一旦写下, 事后不应该还需要去查一张
 * 会被更新的业务表才能知道它属于谁。
 *
 * 而 {@code agent_task.trace_id} 是"最近一次执行"的滚动值: 重试一开始它就变。
 * 如果 TASK_LIFECYCLE 从那里取 trace, 那么第一遍执行的在途上报会被盖上第二遍
 * 的 trace —— 单独看每一行都正常, 只有按 trace 拼链路时才发现拼不上。
 *
 * 所以"执行"必须是一等公民, 有自己的行:
 *
 * <ul>
 *   <li>一个任务可以有多次执行: 首次由 {@code create()} 建, 每次重试由
 *       {@code retry()} 建下一代 (attempt_no 递增)</li>
 *   <li>每次执行有且只有一个 {@code trace_id}, 建行时确定, 之后不再改</li>
 *   <li>{@code ended_at} 非空 = 这次执行已闭链, 不再接受状态上报</li>
 * </ul>
 *
 * <p>{@code agent_task.trace_id} 保留, 但降级为"当前执行的指针"(反范式,
 * 方便按任务查最近的链), <b>不再是权威</b>。权威是这张表。
 *
 * <h3>不变量</h3>
 *
 * <ul>
 *   <li>{@code (agent_task_id, attempt_no)} 唯一 —— 第几次执行不会重复</li>
 *   <li>{@code trace_id} 全局唯一 —— 一条链不可能属于两个任务</li>
 *   <li>同一任务下最多一行 {@code ended_at IS NULL}</li>
 * </ul>
 */
@TableName("agent_task_attempt")
public class AgentTaskAttempt {

    /** 正在执行。闭链时会被改成任务的终态 (COMPLETED / FAILED / CANCELLED)。 */
    public static final String RUNNING = "RUNNING";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String agentTaskId;

    /** 本次执行的链路 ID, 建行时确定。审计事件用它盖章。 */
    private String traceId;

    /** 第几次执行, 从 1 开始。 */
    private Integer attemptNo;

    /** RUNNING / COMPLETED / FAILED / CANCELLED */
    private String status;

    private LocalDateTime startedAt;

    /** NULL = 还在跑。闭链后不再接受这次执行的状态上报。 */
    private LocalDateTime endedAt;

    /**
     * Java 把 {@code /chat} 发出去的时点。NULL = 还没派发。
     *
     * <p>心跳兜底 (见 {@code com.km.task.heartbeat}) 用它回答一个问题:
     * <b>这次执行到底有没有人负责跑?</b>
     *
     * <p>不能拿 {@code started_at} 当这个证据: attempt 是"开新链"时创建的,
     * 而首次执行由调用方自己调 {@code /chat}, 任务建好后可能合法地停在
     * PENDING 很久。只看年龄的话, 扫描器会把"还在等调用方"误判成"卡住了"。
     * 所以只有两种证据算数: Java 派发过 ({@code dispatched_at}) 或 Python
     * 跑起来过 ({@code lastHeartbeatAt})。
     */
    private LocalDateTime dispatchedAt;

    /**
     * 最近一次心跳。NULL = 这次执行从来没跑起来。
     *
     * <p>写在 attempt 而不是 agent_task 上: 心跳是<b>一次执行</b>的属性,
     * 重试会换一条链, 旧链的心跳不该刷新新链的时钟。
     *
     * <p>它只证明"Python 进程里这个 trace 还活着", <b>不</b>直接给关闭链路
     * 授权 —— 时间戳陈旧可能只是这台 Java 收不到心跳。真正能关链的证据是
     * 主动探活得到 {@code alive=false}, 见 HeartbeatSweeper。
     */
    private LocalDateTime lastHeartbeatAt;

    /** 这次执行是否还活着。判断用 endedAt 而不是 status, 避免两列不一致。 */
    public boolean isRunning() {
        return endedAt == null;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAgentTaskId() {
        return agentTaskId;
    }

    public void setAgentTaskId(String agentTaskId) {
        this.agentTaskId = agentTaskId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public LocalDateTime getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(LocalDateTime dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }

    public LocalDateTime getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }
}
