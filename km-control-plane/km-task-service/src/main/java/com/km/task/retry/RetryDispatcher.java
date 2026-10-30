package com.km.task.retry;

import com.km.task.domain.AgentTask;
import com.km.task.domain.TaskEventSource;
import com.km.task.domain.TaskStatus;
import com.km.task.service.TaskService;
import jakarta.annotation.PreDestroy;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 把"已经开好新链"的任务真正交给 Python 跑。
 *
 * <h3>为什么派发是异步的</h3>
 *
 * <p>{@code /chat} 是一次完整的 Agent 执行: LLM 多轮 + 工具调用, 几分钟很正常。
 * 如果调度线程同步等它返回, 一轮扫描就会被一个慢任务拖住, 后面的任务全部延后 ——
 * 扫描节奏被最慢的一次执行绑架。所以这里只负责<b>丢进线程池</b>, 结果由 Python
 * 通过 {@code PATCH /status} 回报。派发成功不等于任务成功, 这句话在审计里
 * 必须分得清: 派发只证明"请求到了 Python", 任务结果另有事件。
 *
 * <h3>★ 失败怎么分类 ★</h3>
 *
 * <p>这是整个自动重试里最容易做错的地方。<b>能不能确认 Python 没收到请求</b>,
 * 决定我们能不能安全地关闭这次执行:
 *
 * <ul>
 *   <li><b>连接阶段失败</b> (拒绝连接 / DNS 解析不了 / 连接超时) —— 请求
 *       绝对没有到达 Python, 也没人在跑。此时把刚开的 attempt 标记 FAILED,
 *       任务回到 FAILED, 下一轮退避后还能再试。</li>
 *   <li><b>读超时 / HTTP 5xx</b> —— 请求<b>可能</b>已经在 Python 里执行了。
 *       这时如果回写 FAILED, 就会出现"任务显示失败, 但 Python 还在跑并继续
 *       上报"的双写: 后到的上报会被终态拒绝, 审计里留下一串 409。所以只记
 *       日志, <b>不关链</b>, 让 Python 自己的终态上报来收尾; 真的一直不回来,
 *       那属于"卡住的 PENDING", 需要单独的兜底扫描 (暂未实现), 不在这里猜。</li>
 *   <li><b>线程池满</b> —— 请求根本还没发出去。和连接失败一样, 可以安全地
 *       标记 FAILED。</li>
 * </ul>
 */
