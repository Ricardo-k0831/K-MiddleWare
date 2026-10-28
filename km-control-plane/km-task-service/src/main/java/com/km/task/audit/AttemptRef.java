package com.km.task.audit;

import java.time.LocalDateTime;

/**
 * 一次执行在审计入口眼里的<b>最小快照</b>。
 *
 * <p>只取身份校验用得到的三列, 不把整个 {@code AgentTaskAttempt} 传进来:
 * 缓存里要存成千上万条, 多带一列就是多一份要过期的东西; 而且字段少了,
 * 调用方也就不会在审计入口里顺手改点什么 —— 这里的定位是<b>只读核对</b>。
 *
 * @param agentTaskId 这次执行属于哪个任务 —— 权威值, 用来核对/补齐事件上自报的 taskId
 * @param traceId     链路 ID, 和查询键相同, 保留它只是为了让日志/缓存内容自解释
 * @param endedAt     闭链时刻; {@code null} = 还在跑。晚于它的审计事件算"迟到"
 */

//编译器看到 record AttemptRef(String a, String b, LocalDateTime c) 这一行，自动生成：
//
//自动生成的内容	说明
//全参构造器	AttemptRef(String a, String b, LocalDateTime c)
//访问器方法	a()、b()、c() —— 不是 getA()，就是字段名本身
//字段	private final，不可变
//equals()	按字段值比较
//hashCode()	按字段值计算
//toString()	AttemptRef[a=xxx, b=yyy, c=zzz]
public record AttemptRef(String agentTaskId, String traceId, LocalDateTime endedAt) {

    /** 这次执行是否还开着。判断用 endedAt 而不是 status, 和实体上的方法同一个理由。 */
    public boolean isRunning() {
        return endedAt == null;
    }
}
