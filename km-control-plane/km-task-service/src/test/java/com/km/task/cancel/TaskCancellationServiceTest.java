package com.km.task.cancel;

import com.km.task.domain.AgentTask;
import com.km.task.error.CancellationNotAllowedException;
import com.km.task.service.TaskService;
import com.km.task.web.dto.CancelTaskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取消的编排顺序: 先落库, 后通知运行时。
 *
 * <p>顺序是这里唯一容易写反、而且写反了不会立刻报错的东西 —— 所以单独
 * 钉一个测试。先通知的话, Python 已经停了而 Java 还是 THINKING, 心跳兜底
 * 会把它当"卡住的执行"再拉起来。
 */
class TaskCancellationServiceTest {

    private TaskService taskService;
    private PythonCancelNotifier notifier;
    private TaskCancellationService service;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        notifier = mock(PythonCancelNotifier.class);
        service = new TaskCancellationService(taskService, notifier);
    }

    @Test
    void 先改状态再通知运行时() {
        AgentTask cancelled = task();
        when(taskService.cancel("task-1", "reason", null)).thenReturn(cancelled);
        CancelTaskResponse.RuntimeStop runtime =
                new CancelTaskResponse.RuntimeStop(true, true, true, "session:km:s-1", null);
        when(notifier.notify(cancelled, "reason")).thenReturn(runtime);

        CancelTaskResponse response = service.cancel("task-1", "reason");

        // 断言顺序: 两个 mock 都在同一个线程里同步调用, InOrder 能钉住先后。
        var order = inOrder(taskService, notifier);
        order.verify(taskService).cancel("task-1", "reason", null);
        order.verify(notifier).notify(cancelled, "reason");

        assertEquals(cancelled, response.task());
        assertTrue(response.runtime().released());
    }

    @Test
    void 状态变更失败时根本不通知运行时() {
        when(taskService.cancel("task-1", null, null))
                .thenThrow(new CancellationNotAllowedException("task-1", "已 COMPLETED"));

        assertThrows(CancellationNotAllowedException.class,
                () -> service.cancel("task-1", null));

        // 没取消成功就不要去打扰运行时 —— 否则一次被拒的请求也会
        // 停掉一个正在正常跑的图。
        verify(notifier, never()).notify(any(), any());
    }

    @Test
    void 运行时通知失败不影响取消结果() {
        AgentTask cancelled = task();
        when(taskService.cancel(eq("task-1"), any(), any())).thenReturn(cancelled);
        when(notifier.notify(cancelled, null))
                .thenReturn(CancelTaskResponse.RuntimeStop.unreachable("Connection refused"));

        CancelTaskResponse response = service.cancel("task-1", null);

        assertEquals("CANCELLED", response.task().getStatus());
        assertFalse(response.runtime().reached());
        assertFalse(response.runtime().stopped());
    }

    private static AgentTask task() {
        AgentTask task = new AgentTask();
        task.setAgentTaskId("task-1");
        task.setStatus("CANCELLED");
        task.setTenantId("km");
        task.setSessionId("s-1");
        task.setUserId("u-1");
        return task;
    }
}
