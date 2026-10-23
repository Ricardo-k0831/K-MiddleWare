package com.km.audit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * 审计投递的自动配置 —— 各服务引入本模块后<b>不用写任何装配代码</b>。
 *
 * <h3>为什么必须是自动配置, 不能靠 @Component 扫描</h3>
 *
 * 本模块的包是 {@code com.km.audit}, 而使用它的服务分别在 {@code com.km.task}
 * 和 {@code com.km.mcp} 下 —— {@code @SpringBootApplication} 只扫自己所在包及其子包,
 * <b>扫不到这里</b>。
 *
 * <p>更麻烦的是它的失败方式: {@code @Component} 加了也不会报"扫描不到",
 * 只会在注入点报 {@code No qualifying bean of type 'AuditPublisher'},
 * 而这个错指向的是使用方, 不是这里 —— 排查方向一开始就被带偏了。
 *
 * <p>自动配置走的是另一条路: 由
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 声明, Boot 直接读这个文件, 与包扫描范围无关。
 *
 * <h3>为什么 enabled=false 时【不】让这个 Bean 消失</h3>
 *
 * 看起来写个 {@code @ConditionalOnProperty} 更"干净", 但那样关掉审计之后
 * 所有注入 {@code AuditPublisher} 的业务代码会直接启动失败 —— 关掉一个
 * <b>旁路</b>功能却把主流程弄挂了, 和审计的定位正好相反。
 *
 * <p>所以 Bean 一直在, 由 {@link AuditPublisher#start()} 自己判断要不要连 broker。
 * "关掉"的语义是"发布变成空操作", 不是"这个对象不存在"。
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

    /**
     * 审计投递器。
     *
     * <p>{@code @ConditionalOnMissingBean} 是为了留一个口子: 将来某个服务要
     * 换成走公司统一的日志通道, 注册自己的 {@code AuditPublisher} 就能顶掉这个,
     * 不用去改本模块。
     */
    @Bean
    @ConditionalOnMissingBean
    public AuditPublisher auditPublisher(AuditProperties props, ObjectMapper objectMapper) {
        return new AuditPublisher(props, objectMapper);
    }
}
