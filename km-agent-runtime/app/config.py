"""运行时配置。

所有配置从环境变量读取, 变量清单见仓库根目录的 .env.example。
本地开发时把 .env.example 复制成 .env 放在【仓库根目录】(不是这里),
pydantic-settings 会自动向上找到它。
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        # 从仓库根目录读 .env。env_file 的路径相对于【当前工作目录】,
        # 所以约定: 所有命令都在 km-agent-runtime/ 下执行。
        env_file=("../.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # ---------- LLM ----------
    # 走 OpenAI 兼容协议, 所以 DeepSeek / 通义千问 / OpenAI / 本地 vLLM 都能直接用,
    # 换供应商只需要改这三个环境变量。
    llm_base_url: str = "https://api.deepseek.com/v1"
    # 这里刻意留空。真实 key 放仓库根目录的 .env (已被 .gitignore 忽略),
    # 由 pydantic-settings 覆盖这个默认值。
    # 不要把 key 直接写在这行 —— 这个文件是被 git 跟踪的, 一次 git add . 就会提交上去。
    llm_api_key: str = ""
    llm_model: str = "deepseek-chat"
    llm_temperature: float = 0.0  # Agent 场景要确定性, 不要创造力

    # ---------- MCP ----------
    mcp_order_server_url: str = "http://localhost:8081/mcp"
    mcp_auth_token: str = "dev-token"
    # 单次 MCP 工具调用的超时。Agent 场景下宁可快速失败也不要挂死。
    mcp_tool_timeout_seconds: float = 30.0

    # ---------- 服务自身 ----------
    app_host: str = "0.0.0.0"
    app_port: int = 8000

    # ---------- 任务服务 (Java 控制面) ----------
    # /chat 带 task_id 时把任务状态回调到这里。Java 是任务状态的唯一事实源,
    # Python 只上报"我到了哪一步"。见 docs/task-state-machine.md。
    km_task_service_url: str = "http://localhost:8083"
    # 回调是旁路, 宁可丢弃也不要拖慢 Agent 主流程 —— 所以超时给得短。
    km_task_service_timeout_seconds: float = 2.0
    # 提交审计时的内部认证 token (HTTP 头 X-KM-Audit-Token)。
    #
    # 必须和 Java 侧 km.audit.ingest.token 一致, 否则 /api/audit/events 会回 401,
    # 而表现只是"审计一条都没有" —— 所以宁可把它写进显式配置并在 .env.example
    # 里留位置, 也不要在代码里偷偷写死。
    #
    # 默认值和 Java 侧一样是开发占位值 (Java 启动时会为它打一条 WARN)。
    # 生产用 AUDIT_INGEST_TOKEN 环境变量覆盖, 和 Java 的 KM_AUDIT_INGEST_TOKEN 配对。
    audit_ingest_token: str = "dev-audit-token-change-me"

    # ---------- PostgreSQL ----------
    postgres_host: str = "localhost"
    postgres_port: int = 5432
    postgres_db: str = "km_memory"
    postgres_user: str = "km"
    postgres_password: str = "km_dev_pwd"

    @property
    def postgres_dsn(self) -> str:
        return (
            f"postgresql://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )


@lru_cache
def get_settings() -> Settings:
    """缓存单例。FastAPI 的依赖注入里直接 Depends(get_settings) 即可。"""
    return Settings()
