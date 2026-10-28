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
 * <h3>两道防线: 谁能写, 以及写进来的话是否自洽</h3>
 *
 * <p><b>第一道是认证。</b> 提交方必须带 {@code X-KM-Audit-Token}, 值和服务端
 * 配置的 {@code km.audit.ingest.token} 一致; 这是服务间共享密钥, 不经过用户。
 * 没带 / 带错一律 401, 由 {@link com.km.task.audit.AuditIngestAuthFilter}
 * 在进入控制器之前挡掉 —— <b>一份能被无声篡改的审计, 比没有审计更糟</b>,
 * 因为你会在出事之后相信它。
 *
 * <p><b>第二道是身份核对。</b> token 证明了"你有权写", 但证明不了"你写的
 * 这条属于你声称的那个任务"。所以 {@link AuditIngestService} 会拿
 * traceId 去 {@code agent_task_attempt} 核对, 矛盾一律拒收、缺失的 taskId
 * 用执行记录补齐。规则和降级策略都在那个类的注释里。
 *
 * <p>注意这仍然是<b>内部采集通道</b>, 不是对外的 API: token 是共享的, 不是
 * 每个调用方独立的凭据。将来要区分"网关报的"和"运行时报的", 再按调用方
 * 发不同 token 即可, 布局不用变。
 *
 * <h3>返回值为什么是 202 不是 201</h3>
 *
 * <p>202 Accepted 表达的是"收到了, 会异步处理" —— 正是这里发生的事:
 * 事件被投进 MQ 就直接返回了, 落库是消费端稍后做的。返回计数而不是
 * 空体, 是为了让调用方能看出有没有被丢掉, 以及丢在哪一步
 * ({@code rejected} / {@code unverified} / {@code late})。
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
    public AuditIngestService.IngestResult ingest(@Valid @RequestBody List<AuditEventRequest> events) {
        // 结果类型直接复用 service 的 record: 中间再包一层 DTO 只会出现
        // "service 加了计数, HTTP 忘了透传"这种两边定义漂移的问题。
        return ingestService.ingest(events);
    }
}
