package com.km.task.web;

import com.km.task.auth.CallerIdentity;
import com.km.task.auth.ControlAuthProperties;
import com.km.task.domain.AgentTask;
import com.km.task.domain.AuditLog;
import com.km.task.error.TaskNotFoundException;
import com.km.task.mapper.AuditLogMapper;
import com.km.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 轨迹查询的 HTTP 边界。
 *
 * <p>不验 SQL 怎么写 —— 那是 Mapper 的事; 验两件事:
 * <ol>
 *   <li>跨租户的任务, 连一条审计都不查 (先判可见性, 再查库);</li>
 *   <li>字段名就是评测脚本要读的那几个 —— 多一个少一个都不行。</li>
 * </ol>
 *
 * <p>第二条尤其重要: 评测脚本在 Python 侧, 字段名对不上不会报错,
 * 只会让某一列永远是 None, 然后评测算出一个看起来很正常的分。
 */
class TrajectoryControllerTest {

    private static final String TASK_ID = "task-1";

    private TaskService taskService;
    private AuditLogMapper auditLogMapper;
    private MockMvc mvc;

    private static final CallerIdentity USER_DEMO =
            new CallerIdentity("demo", "demo-user", ControlAuthProperties.Role.USER);

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        auditLogMapper = mock(AuditLogMapper.class);
        // 把真实的异常翻译层也装上: 这里验的是"跨租户 404", 而 404 是
        // requireTaskVisible 抛 TaskNotFoundException 之后由 advice 翻出来的。
        // 不装 advice 的话异常直接冒出去, 测到的就不是 HTTP 行为了。
        mvc = MockMvcBuilders
                .standaloneSetup(new TrajectoryController(taskService, auditLogMapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private AgentTask taskInTenant(String tenant) {
        AgentTask task = new AgentTask();
        task.setAgentTaskId(TASK_ID);
        task.setTenantId(tenant);
        return task;
    }

    private AuditLog row(String layer, String eventType, String status) {
        AuditLog row = new AuditLog();
        row.setId(1L);
        row.setAgentTaskId(TASK_ID);
        row.setTraceId("trace-1");
        row.setTenantId("demo");
        row.setLayer(layer);
        row.setEventType(eventType);
        row.setStatus(status);
        row.setLatencyMs(12);
        row.setOccurredAt(LocalDateTime.of(2026, 11, 12, 10, 0));
        return row;
    }

    @Test
    void 本人租户可以读到轨迹且字段齐全() throws Exception {
        when(taskService.requireTask(TASK_ID)).thenReturn(taskInTenant("demo"));
        when(auditLogMapper.selectList(any())).thenReturn(
                List.of(row("AGENT_RUNTIME", "LLM_CALL", "OK")));

        mvc.perform(get("/api/tasks/{id}/trajectory", TASK_ID)
                        .requestAttr(CallerIdentity.ATTRIBUTE, USER_DEMO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].layer").value("AGENT_RUNTIME"))
                .andExpect(jsonPath("$[0].eventType").value("LLM_CALL"))
                .andExpect(jsonPath("$[0].status").value("OK"))
                .andExpect(jsonPath("$[0].agentTaskId").value(TASK_ID))
                .andExpect(jsonPath("$[0].latencyMs").value(12));
    }

    @Test
    void 跨租户返回404且一条审计都不查() throws Exception {
        when(taskService.requireTask(TASK_ID)).thenReturn(taskInTenant("km"));

        mvc.perform(get("/api/tasks/{id}/trajectory", TASK_ID)
                        .requestAttr(CallerIdentity.ATTRIBUTE, USER_DEMO))
                .andExpect(status().isNotFound());

        // ★ 顺序断言 ★ 如果先查审计再判租户, 数据的形状会先被读出来 ——
        // 虽然没返回给调用方, 但"查过"本身在慢查询日志、连接池、缓存里
        // 都会留痕。先判后查是边界, 不是风格。
        verify(auditLogMapper, never()).selectList(any());
    }

    @Test
    void 任务不存在时也是404() throws Exception {
        when(taskService.requireTask(TASK_ID)).thenThrow(new TaskNotFoundException(TASK_ID));

        mvc.perform(get("/api/tasks/{id}/trajectory", TASK_ID)
                        .requestAttr(CallerIdentity.ATTRIBUTE, USER_DEMO))
                .andExpect(status().isNotFound());

        verify(auditLogMapper, never()).selectList(any());
    }
}
