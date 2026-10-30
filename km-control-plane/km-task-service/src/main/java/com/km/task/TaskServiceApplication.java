package com.km.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 任务服务 —— Agent 任务状态的唯一事实源。
 *
 * 端口 8083 (8081 是 MCP 网关, 8082 是 mock 订单服务)。
 *
 * 这个服务只做三件事:
 *   1. 建任务 / 查任务
 *   2. 校验状态流转合法性 (Python 只能上报, 不能决定)
 *   3. 把每一次状态变化追加进 agent_task_event (审计)
 *
 * <h3>审计相关的 Bean 是从哪来的</h3>
 *
 * {@code AuditPublisher} / {@code AuditProperties} 不在 {@code com.km.task} 下,
 * 所以<b>不在本类的包扫描范围内</b>。它们由 km-audit-core 里的
 * {@code com.km.audit.AuditAutoConfiguration} 提供 —— 走的是自动配置
 * (读 {@code AutoConfiguration.imports}), 与包扫描无关。
 *
 * <p>所以这里<b>不再有</b> {@code @EnableConfigurationProperties(AuditProperties.class)}:
 * 自动配置已经做过了, 留着只会让人以为审计的装配在本服务里,
 * 找配置来源时会先翻错地方。
 *
 * <p>另: agent_task_service 和 audit_log 都在这一个库里, 本服务既是
 * 任务状态的唯一事实源, 也是【四层审计的唯一入库口】(消费端见
 * {@code com.km.task.audit.AuditLogConsumer})。这两件事共用一个服务不是巧合 ——
 * 审计要记的就是任务的状态变化。
 */
// @EnableScheduling 只是打开调度基础设施; 真正的自动重试 Bean
// (RetryScheduler) 由 km.retry.scheduler.enabled 控制是否创建。
// 所以默认配置下这里什么也不会跑 —— 开关在配置里, 不在这里。
@EnableScheduling
@SpringBootApplication
@MapperScan("com.km.task.mapper")
public class TaskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
