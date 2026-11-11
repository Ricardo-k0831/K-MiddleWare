package com.km.task.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制面认证的边界测试。
 *
 * <p>这个类钉的是两件<b>只有在这里才看得见</b>的事:
 *
 * <ol>
 *   <li><b>被拒的请求进不到业务代码</b> —— 只断言 401 是不够的,
 *       一个"先执行业务再返回 401"的实现也能通过。所以探针 Controller
 *       会记调用次数, 被拒时必须是 0。</li>
 *   <li><b>角色边界比租户边界更硬</b> —— USER 密钥能读任务, 但不能上报
 *       状态。否则拿到用户密钥的脚本就能伪造 COMPLETED, 状态机的事实源
 *       就不再是事实源。</li>
 * </ol>
 */
class ControlAuthFilterTest {

    private static final String USER_KEY = "user-key";
    private static final String SERVICE_KEY = "service-key";

    @Test
    void 没有密钥时401且业务代码不执行() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mvc = mvcWith(enabledProperties(), probe);

        mvc.perform(get("/api/tasks/task-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));

        assertEquals(0, probe.calls.get(), "被认证挡掉的请求不该进入 Controller");
    }

    @Test
    void 密钥错误也是401() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mvc = mvcWith(enabledProperties(), probe);

        mvc.perform(get("/api/tasks/task-1").header(ControlAuthFilter.API_KEY_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());

        assertEquals(0, probe.calls.get());
    }

    @Test
    void USER密钥可以读任务且身份被写进request_attribute() throws Exception {
        // Controller 用 @RequestAttribute 取身份, 取不到就返回 "anonymous" ——
        // 所以这里同时验证了"过滤器把身份放进去了"和"Controller 读得到"。
        MockMvc mvc = mvcWith(enabledProperties(), new ProbeController());

        mvc.perform(get("/api/tasks/task-1").header(ControlAuthFilter.API_KEY_HEADER, USER_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("demo-user:USER"));
    }

    @Test
    void USER密钥不能上报状态() throws Exception {
        ProbeController probe = new ProbeController();
        MockMvc mvc = mvcWith(enabledProperties(), probe);

        mvc.perform(patch("/api/tasks/task-1/status")
                        .header(ControlAuthFilter.API_KEY_HEADER, USER_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        assertEquals(0, probe.calls.get(), "角色不足的请求不该进入 Controller");
    }

    @Test
    void SERVICE密钥可以上报状态和心跳() throws Exception {
        MockMvc mvc = mvcWith(enabledProperties(), new ProbeController());

        mvc.perform(patch("/api/tasks/task-1/status")
                        .header(ControlAuthFilter.API_KEY_HEADER, SERVICE_KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("python-runtime:SERVICE"));

        mvc.perform(post("/api/tasks/task-1/heartbeat")
                        .header(ControlAuthFilter.API_KEY_HEADER, SERVICE_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void 关闭认证时使用系统身份() throws Exception {
        // 关闭也必须设置身份: Controller 不区分"有没有鉴权", 一律从
        // attribute 读。否则开关会漏到业务层, 每个入口都要判空。
        ControlAuthProperties properties = new ControlAuthProperties();
        properties.setEnabled(false);
        MockMvc mvc = mvcWith(properties, new ProbeController());

        mvc.perform(get("/api/tasks/task-1"))
                .andExpect(status().isOk())
                .andExpect(content().string("system:SERVICE"));
    }

    @Test
    void 只拦任务路径_别的接口不受影响() throws Exception {
        // 过滤器是 @Component, 挂到所有请求上。shouldNotFilter 写错的话
        // /api/audit 会开始要 X-KM-Api-Key —— 症状离原因很远。
        MockMvc mvc = mvcWith(enabledProperties(), new ProbeController());

        mvc.perform(get("/api/other"))
                .andExpect(status().isOk())
                .andExpect(content().string("public"));
    }

    @Test
    void 空密钥会被跳过_不能用空头混进来() throws Exception {
        // 配置里有一条空 key 时, 带空头 / 不带头的请求都必须是 401。
        // 否则"漏配一条"就等于"认证完全关掉"。
        ControlAuthProperties properties = enabledProperties();
        properties.getKeys().add(new ControlAuthProperties.ApiKey());
        ProbeController probe = new ProbeController();
        MockMvc mvc = mvcWith(properties, probe);

        mvc.perform(get("/api/tasks/task-1")
                        .header(ControlAuthFilter.API_KEY_HEADER, ""))
                .andExpect(status().isUnauthorized());

        assertEquals(0, probe.calls.get());
    }

    // ------------------------------------------------------------------
    //  夹具
    // ------------------------------------------------------------------

    private static ControlAuthProperties enabledProperties() {
        ControlAuthProperties properties = new ControlAuthProperties();

        ControlAuthProperties.ApiKey user = new ControlAuthProperties.ApiKey();
        user.setKey(USER_KEY);
        user.setTenant("demo");
        user.setUser("demo-user");
        user.setRole(ControlAuthProperties.Role.USER);

        ControlAuthProperties.ApiKey service = new ControlAuthProperties.ApiKey();
        service.setKey(SERVICE_KEY);
        service.setTenant(CallerIdentity.WILDCARD_TENANT);
        service.setUser("python-runtime");
        service.setRole(ControlAuthProperties.Role.SERVICE);

        // 可变列表: 有一个用例要在表里追加一条空 key。
        properties.setKeys(new java.util.ArrayList<>(List.of(user, service)));
        return properties;
    }

    private static MockMvc mvcWith(ControlAuthProperties properties, ProbeController probe) {
        return MockMvcBuilders.standaloneSetup(probe)
                .addFilters(new ControlAuthFilter(properties))
                .build();
    }

    /**
     * 只用来证明"请求有没有进到业务代码"的探针。
     *
     * <p>刻意不注入真实的 TaskService —— 这里测的是过滤器, 不是业务;
     * 业务规则在 TaskServiceTest 里。
     */
    @RestController
    static class ProbeController {

        private final AtomicInteger calls = new AtomicInteger();

        @GetMapping("/api/tasks/{id}")
        String read(@PathVariable String id,
                    @RequestAttribute(name = CallerIdentity.ATTRIBUTE, required = false)
                    CallerIdentity caller) {
            calls.incrementAndGet();
            return caller == null ? "anonymous" : caller.userId() + ":" + caller.role();
        }

        @PatchMapping("/api/tasks/{id}/status")
        String report(@PathVariable String id,
                      @RequestAttribute(name = CallerIdentity.ATTRIBUTE, required = false)
                      CallerIdentity caller) {
            calls.incrementAndGet();
            return caller == null ? "anonymous" : caller.userId() + ":" + caller.role();
        }

        @PostMapping("/api/tasks/{id}/heartbeat")
        String heartbeat(@PathVariable String id) {
            calls.incrementAndGet();
            return "beated";
        }

        @GetMapping("/api/other")
        String other() {
            calls.incrementAndGet();
            return "public";
        }
    }
}
