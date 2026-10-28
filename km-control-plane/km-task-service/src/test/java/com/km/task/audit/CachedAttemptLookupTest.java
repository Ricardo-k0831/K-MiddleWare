package com.km.task.audit;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.km.task.domain.AgentTaskAttempt;
import com.km.task.mapper.AgentTaskAttemptMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存层的规则测试 —— 什么时候查库, 什么时候不查, 异常会不会被缓存住。
 *
 * <p>这个类的价值不在"缓存能命中", 而在<b>三种结果不能互相冒充</b>:
 * 查到 / 查无 / 查不动。把它们混成一个值, 一次数据库抖动就会在 STRICT
 * 模式下把整批真实审计拒掉, 而日志上看起来像"trace 不存在"。
 */
class CachedAttemptLookupTest {

    private AgentTaskAttemptMapper mapper;
    private CachedAttemptLookup lookup;

    @BeforeAll
    static void initLambdaCache() {
        // Wrappers.lambdaQuery() 在【构造时】就要把方法引用解析成列名,
        // 而那依赖 TableInfo 缓存。纯单元测试没有 Spring/MyBatis 的启动流程,
        // 得手动装一次 —— 不装的话报 "can not find lambda cache for this entity"。
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, AgentTaskAttempt.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AgentTaskAttemptMapper.class);
        lookup = new CachedAttemptLookup(mapper, Duration.ofMinutes(1), Duration.ofMinutes(1));
    }

    @Test
    void 同一个trace查第二次不再打数据库() {
        // 一次执行的几十条事件全是同一个 traceId —— 不缓存的话,
        // 审计这条旁路会以"每条事件一次查询"的速率压在数据库上。
        when(mapper.selectOne(any()))
                .thenReturn(attempt("task-1", "trace-1", null));

        lookup.findByTrace("trace-1");
        lookup.findByTrace("trace-1");

        verify(mapper, times(1)).selectOne(any());
    }

    @Test
    void 查到之后返回的是执行记录上的权威身份() {
        when(mapper.selectOne(any()))
                .thenReturn(attempt("task-1", "trace-1", null));

        AttemptRef ref = lookup.findByTrace("trace-1");

        assertEquals("task-1", ref.agentTaskId());
        assertEquals("trace-1", ref.traceId());
        assertTrue(ref.isRunning());
    }

    @Test
    void 查无也会缓存一小段时间() {
        // 缓存"没有"是为了挡住重复的无效查询; 但 TTL 必须短 ——
        // "审计先到, attempt 后建"这种情况要能自己恢复。
        when(mapper.selectOne(any())).thenReturn(null);

        assertNull(lookup.findByTrace("trace-missing"));
        assertNull(lookup.findByTrace("trace-missing"));

        verify(mapper, times(1)).selectOne(any());
    }

    @Test
    void 查询异常不写缓存() {
        // ★ 数据库抖一下不能变成"这个 trace 不存在"的证据 ★
        // 异常原样上抛, 由 AuditIngestService 决定降级; 如果这里把异常
        // 吞成 null, 上游就再也分不清"查无"和"查不动"了。
        when(mapper.selectOne(any())).thenThrow(new IllegalStateException("连接池耗尽"));

        assertThrows(IllegalStateException.class, () -> lookup.findByTrace("trace-1"));
        assertThrows(IllegalStateException.class, () -> lookup.findByTrace("trace-1"));

        verify(mapper, times(2)).selectOne(any());
    }

    @Test
    void 过期后会重新查库() throws InterruptedException {
        // 正缓存过期之后必须重新查 —— 执行闭链后 endedAt 会变, 一直用旧快照
        // 的话, 迟到事件永远不会被识别。
        CachedAttemptLookup shortLived = new CachedAttemptLookup(
                mapper, Duration.ofMillis(20), Duration.ofMillis(20));
        when(mapper.selectOne(any()))
                .thenReturn(attempt("task-1", "trace-1", null));

        shortLived.findByTrace("trace-1");
        Thread.sleep(60);
        shortLived.findByTrace("trace-1");

        verify(mapper, times(2)).selectOne(any());
    }

    @Test
    void 空traceId不查库() {
        assertNull(lookup.findByTrace(null));
        assertNull(lookup.findByTrace("   "));

        verify(mapper, times(0)).selectOne(any());
    }

    private static AgentTaskAttempt attempt(String taskId, String traceId, LocalDateTime endedAt) {
        AgentTaskAttempt attempt = new AgentTaskAttempt();
        attempt.setAgentTaskId(taskId);
        attempt.setTraceId(traceId);
        attempt.setEndedAt(endedAt);
        return attempt;
    }
}
