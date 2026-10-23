package com.km.mcp.audit;

import com.km.audit.AuditHeaders;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskIdentity} 的解析规则。
 *
 * <p>这个类看着只是个取值封装, 但它决定了<b>整个审计能不能按任务归拢</b> ——
 * 解析规则错一条 (比如空串没归一化), 后果是一部分事件永远查不到,
 * 而错的地方离现象非常远。
 */
class TaskIdentityTest {

    @Test
    void 四个头都在时逐个取出来() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AuditHeaders.TASK_ID, "task-1");
        metadata.put(AuditHeaders.TRACE_ID, "trace-1");
        metadata.put(AuditHeaders.TENANT_ID, "km");
        metadata.put(AuditHeaders.ACTOR, "u-1");

        TaskIdentity identity = TaskIdentity.from(McpTransportContext.create(metadata));

        assertEquals("task-1", identity.taskId());
        assertEquals("trace-1", identity.traceId());
        assertEquals("km", identity.tenantId());
        assertEquals("u-1", identity.actor());
        assertFalse(identity.isAnonymous());
    }

    @Test
    void 空的上下文得到匿名身份_而不是抛异常() {
        // 工具方法的 McpTransportContext 参数在某些调用路径下会是 EMPTY。
        // 审计不该因为一个参数没给上就炸掉整个工具调用。
        assertEquals(TaskIdentity.ANONYMOUS, TaskIdentity.from(McpTransportContext.EMPTY));
        assertEquals(TaskIdentity.ANONYMOUS, TaskIdentity.from(null));
    }

    @Test
    void 传null也当匿名处理() {
        // from(null) 不该 NPE —— 这是最容易被写漏的一手, 而它的症状是
        // "某个调用路径下工具直接 500", 和审计看起来毫无关系。

        assertNull(TaskIdentity.from(null).taskId());
        assertTrue(TaskIdentity.from(null).isAnonymous());
    }

    @Test
    void 空串和空白串都归一化成null() {
        // HTTP 头里一个空串和一个缺失的头语义上都是"没带", 但落到库里
        // 一个是 '' 一个是 NULL。审计表里同时存在这两种"空"最消耗排查时间。
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AuditHeaders.TASK_ID, "   ");
        metadata.put(AuditHeaders.TRACE_ID, "");

        TaskIdentity identity = TaskIdentity.from(McpTransportContext.create(metadata));

        assertNull(identity.taskId(), "空白串要归一化成 null, 不能原样存进去");
        assertNull(identity.traceId(), "空串同理");
        assertTrue(identity.isAnonymous());
    }

    @Test
    void 值不是字符串时也能取出来() {
        // McpTransportContext 的取值类型是 Object。别的采集器 (或者将来
        // 某个自定义 extractor) 塞进来一个非 String 时, 这里不该 ClassCastException。
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AuditHeaders.TASK_ID, 12345);

        assertEquals("12345", TaskIdentity.from(McpTransportContext.create(metadata)).taskId());
    }

    @Test
    void 只有taskId缺了就算匿名_别的字段不缺也没用() {
        // isAnonymous 只看 taskId: 它是"能不能把这次调用归到某件事上"的分水岭。
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(AuditHeaders.TRACE_ID, "trace-1");
        metadata.put(AuditHeaders.ACTOR, "u-1");

        assertTrue(TaskIdentity.from(McpTransportContext.create(metadata)).isAnonymous());
    }
}
