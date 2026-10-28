package com.km.task.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 状态流转规则。纯函数, 不依赖 Spring / 数据库 —— 所以能被单测穷举。
 *
 * <h2>为什么校验必须在 Java 侧做</h2>
 *
 * 这是"控制面"的实质含义: Python 只能【上报】自己到了哪一步, 不能【决定】
 * 状态该不该变。如果 Python 说"我现在是 COMPLETED"就直接写库, 那状态机
 * 就退化成了一个字符串字段, 谁都能改坏它。
 *
 * <h2>规则</h2>
 *
 * <ul>
 *   <li><b>终态不可离开</b> —— COMPLETED / FAILED / CANCELLED 是终点,
 *       任何回转都是 bug</li>
 *   <li><b>不能跳级</b> —— PENDING 不能直接到 TOOL_CALLING,
 *       必须先经过 THINKING (Agent 不可能没思考就调工具)</li>
 *   <li><b>同态上报幂等</b> —— 见 {@link #isNoop}, 由调用方处理成
 *       "返回 200 但不写事件表"</li>
 * </ul>
 *
 * <h2>★ 重试是唯一能离开终态的边, 而且它不在这张表里 ★</h2>
 *
 * <p>{@code FAILED -> PENDING} 由 {@link #isRetryAllowed} <b>单独</b>判定,
 * 不放进 {@link #ALLOWED}。原因: {@code ALLOWED} 是"上报能走的边", 而
 * Python 运行时绝不能自己把 FAILED 翻回 PENDING —— 它只能上报"我失败了",
 * 要不要再试一次是控制面的决定。两条规则放在两个方法里, "上报不能离开
 * 终态"这个不变量就仍然由 36 组合的穷举测试锁着。
 */
public final class TaskStatusMachine {

    /**
     * 合法的流转表。
     *
     * 只列非终态作为 key —— 终态没有任何出边, 所以压根不出现在表里,
     * {@link #isAllowed} 对不存在的 key 一律返回 false。
     */
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = buildAllowed();

    private TaskStatusMachine() {
        // 工具类, 不实例化
    }

    private static Map<TaskStatus, Set<TaskStatus>> buildAllowed() {
        Map<TaskStatus, Set<TaskStatus>> m = new EnumMap<>(TaskStatus.class);

        // 建完任务, Agent 开始思考 —— 也可能直接失败/被取消
        m.put(TaskStatus.PENDING, EnumSet.of(
                TaskStatus.THINKING,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED));

        // 思考完, 要么调工具, 要么直接给出答案
        m.put(TaskStatus.THINKING, EnumSet.of(
                TaskStatus.TOOL_CALLING,
                TaskStatus.COMPLETED,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED));

        // ★ 调完工具【回到思考】是 ReAct 循环的关键, 不是异常路径 ★
        m.put(TaskStatus.TOOL_CALLING, EnumSet.of(
                TaskStatus.THINKING,
                TaskStatus.COMPLETED,
                TaskStatus.FAILED,
                TaskStatus.CANCELLED));

        return Map.copyOf(m);
    }

    /**
     * 这次流转合不合法。
     *
     * 终态因为不在表里, 一律返回 false —— 这就是"终态不可离开"的实现。
     */
    public static boolean isAllowed(TaskStatus from, TaskStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 是不是"原地踏步"的上报 (目标状态 == 当前状态)。
     *
     * 回调走网络, <b>重复投递是常态而不是异常</b>: Python 侧的超时重试、
     * 网络抖动后的重放都会产生它。所以要当成成功来处理。
     *
     * 但必须【不写事件表】—— 否则事件流会被重复记录淹没, 而事件表恰恰是
     * 排查问题时最该干净的地方。
     */
    public static boolean isNoop(TaskStatus from, TaskStatus to) {
        return from != null && from == to;
    }

    /**
     * 重试这条边是否成立: <b>只有 {@code FAILED -> PENDING}</b>。
     *
     * <h3>为什么单独一个方法</h3>
     *
     * <p>见类注释: 它不能被 {@link #isAllowed} 认出来, 否则一条伪造的
     * PATCH 就能把终态任务"重试"回 PENDING —— 那等于给 Python 开了后门。
     * 只有 {@code TaskService.retry()} 这个控制面入口会调用它。
     *
     * <h3>为什么只允许 FAILED</h3>
     *
     * <ul>
     *   <li><b>COMPLETED</b>: 已经成功, "再试一次"没有业务含义。真要重跑,
     *       那是<b>新建任务</b> (新的 task_id、新的意图上下文), 不是重试 ——
     *       重试的前提是"同一个任务、同一个目标、上次没做成"</li>
     *   <li><b>CANCELLED</b>: 用户/运维主动停下的。自动或半自动地把它拉回
     *       PENDING 会违背那次取消的意图; 要恢复也应当是一条显式的
     *       "恢复"操作, 而不是复用重试</li>
     * </ul>
     *
     * <p>返回 {@code false} 不代表"非法状态上报" (那条路径根本不会问到这里),
     * 而是"这个任务现在不该被重试"。
     */
    public static boolean isRetryAllowed(TaskStatus from, TaskStatus to) {
        return from == TaskStatus.FAILED && to == TaskStatus.PENDING;
    }
}