@Component
public class RetryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RetryDispatcher.class);

    private final RestClient restClient;
    private final TaskService taskService;
    private final Executor executor;

    /**
     * 自己创建的线程池才需要关。测试注入的 executor 由测试负责。
     */
    private final ThreadPoolExecutor ownedExecutor;

    /**
     * 自己 {@code RestClient.builder()} 而不是注入 {@code RestClient.Builder}:
     * Boot 4 把 REST 客户端拆成了独立模块, 这个服务里并没有自动配置出
     * {@code RestClient.Builder} 的 Bean (启动时直接 NoSuchBeanDefinition)。
     * 我们也不需要用它的定制器 (观测/拦截器) —— 一个带超时的普通客户端即可。
     */
    @Autowired
    public RetryDispatcher(RetryProperties properties, TaskService taskService) {
        this(buildClient(RestClient.builder(), properties.getDispatch()), taskService,
                createExecutor(properties.getDispatch()));
    }

    RetryDispatcher(RestClient restClient, TaskService taskService, Executor executor) {
        this.restClient = restClient;
        this.taskService = taskService;
        this.executor = executor;
        this.ownedExecutor = executor instanceof ThreadPoolExecutor pool ? pool : null;
    }

    /**
     * 把任务交给 Python。方法本身只做"入队", 不会阻塞到执行结束。
     *
     * @param task 刚被 {@link TaskService#retry} 更新过的任务: status=PENDING,
     *             traceId 已经是<b>新</b>链的 id, attempt 已开
     */
    public void dispatch(AgentTask task) {
        try {
            executor.execute(() -> run(task));
        } catch (RejectedExecutionException e) {
            // 队列满 = 请求还没发出去, 可以安全地关链。不关的话任务会停在
            // PENDING 且没人跑, 而调度器只扫描 FAILED —— 这条任务就永远丢了。
            log.warn("重试派发被拒绝 (线程池已满) task={} trace={}",
                    task.getAgentTaskId(), task.getTraceId());
            failAttempt(task, "派发队列已满, 请求未发出: " + e.getMessage());
        }
    }

    // 线程池线程会将任务对象发送给 chat 接口重新执行
    private void run(AgentTask task) {
        String taskId = task.getAgentTaskId();
        String traceId = task.getTraceId();
        try {
            // 发送请求到 Python 服务器 的 chat 接口, 传递任务对象 (Map格式体)
            restClient.post()
                    .uri("/chat")
                    // thread_id 用 task_id: 一次执行就是一个独立会话。
                    // 重试是同一条任务的新执行, 但绝不能继承上一遍的对话上下文 ——
                    // 上一遍的失败过程 (报错、半截工具结果) 会污染这一遍的判断。
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload(task))
                    .retrieve()
                    .toBodilessEntity();
            log.info("重试派发成功 (已交给 Python) task={} trace={}", taskId, traceId);
        } catch (ResourceAccessException e) { //连接失败
            if (neverReachedPython(e)) {
                // 连接阶段就失败了: 确定 Python 没收到, 可以关链。
                log.warn("重试派发连接失败 (Python 不可达) task={} trace={}: {}",
                        taskId, traceId, rootMessage(e));
                failAttempt(task, "派发连接失败 (请求未到达 Python): " + rootMessage(e));
            } else {
                // 读超时: 请求可能已经在跑了。★ 不能关链 ★ 见类注释。
                log.error("重试派发读超时/中断 task={} trace={} —— 结果未知, 不关链, "
                                + "等 Python 自己上报终态: {}",
                        taskId, traceId, rootMessage(e));
            }
        } catch (RestClientResponseException e) {
            // Python 收到了请求但回了错误码。它可能已经开始跑, 也可能在做
            // 入场校验时就拒绝了 (比如状态不一致)。两种情况都交给 Python
            // 的终态上报 / 人工排查, 这里不做"替它下结论"的写操作。
            log.error("重试派发收到 HTTP {} task={} trace={} response={}",
                    e.getStatusCode().value(), taskId, traceId, e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            log.error("重试派发异常 task={} trace={}", taskId, traceId, e);
        }
    }

    //将 任务对象 转换为 Map格式  (Python请求体)
    private Map<String, Object> payload(AgentTask task) {
        Map<String, Object> body = new LinkedHashMap<>();
        // message 用的还是最初的 intent: 重试 = 把同一件事再做一遍。
        // 不能用 error_msg / result 拼一个新提示 —— 那等于换了个任务。
        body.put("message", task.getIntent());
        body.put("thread_id", task.getAgentTaskId());
        body.put("task_id", task.getAgentTaskId());
        body.put("trace_id", task.getTraceId());
        body.put("tenant_id", task.getTenantId());
        body.put("actor", task.getUserId());
        return body;
    }

    /**
     * 回写 FAILED, 把这次刚开的执行关掉。
     *
     * <p>用 {@code SYSTEM_RETRY} 作为 source: 这是系统在兜底, 不是人。
     * 失败原因放进 detail.phase=DISPATCH, 运维一眼能分清"是 Python 任务失败"
     * 还是"根本连 Python 的门都没进"。
     */
    private void failAttempt(AgentTask task, String error) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("phase", "DISPATCH");
        detail.put("error", error);
        try {
            // 回写 FAILED 状态, 因为toStatus是任务的终态, 所以也会把该任务刚开的执行关掉。
            taskService.reportStatus(task.getAgentTaskId(), task.getTraceId(),
                    TaskStatus.FAILED, TaskEventSource.SYSTEM_RETRY, detail);
        } catch (RuntimeException e) {
            // 连回写都出了问题,任务当前状态可能是PENDING, 并且调用可能是因为失败而停止着的. 前后是矛盾的
            log.error("派发失败后回写 FAILED 也失败 task={} trace={} —— 任务可能停在 PENDING",
                    task.getAgentTaskId(), task.getTraceId(), e);
        }
    }

    /**
     * 异常链里是否有"连接阶段"的标志。
     *
     * <p>只看最外层不够: RestClient 会把 IO 异常包成
     * {@link ResourceAccessException}, 真正的类型在 cause 链里。识别"确定没到
     * Python"的白名单, 而不是把"不是读超时"的都当成连接失败 —— 未知异常
     * 一律按"结果未知"处理, 宁可不关链。
     */
    static boolean neverReachedPython(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException
                    || t instanceof HttpConnectTimeoutException
                    || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static RestClient buildClient(RestClient.Builder builder,
                                          RetryProperties.Dispatch config) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()));
        return builder
                .baseUrl(config.getChatUrl())
                .requestFactory(factory)
                .build();
    }

    private static ThreadPoolExecutor createExecutor(RetryProperties.Dispatch config) {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                config.getConcurrency(), config.getConcurrency(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "retry-dispatch-" + seq.incrementAndGet());
                    // daemon: 派发线程不该阻止 JVM 退出。执行中的请求被强杀
                    // 不会丢数据 —— 任务状态在库里, attempt 未闭链, 重启后
                    // 靠"卡住的 PENDING"兜底 (暂未实现) 或人工处理。
                    t.setDaemon(true);
                    return t;
                },
                // AbortPolicy: 满了就抛, 由 dispatch() 捕获并关链。
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }
}
