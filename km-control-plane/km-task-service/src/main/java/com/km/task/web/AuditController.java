package com.km.task.web;

import com.km.task.audit.AuditIngestService;
import com.km.task.web.dto.AuditEventRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Python 运行时提交审计事件的入口。
 *
 * <pre>
 *   POST /api/audit/events    批量提交审计事件   -> 202
 * </pre>
 *
 * <h3>为什么是【批量】接口</h3>
 *
 * <p>Agent 一次工具调用至少要产生两条事件 (网关一条、存量服务一条), 一轮
 * 对话里 LLM 调用又是好几次。一条一个 POST 的话, 审计的 HTTP 开销会超过
 * 它要记录的业务本身。
 *
 * <p>批量也让 Python 侧能复用已经成熟的形状: 队列 + 后台 worker + 收尾排空
 * (见 {@code app/task_state.py})。worker 每次醒来把队列里攒下的全部取走,
 * 一趟发完。
 *
 * <h3>★ 这个接口不防伪造 ★</h3>
 *
 * <p>任何能访问 8083 的人都能往里写任意 layer / taskId / actor 的审计记录,
 * 包括伪造一条"任务已成功完成"。
 *
 * <p>现在整个控制面都是这个状态 ({@code /api/tasks} 同样没有鉴权), 所以
 * 这里不是单独的短板。但审计比别的接口更值得单独提一句 —— <b>一份能被
 * 无声篡改的审计, 比没有审计更糟</b>, 因为你会在出事之后相信它。
 *
 * <p>要做的话方向是明确的: 只信任 loopback/内网来源, 或者给提交方发一个
 * 只有它和服务端知道的密钥 (MCP 网关那边已经有 {@code mcp_auth_token} 的
 * 位置了)。在那之前, 这个接口的定位是"内部可信网络里的采集通道",
 * 不是"对外的 API"。见 docs/audit.md 第十节。
 *
 * <h3>返回值为什么是 202 不是 201</h3>
 *
 * <p>202 Accepted 表达的是"收到了, 会异步处理" —— 正是这里发生的事:
 * 事件被投进 MQ 就直接返回了, 落库是消费端稍后做的。返回计数而不是
 * 空体, 是为了让调用方能看出有没有被丢掉。
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditIngestService ingestService;

    public AuditController(AuditIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * @param events 事件数组。空数组返回 {@code accepted: 0} 而不是 400 ——
     *               Python 的 worker 醒来时队列可能是空的, 那是一次正常的
     *               轮询, 不该被当成错误
     */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestResult ingest(@Valid @RequestBody List<AuditEventRequest> events) {
        int accepted = ingestService.ingest(events);
        return new IngestResult(accepted, events == null ? 0 : events.size());
    }

    /**
     * @param accepted 成功投进 MQ 的条数
     * @param received 收到的条数
     */
    public record IngestResult(int accepted, int received) {
    }
}
