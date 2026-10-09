package com.km.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.task.domain.AgentTask;

/**
 * agent_task 的读写。
 *
 * 只继承 BaseMapper —— 状态流转的并发安全靠 {@code update ... where status = ?}
 * 这条 CAS 语句实现, 不需要自定义 SQL (见 TaskService#reportStatus)。
 */
public interface AgentTaskMapper extends BaseMapper<AgentTask> {
}
