"""控制面 (Java 任务服务) 调用的服务身份。

Java 侧 ``ControlAuthFilter`` 只认一个头: ``X-KM-Api-Key``。Python 运行时
用的是 **SERVICE** 角色 —— 它替所有租户上报状态、发心跳, 所以租户是通配的。

为什么单独一个模块而不是三处各写一个字符串:
头名一旦两边不一致, 表现是"所有上报 401", 而日志里只有 Java 的拒绝记录,
Python 这边看起来一切正常。把它收敛成一个常量, 至少改的时候只需要改一处。

密钥值来自 ``Settings.km_task_service_api_key`` (默认和 Java 的
``KM_CONTROL_SERVICE_KEY`` 开发默认值一致)。空值 = 不带这个头 ——
Java 侧关闭认证时才允许这样。
"""

API_KEY_HEADER = "X-KM-Api-Key"


def control_auth_headers(api_key: str | None) -> dict[str, str]:
    """把服务密钥变成请求头。空值返回空 dict, 不制造一个 ``X-KM-Api-Key: `` 的空头。"""
    return {API_KEY_HEADER: api_key} if api_key else {}
