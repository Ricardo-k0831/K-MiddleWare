package com.km.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.task.domain.AgentTaskAttempt;

/**
 * {@code agent_task_attempt} 的读写。
 *
 * <p>和 {@link AgentTaskEventMapper} 不同, 这张表<b>不是</b> append-only:
 * 状态上报走到终态时要 {@code UPDATE ... SET ended_at/status} 把这次执行闭链。
 * 但只允许这一种更新 —— 不可变的列是 {@code trace_id} / {@code attempt_no} /
 * {@code started_at}, 它们一旦写入就不该再改。
 */
public interface AgentTaskAttemptMapper extends BaseMapper<AgentTaskAttempt> {
}
