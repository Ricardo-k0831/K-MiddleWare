package com.km.task.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态流转规则的穷举测试。
 *
 * <p>这个类没有 @SpringBootTest, 也不需要数据库 —— 状态机是纯函数,
 * 所有分支都能在毫秒内跑完。这也正是把它从 Service 里抽出来的原因。
 *
 * <p>测试策略是<b>把期望的流转矩阵完整写死</b>, 然后和实现逐一比对
 * (6x6 = 36 种组合全测)。比起来啰嗦, 但好处是:
 * 以后谁不小心往流转表里加了条边, 这里会立刻红, 而不是等到生产环境
 * 出现一个"状态怎么从 FAILED 跳到 THINKING 了"的诡异事件。
 */
class TaskStatusMachineTest {

    /**
     * 期望的流转矩阵 —— 把这张表当成文档读。
     *
     * <p>只列非终态作为 key: 终态没有任何出边。
     */
    private static final Map<TaskStatus, Set<TaskStatus>> EXPECTED = buildExpected();

    private static Map<TaskStatus, Set<TaskStatus>> buildExpected() {
        Map<TaskStatus, Set<TaskStatus>> m = new EnumMap<>(TaskStatus.class);
        m.put(TaskStatus.PENDING, EnumSet.of(
                TaskStatus.THINKING, TaskStatus.FAILED, TaskStatus.CANCELLED));
        m.put(TaskStatus.THINKING, EnumSet.of(
                TaskStatus.TOOL_CALLING, TaskStatus.COMPLETED,
                TaskStatus.FAILED, TaskStatus.CANCELLED));
        m.put(TaskStatus.TOOL_CALLING, EnumSet.of(
                TaskStatus.THINKING, TaskStatus.COMPLETED,
                TaskStatus.FAILED, TaskStatus.CANCELLED));
        return m;
    }

    @Test
    void 全部36种状态组合的判定都与期望一致() {
        for (TaskStatus from : TaskStatus.values()) {
            for (TaskStatus to : TaskStatus.values()) {
                boolean expected = EXPECTED.getOrDefault(from, Set.of()).contains(to);
                assertEquals(expected, TaskStatusMachine.isAllowed(from, to),
                        "流转判定不符: " + from + " -> " + to);
            }
        }
    }

    @Test
    void 终态不能再到任何状态_包括它自己() {
        for (TaskStatus terminal : TaskStatus.values()) {
            if (!terminal.isTerminal()) {
                continue;
            }
            for (TaskStatus to : TaskStatus.values()) {
                assertFalse(TaskStatusMachine.isAllowed(terminal, to),
                        "终态 " + terminal + " 不该能走到 " + to);
            }
        }
    }

    /**
     * ReAct 循环的关键规则: 调完工具要回到思考。
     *
     * <p>这条如果被误删, 表现是 Agent 只能调一次工具就再也"思考"不了 ——
     * 而这恰恰是最不容易在日常手工测试里发现的一类退化。
     */
    @Test
    void 调用工具后可以回到思考_这是ReAct循环() {
        assertTrue(TaskStatusMachine.isAllowed(TaskStatus.TOOL_CALLING, TaskStatus.THINKING));
    }

    @Test
    void 不能跳级_PENDING不能直接到TOOL_CALLING() {
        assertFalse(TaskStatusMachine.isAllowed(TaskStatus.PENDING, TaskStatus.TOOL_CALLING),
                "Agent 不可能没思考就调工具");
    }

    @Test
    void 三个终态各自的判定正确() {
        assertTrue(TaskStatus.COMPLETED.isTerminal());
        assertTrue(TaskStatus.FAILED.isTerminal());
        assertTrue(TaskStatus.CANCELLED.isTerminal());

        assertFalse(TaskStatus.PENDING.isTerminal());
        assertFalse(TaskStatus.THINKING.isTerminal());
        assertFalse(TaskStatus.TOOL_CALLING.isTerminal());
    }

    /**
     * "原地踏步"的上报要被识别成幂等, 而不是"非法流转"。
     *
     * <p>区别很重要: 幂等返回 200 且不写事件表; 非法流转返回 409。
     * 回调走网络, 重复投递是常态 —— 把它当成错误会让 Java 侧日志被
     * 无意义的 409 淹没, 真出问题时就看不见了。
     */
    @Test
    void 同态上报被识别为幂等而不是非法() {
        for (TaskStatus s : TaskStatus.values()) {
            assertTrue(TaskStatusMachine.isNoop(s, s), s + " -> " + s + " 应识别为幂等");
        }
        assertFalse(TaskStatusMachine.isNoop(TaskStatus.THINKING, TaskStatus.TOOL_CALLING));
    }

    /**
     * 注意: 终态的"同态上报"是幂等而不是非法。
     *
     * <p>这看着有点反直觉, 但正是想要的: Python 侧超时重发了一条
     * COMPLETED, 任务本来就是 COMPLETED, 应该安静地返回 200,
     * 而不是报 409 让上报方以为出了错、再去重试。
     */
    @Test
    void 终态的同态上报也算幂等() {
        assertTrue(TaskStatusMachine.isNoop(TaskStatus.COMPLETED, TaskStatus.COMPLETED));
        assertTrue(TaskStatusMachine.isNoop(TaskStatus.FAILED, TaskStatus.FAILED));
    }

    @Test
    void null一律判false而不是抛异常() {
        assertFalse(TaskStatusMachine.isAllowed(null, TaskStatus.THINKING));
        assertFalse(TaskStatusMachine.isAllowed(TaskStatus.PENDING, null));
        assertFalse(TaskStatusMachine.isAllowed(null, null));
        assertFalse(TaskStatusMachine.isNoop(null, null));
    }
}
