package com.km.task.web.dto;

import com.km.task.domain.AgentTask;

/**
 * POST /api/tasks/{id}/cancel 的响应。
 *
 * <p>为什么不是直接返回 {@link AgentTask}: 取消有<b>两个层面</b>的结果 ——
 * 控制面的状态变更 (一定成功, 否则接口会报错) 和运行时的实际停止
 * (尽力而为, 可能失败)。只返回任务的话, 调用方看到 CANCELLED 就以为
 * "已经停了", 实际上 Python 可能还在花 token、调工具。把两者分开写,
 * 运维一眼就能看到"状态已取消, 但运行时没确认停"。
 *
 * <p>⚠️ 提醒读这个响应的人: {@code runtime.stopped=false} <b>不代表取消失败</b>。
 * 取消的业务决定已经生效 —— Python 后续的状态上报会因执行已闭链被拒。
 * 它只说明那次 LLM 调用可能还在跑, 属于"收尾稍慢", 不是"取消没生效"。
 */
public record CancelTaskResponse(

        /** 取消之后的任务 (status=CANCELLED)。**/
        AgentTask task,

        /** Python 运行时的处理结果。 */
        RuntimeStop runtime
) {

    /**
     * 运行时的处理结果。
     *
     * @param reached  Python 的取消接口有没有连上/返回 2xx
     * @param stopped  本地是否确实有一个正在跑的 ainvoke 被停掉
     * @param released 会话线程上的挂起状态是否被收尾 (false 时同会话下一个任务仍会 409)
     * @param threadId Python 算出来的线程 id, 用于排查"收尾打在了哪个线程上"
     * @param error    没连上/非 2xx 时的原因; 成功时为 null
     */
    public record RuntimeStop(
            boolean reached,
            boolean stopped,
            boolean released,
            String threadId,
            String error
    ) {
        /** 连不上 Python —— 状态已取消, 运行时没收到通知。 */
        public static RuntimeStop unreachable(String error) {
            return new RuntimeStop(false, false, false, null, error);
        }
    }
}
