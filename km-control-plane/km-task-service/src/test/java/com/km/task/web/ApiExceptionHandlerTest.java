package com.km.task.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异常翻译的<b>优先级</b>测试。
 *
 * <p>这个类只守一件事: 兜底的 {@code @ExceptionHandler(Exception.class)} 不能
 * 把"路径不存在"吃掉。它吃过 —— 表现是一条本该 404 的请求返回 500, 并且用
 * {@code log.error} 打一整条堆栈。后果不是调用方看到的状态码错了, 而是
 * <b>日志被污染</b>: 扫描器扫一遍、前端拼错一个 URL, 都会留下一条看似
 * "服务端故障"的记录, 真出故障时没人能从里面认出来。
 *
 * <p>为什么用 MockMvc 而不是直接调 handler 方法: 直接调只能证明"这个方法的
 * 返回值是 404", 证明不了<b>Spring 在两条规则都匹配时选了哪一条</b> ——
 * 而这里要守的恰恰是后者。
 */
class ApiExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PathProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void 路径不存在时返回404而不是500() throws Exception {
        mvc.perform(get("/probe/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void 真正的服务端异常仍然是500_兜底规则没有被这次修改削弱() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"));
    }

    /**
     * 只负责把两种异常抛出 Controller —— 这里验的不是业务, 是异常翻译。
     */
    @RestController
    static class PathProbeController {

        /** Spring 找不到静态资源时抛的就是这个。 */
        @GetMapping("/probe/missing")
        public void missing() throws NoResourceFoundException {
            // Spring Framework 7 的构造是三参数: (方法, 资源路径, 提示)
            throw new NoResourceFoundException(HttpMethod.GET, "/probe/missing", "No resource");
        }

        /** 货真价实的 bug, 必须还是 500。 */
        @GetMapping("/probe/boom")
        public void boom() {
            throw new IllegalStateException("boom");
        }
    }
}
