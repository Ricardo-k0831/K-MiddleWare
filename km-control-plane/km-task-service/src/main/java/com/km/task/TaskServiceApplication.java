package com.km.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 任务服务 —— Agent 任务状态的唯一事实源。
 *
 * 端口 8083 (8081 是 MCP 网关, 8082 是 mock 订单服务)。
 *
 * 这个服务只做三件事:
 *   1. 建任务 / 查任务
 *   2. 校验状态流转合法性 (Python 只能上报, 不能决定)
 *   3. 把每一次状态变化追加进 agent_task_event (审计)
 */
@SpringBootApplication
@MapperScan("com.km.task.mapper")
public class TaskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
