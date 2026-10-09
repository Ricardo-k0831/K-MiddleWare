package com.km.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 一次状态流转的记录。对应表 {@code agent_task_event}。
 *
 * <h3>为什么要单独一张表</h3>
 *
 * {@code agent_task.status} 记的是<b>"现在是什么"</b>,
 * 这张表记的是<b>"怎么变成的"</b>。只有前者的话, 一个任务最后是 FAILED,
 * 你无法回答"它卡在哪一步、调了哪个工具之后失败的"。
 *
 * 表是 <b>append-only</b> 的: 只插入, 从不更新、从不删除。
 * 审计和排查全指望它。
 *
 * <h3>★ Jackson3TypeHandler, 不是 JacksonTypeHandler ★</h3>
 *
 * {@code detail} 是 MySQL 的 JSON 列, 需要一个 TypeHandler 来和
 * {@code Map<String,Object>} 互转。MyBatis-Plus 有两个:
 *
 * <pre>
 *   JacksonTypeHandler  -> com.fasterxml.jackson  (Jackson 2)
 *   Jackson3TypeHandler -> tools.jackson          (Jackson 3)
 * </pre>
 *
 * Spring Boot 4 已经迁到 <b>Jackson 3</b> ({@code tools.jackson}), 所以必须用
 * 后者。用错的那个不会在编译期报错, 而是等到第一次读写这个字段时
 * 抛 NoClassDefFoundError / ClassNotFoundException —— 报错位置离真因很远。
 *
 * <p>另外 {@code autoResultMap = true} 是必须的: 不加它, TypeHandler 只在
 * 写库时生效, <b>查询时不会应用</b>, 读回来的 detail 会直接报类型转换错误。
 */
@TableName(value = "agent_task_event", autoResultMap = true)
public class AgentTaskEvent {

    /** 事件表的自增主键。业务上不用它, 只用来排序/翻页。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String agentTaskId;

    /** 变更前的状态。任务创建时的第一条事件为 null。 */
    private String fromStatus;

    private String toStatus;

    /** 谁触发的: JAVA_GATEWAY / PYTHON_RUNTIME / SYSTEM_RETRY */
    private String source;

    /** 变更上下文, 如 {"toolName": "get_order_by_id"}。存成 JSON 列。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, Object> detail;

    private LocalDateTime createdAt;

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

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> detail) {
        this.detail = detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
