package com.km.task.heartbeat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 主动探活: 问 Python "trace &lt;id&gt; 这次执行你还在跑吗?"。
 *
 * <pre>
 *   GET {probe-url}/tasks/{traceId}/alive
 *   200 {"alive": true}   -> ALIVE      这次执行确实在 Python 进程里
 *   200 {"alive": false}  -> NOT_ALIVE  Python 明确回答"我这儿没有它"
 *   其它一切 (超时/拒连/5xx/字段缺失) -> UNKNOWN
 * </pre>
 *
 * <h3>★ 为什么关链必须靠探活, 不能靠"心跳很旧了" ★</h3>
 *
 * <p>心跳是<b>单向</b>的: Python → Java。它停了有至少三种解释 ——
 * Python 进程死了、Python 活着但连不上 Java、Java 自己这台收不到网络。
 * 直接按"心跳旧了"关链, 在第二、第三种情况下会把一个正在跑 (并且可能在
 * 最后一刻成功) 的任务判死, 然后 Python 的终态上报全被 409 拒掉 ——
 * 这就是双写。
 *
 * <p>探活把判断变成<b>双向</b>的: 只有当 Python <b>亲口说</b>没有这条执行,
 * 才能确定它没在跑。而"亲口说"这个动作本身需要进程活着、事件循环能响应,
 * 所以它不会在 Python 卡死时错误地回答 false —— 那种情况下它是超时 (UNKNOWN)。
 *
 * <h3>★ 前提: 探活必须打到真正在跑的那个 Python 进程 ★</h3>
 *
 * <p>当前部署是单实例, 所以"本进程没有它" = "没人在跑它"。一旦 Python
 * 多实例部署, 负载均衡可能把探活打到另一台, 它会诚实地回答 false ——
 * 那是误判。多实例必须把"在跑清单"放到共享存储 (Redis key + TTL),
 * 或者给探活加实例亲和性。这一点写在 docs/task-state-machine.md 里。
 */
@Component
public class HeartbeatProbe {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatProbe.class);

    /** 探活结论。只有 NOT_ALIVE 可以授权关链。 */
    public enum Verdict {
        /** Python 明确回答: 这条执行在跑。 */
        ALIVE,
        /** Python 明确回答: 这条执行不在跑。 */
        NOT_ALIVE,
        /** 问不出来 (不可达 / 超时 / 协议不对)。此时<b>什么都不做</b>。 */
        UNKNOWN
    }

    private final RestClient client;

    /**
     * 和 RetryDispatcher 一样: 自己建 {@code RestClient}, 不注入
     * {@code RestClient.Builder} —— Boot 4 把 REST 客户端拆成独立模块后,
     * 这个服务里并没有那个 Bean (踩过一次启动失败的坑)。
     */
    @Autowired
    public HeartbeatProbe(HeartbeatProperties properties) {
        this(build(properties));
    }

    HeartbeatProbe(RestClient client) {
        this.client = client;
    }

    /** 探活 traceId 对应的任务是否还在 Python 进程里。 只是查看存活状态, 不会有后续决定*/
    public Verdict probe(String traceId) {
        Map<String, Object> body;
        try {
            // 接口返回值为Map集合, 有 bool alive, trace_id, elapsedSeconds 三个key
            body = client.get()
                    .uri("/tasks/{traceId}/alive", traceId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientResponseException e) {
            // 404 最常见的原因是 Python 还没有这个接口 (旧版本) ——
            // 不能把它当成"没有这条执行", 否则升级不同步会误杀全部在跑任务。
            log.warn("探活返回 HTTP {} (视作未知) trace={}: {}",
                    e.getStatusCode().value(), traceId, firstLine(e.getResponseBodyAsString()));
            return Verdict.UNKNOWN;
        } catch (ResourceAccessException e) {
            log.warn("探活不可达 (视作未知) trace={}: {}", traceId, e.getMessage());
            return Verdict.UNKNOWN;
        } catch (RuntimeException e) {
            log.warn("探活异常 (视作未知) trace={}", traceId, e);
            return Verdict.UNKNOWN;
        }

        Object alive = body == null ? null : body.get("alive");
        if (Boolean.TRUE.equals(alive)) {
            return Verdict.ALIVE;
        }
        if (Boolean.FALSE.equals(alive)) {
            return Verdict.NOT_ALIVE;
        }
        // 字段缺失 / 不是布尔: 协议没对上。宁可当成未知, 也不能按 false 处理。
        log.warn("探活响应缺少布尔字段 alive (视作未知) trace={} body={}", traceId, body);
        return Verdict.UNKNOWN;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        String line = nl < 0 ? text : text.substring(0, nl);
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private static RestClient build(HeartbeatProperties config) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getProbeConnectTimeoutSeconds()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(config.getProbeTimeoutSeconds()));
        return RestClient.builder()
                .baseUrl(config.getProbeUrl())
                .requestFactory(factory)
                .build();
    }
}
