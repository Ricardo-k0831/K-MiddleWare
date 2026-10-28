package com.km.task.audit;

/**
 * 按 traceId 查执行记录 —— 审计摄入身份校验的唯一数据来源。
 *
 * <p>抽成接口不是为了将来换实现, 而是为了让 {@link AuditIngestService} 的
 * 测试能<b>精确地</b>表达三种情况, 而不用真的连库:
 *
 * <ul>
 *   <li>返回 {@link AttemptRef} —— 查到了</li>
 *   <li>返回 {@code null} —— <b>明确查无</b>。调用方据此判断 "WARN 放行还是 STRICT 拒收"</li>
 *   <li>抛异常 —— <b>查不动</b> (数据库连不上 / 超时)。这和"查无"是两件事,
 *       调用方必须区别对待: 前者是基础设施故障, 不该因此丢掉一整批审计</li>
 * </ul>
 *
 * <p>★ 这个 null 与异常的区分是整套降级策略的地基 ★ 如果实现把异常吞成 null,
 * 一次数据库抖动就会让所有事件变成"未知 trace", 在 STRICT 模式下整批被拒 ——
 * 而真实原因会藏在一条"trace 不存在"的日志后面, 很难查。
 */
public interface AttemptLookup {

    /**
     * @return 执行快照; 没有这个 trace 时返回 {@code null}
     * @throws RuntimeException 查询本身失败 (数据库不可用等)
     */
    AttemptRef findByTrace(String traceId);
}
