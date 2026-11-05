package com.km.task.cancel;

import com.km.task.domain.AgentTask;
import com.km.task.service.TaskService;
import com.km.task.web.dto.CancelTaskResponse;
import org.springframework.stereotype.Service;

/**
 * 取消的编排: 先落控制面状态, 再通知运行时。
 *
 * <p>这一步单独成类, 是为了让 Controller 保持"只做参数绑定" —— 而顺序
 * (先 Java 后 Python) 是这个功能里唯一不能写反的东西, 把它放在一个有名字
 * 的地方比散在 Controller 里好读。见 {@link TaskService#cancel} 的注释。
 */
@Service
public class TaskCancellationService {

    private final TaskService taskService;
    private final PythonCancelNotifier notifier;

    public TaskCancellationService(TaskService taskService, PythonCancelNotifier notifier) {
        this.taskService = taskService;
        this.notifier = notifier;
    }

    /**
     * @param reason 可选原因; 同时写进事件/审计和 Python 日志
     * @return 任务 (已 CANCELLED) + 运行时的处理结果
     */
    public CancelTaskResponse cancel(String agentTaskId, String reason) {
        // ① 事务内: 状态机校验 + CAS + 事件 + 闭链 + 审计。
        //    抛异常 (任务已结束/并发冲突) 时, 下面的通知不会执行 ——
        //    这正是我们要的: 没取消成功就不要打扰运行时。
        AgentTask task = taskService.cancel(agentTaskId, reason);

        // ② 事务外: 最好的情况是停掉正在跑的调用并释放会话线程;
        //    最坏的情况是连不上, 只记日志。无论哪种, ①都已经生效。
        //
        //    对"本来就已经 CANCELLED"的重复取消也照常通知: 第一次通知
        //    可能因为 Python 重启/网络抖动失败了, 而挂起状态还占着会话。
        //    Python 侧两步都是幂等的, 重发没有副作用。
        CancelTaskResponse.RuntimeStop runtime = notifier.notify(task, reason);
        return new CancelTaskResponse(task, runtime);
    }
}
