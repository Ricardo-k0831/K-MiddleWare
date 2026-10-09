package com.km.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.km.task.domain.AgentTaskEvent;

/**
 * agent_task_event 的读写。
 *
 * 这张表是 append-only 的: 业务代码只应该调用 {@code insert} 和 {@code selectList},
 * 不该出现 update / delete。将来要清理只做归档, 不做删除。
 */
public interface AgentTaskEventMapper extends BaseMapper<AgentTaskEvent> {
}
