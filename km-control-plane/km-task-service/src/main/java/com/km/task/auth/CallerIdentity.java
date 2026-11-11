package com.km.task.auth;

import com.km.task.error.ForbiddenException;
import com.km.task.error.TaskNotFoundException;

/**
 * 一次控制面请求的调用方身份。
 *
 * <p>由 {@link ControlAuthFilter} 从 {@code X-KM-Api-Key} 解析出来, 放进
 * request attribute; Controller 用 {@code @RequestAttribute} 取, 不碰
 * ThreadLocal —— 请求一旦进线程池, ThreadLocal 的清理就依赖 finally,
 * 漏一次就是"上一个请求的身份跟着下一个请求跑"。
 *
 * <h3>租户是隔离边界, 不是新功能</h3>
 *
 * <p>任务表本来就有 {@code tenant_id} 列, 之前只是没被用来做鉴权。这里
 * 直接拿它当边界: 一个密钥只看得到自己租户的任务。SERVICE 是通配 ——
 * Python 运行时替所有租户执行, 它必须能读任意任务的状态。
 *
 * <h3>越权访问为什么回 404 而不是 403</h3>
 *
 * <p>403 等于告诉对方"这个 ID 存在, 只是你无权看"; 对任务 ID 来说那就是
 * 一个可枚举的存在性探针。所以跨租户的读/改一律走
 * {@link TaskNotFoundException} (404), 和"真的不存在"不可区分。
 *
 * <p>唯一的例外是<b>建任务时声明了别的租户</b>: 请求体里的 tenantId 是
 * 调用方自己写的, 不存在信息泄露, 所以回 403, 让调用方知道是参数写错了。
 */
public record CallerIdentity(String tenantId, String userId, ControlAuthProperties.Role role) {

    /** request attribute 名。过滤器写, Controller 读, 两边都引用这个常量。 */
    public static final String ATTRIBUTE = "km.control.caller";

    /** 通配租户。只应出现在 SERVICE 身份上。 */
    public static final String WILDCARD_TENANT = "*";

    /** 认证关闭时使用的身份 —— 和 SERVICE 一样通配, 但 user 明确写 system。 */
    public static CallerIdentity system() {
        return new CallerIdentity(WILDCARD_TENANT, "system", ControlAuthProperties.Role.SERVICE);
    }

    public boolean isService() {
        return role == ControlAuthProperties.Role.SERVICE;
    }

    /** 能否看见这个租户的任务。null / 空白按 {@code default} 处理, 和建任务时的默认值一致。 */
    //当前CallerIdentity 是否能看到指定租户taskTenantId 的任务
    public boolean canSeeTenant(String taskTenantId) {
        // 如果是 role=SERVICE 或者 tenantId=* , 则能看见所有租户的任务
        if (isService() || WILDCARD_TENANT.equals(tenantId)) {
            return true;
        }
        // 对比当前CallerIdentity 的租户ID 是否与任务租户ID 一致
        String normalized = taskTenantId == null || taskTenantId.isBlank()
                ? "default"
                : taskTenantId;
        return tenantId != null && tenantId.equals(normalized);
    }

    /**
     * 看不见就按"任务不存在"处理 —— 见类注释: 不做 403 存在性泄露。
     *
     * @throws TaskNotFoundException 租户不匹配
     */
    public void requireTaskVisible(String agentTaskId, String taskTenantId) {
        if (!canSeeTenant(taskTenantId)) {
            throw new TaskNotFoundException(agentTaskId);
        }
    }

    /**
     * 建任务时的租户落点。
     *
     * <ul>
     *   <li>请求体没写: 用户密钥用自己的租户; SERVICE 保持空白,
     *       交给 {@code TaskService} 的默认租户逻辑。</li>
     *   <li>请求体写了: SERVICE 通配, 原样接受; USER 必须和密钥租户一致,
     *       否则 403 —— 这是"用别人的租户建任务", 不是看不见, 是伪造。</li>
     * </ul>
     */
    public String resolveCreateTenant(String requestedTenantId) {
        // 判断 传入的租户ID 为空
        boolean blank = requestedTenantId == null || requestedTenantId.isBlank();
        //为空
        if (blank) {
            // 如果是role=SERVICE就保持空白 否则返回当前 CallerIdentity 的租户ID
            return isService() ? requestedTenantId : tenantId;
        }
        //不为空
        //如果role是SERVICE 或者 当前身份的tenantId=* 或者 当前身份的tenantId 与 传入的租户ID 一致, 则返回 传入的租户ID
        if (isService() || WILDCARD_TENANT.equals(tenantId) || tenantId.equals(requestedTenantId)) {
            return requestedTenantId;
        }
        throw new ForbiddenException("不能以租户 " + requestedTenantId
                + " 建任务: 当前密钥只属于租户 " + tenantId);
    }
}
