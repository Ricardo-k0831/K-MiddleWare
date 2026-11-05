package com.km.task.cancel;

import com.km.task.domain.AgentTask;
import com.km.task.retry.RetryProperties;
import com.km.task.web.dto.CancelTaskResponse;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 通知 Python 运行时"这个任务被取消了"。
 *
 * <h3>★ 这是尽力而为的旁路, 不是取消的一部分 ★</h3>
 *
 * <p>取消的<b>事实</b>在 {@code TaskService.cancel()} 落库那一刻就成立了。
 * 这里只负责让正在跑的 LLM/工具调用早点停下, 以及把 checkpoint 上占着
 * 会话线程的挂起状态收尾。通知失败<b>不能</b>把接口变成 5xx —— 否则
 * 运维会以为"取消没生效"而反复点, 实际上任务早就是 CANCELLED 了。
 *
 * <p>通知失败的真实代价写在 {@link CancelTaskResponse.RuntimeStop} 上:
 * 状态已取消, 但 Python 那次执行可能继续跑到自然结束。它的后续上报会
 * 因执行已闭链被 409 拒掉, 不会污染状态。
 *
 * <h3>★ 为什么复用 km.retry.dispatch.chat-url ★</h3>
 *
 * <p>它指的就是 Python Agent 运行时的地址 (文档里写着"不带 /chat"),
 * 重试派发和取消打的是同一个进程。再开一个 {@code km.cancel.python-url}
 * 只会制造"两个配置指向同一服务、改了一个忘另一个"的经典事故。
 * 属性名里的 chat 是历史包袱, 语义上它就是 runtime-url。
 *
 * <h3>★ 必须是 HTTP/1.1 ★</h3>
 *
 * <p>和 {@code RetryDispatcher.buildClient} 是同一个坑 (E2E 实测 2026-11-04):
 * JDK HttpClient 默认 HTTP/2, 对明文 http:// 会先发 h2c 升级, uvicorn
 * 不支持并丢掉请求体, 表现为 FastAPI 422 "body 缺失"。两个客户端都
 * 指向同一台 uvicorn, 这条约束必须写两遍。
 */
@Component
public class PythonCancelNotifier {

    private static final Logger log = LoggerFactory.getLogger(PythonCancelNotifier.class);

    private final RestClient restClient;

    @Autowired
    public PythonCancelNotifier(RetryProperties properties) {
        this(buildClient(properties.getDispatch().getChatUrl()));
    }

    PythonCancelNotifier(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 通知 Python 停止 {@code task} 的运行。
     *
     * @param task        刚被取消的任务 (status=CANCELLED, traceId 仍是本次执行的)
     * @param reason      取消原因, 只进 Python 日志, 便于两边对账
     * @return 运行时的处理结果; 任何异常都被吞掉并反映在返回值里
     */
    public CancelTaskResponse.RuntimeStop notify(AgentTask task, String reason) {
        String taskId = task.getAgentTaskId();

        // session_id / tenant_id 是 Python 算线程身份的必要输入 —— 少了
        // session_id, 它会去收尾"任务线程", 而那次运行其实在会话线程上,
        // 收尾就打空了, 会话继续被占着。
        Map<String, Object> body = new LinkedHashMap<>();
        putIfPresent(body, "session_id", task.getSessionId());
        putIfPresent(body, "tenant_id", task.getTenantId());
        putIfPresent(body, "trace_id", task.getTraceId());
        putIfPresent(body, "reason", reason);

        try {
            Map<?, ?> response = restClient.post()
                    .uri("/tasks/{taskId}/cancel", taskId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                log.warn("取消通知返回空响应 task={} —— 状态已取消, 但运行时结果未知", taskId);
                return new CancelTaskResponse.RuntimeStop(true, false, false, null, "空响应");
            }
            boolean stopped = Boolean.TRUE.equals(response.get("stopped"));
            boolean released = Boolean.TRUE.equals(response.get("released"));
            String threadId = asString(response.get("thread_id"));
            log.info("取消通知完成 task={} stopped={} released={} thread={}",
                    taskId, stopped, released, threadId);
            return new CancelTaskResponse.RuntimeStop(true, stopped, released, threadId, null);
        } catch (RestClientException ex) {
            // 连不上 / 超时 / 非 2xx 都归到这里。状态已经在库里改了, 这里
            // 只记日志 —— 见类注释。
            log.warn("取消通知失败 task={}: {} —— 状态已取消, 运行时可能还在跑",
                    taskId, ex.getMessage());
            return CancelTaskResponse.RuntimeStop.unreachable(ex.getMessage());
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    /**
     * 和 RetryDispatcher.buildClient 同样的 HTTP/1.1 + 显式超时。
     *
     * <p>读超时给 15 秒: Python 的取消接口最多等正在跑的图 10 秒才返回,
     * 比它短会让 Java 在对方即将成功时误判成"没连上"。
     */
    static RestClient buildClient(String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
