package com.km.task.error;

/**
 * 越权请求。对应 HTTP 403。
 *
 * <p>和 {@link TaskNotFoundException} 分开: 那个表示"看不见/不存在",
 * 这个表示"看得见但你不该做这件事"。目前只有一处会抛它 ——
 * 用用户密钥给别的租户建任务。任务级的跨租户读取一律伪装成 404,
 * 理由见 {@code CallerIdentity} 的类注释。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
