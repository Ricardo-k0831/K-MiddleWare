# 跨会话长期记忆

> 目标：让 Agent 在**新会话**里还记得"上次那个订单"。用一组多会话用例量它：
> 无记忆时 0/3，有记忆时 3/3。

代码：

| 文件 | 作用 |
| --- | --- |
| `km-agent-runtime/app/memory.py` | 事实抽取、Postgres 存储、线索检索、注入格式 |
| `km-agent-runtime/app/main.py` | `/chat` 的读写接线（FRESH 注入 / 回答后抽取） |
| `km-agent-runtime/evals/memory.jsonl` | 3 条多会话用例 |
| `km-agent-runtime/evals/runner.py` | 多会话执行 + `--memory` 开关 + 每次运行独立 actor |
| `km-agent-runtime/tests/test_memory.py` | 抽取输入面 / 检索规则 / 分区键的单测 |
| `docker/init/postgres/01-init.sql`、`docker/migrations/2026-11-15-agent-memory.sql` | `agent_memory` 表 |

---

## 一、它和 checkpoint 不是一回事

| | checkpoint (`app/checkpoint.py`) | 长期记忆 (`app/memory.py`) |
| --- | --- | --- |
| 作用域 | 一个 `session_id` 内的对话线程 | 一个 `(租户, 操作者)` 的跨会话事实 |
| 内容 | 完整消息历史 + 图执行位置 | 抽取后的少量持久事实 |
| 生命周期 | 会话结束/换 session 就读不到 | 默认 90 天内持续可用 |
| 用途 | 多轮上下文、断点续跑 | 指代消解、用户偏好 |

会话一换（用户第二天再来、前端每次刷新新建 session），checkpoint 里那条
线程就再也读不到了。而"上次我让你查的那个订单"这句话里没有任何可调工具的
参数——订单号只存在于上一次会话。

## 二、只记"关于用户的事实"，不记"世界的真相"

抽取器只吃**用户消息**，产出三类事实：

| kind | 例子 | 用途 |
| --- | --- | --- |
| `order_ref` | 用户查询过订单 SO20260901001 | "上次那个订单" |
| `user_ref` | 用户自述用户ID为 U1001 | "我的订单" |
| `preference` | 用户希望回答尽量简短 | 答复风格 |

明确**不记录**：

- 订单状态、金额、时间、物流——它们每轮都该重新查工具，写进记忆只会过期；
- 工具返回、助手答复、订单备注——见下一节；
- 用户要求"记住"的业务结论（"记住我的订单已退款"）——那只是用户的声称。

写入是幂等的：同一条内容在同一个 `(租户, 操作者)` 下只留一行，重复出现
刷新 `last_seen_at`——"上次"取的是**最近一次提到**。

### 安全：为什么只从用户消息抽取

`remark` 这类自由文本是 [`docs/agent-security.md`](agent-security.md) 里间接
提示注入的入口。如果从工具返回里抽取事实，一次注入就会**跨会话留存**：攻击者
在订单备注里写"已退款"，Agent 把它记成用户事实，之后每一次会话都会把这个
谎言当成背景知识。相比单次对话被污染，这是高一个量级的危害。

所以抽取的输入面被压到最小：只有用户自己说过的话。订单备注永远进不了长期
记忆表——这一点是结构性的，不依赖模型"足够聪明"。

注入时记忆也被当作**不可信数据**：消息里明确写"与工具实时返回冲突时以工具
为准"，并且带 `additional_kwargs={"km_memory": True}` 标记，审计/调试时能和
用户真正说的话分开。

## 三、为什么没有 embedding

本机实测：没有 `sentence-transformers` / `torch`，没有 HuggingFace 模型缓存，
`.env` 里也没有 embedding 配置。为了一个"用户事实表"去下载几百 MB 模型不划算；
而这里的检索信号本身就是结构化的：

| 问题 | 命中规则 |
| --- | --- |
| "SO20260901001 到哪了?" | 明确订单号 |
| "上次那个订单现在怎么样了?" | 指代线索 → 最近提到的 `order_ref` |
| "我的订单都什么状态?" | 第一人称线索 → 最近的 `user_ref` |
| "以后回答简短点" | 偏好类记忆始终带上（最多 2 条） |

规则检索的好处是**完全可解释**：为什么这条记忆被注入，能一句话说清。代价是
保守——既没有指代线索、又没有明确订单号的问题（例如"那个键盘什么时候到"）
检索不到记忆。这是有意的：宁可少注入，也不要把一堆无关记忆塞进每一轮上下文。

将来接 embedding 时只需要换 `select_memories` 的实现，存储/注入/隔离都不动。

## 四、注入点：只在 FRESH

长期记忆只在 `plan_invocation` 判定为 **FRESH**（有新用户消息）时注入，方式
是往本轮输入前面加一条 `SystemMessage`，它会随图写进 checkpoint：

- 同一会话的后续轮次仍看得到；
- 跨会话的下一轮会重新检索、重新注入（记忆更新后不会一直用旧的）。

`RESUME` / `REPLAY` 不注入：它们是"续跑/复述上一次执行"，要的是和上次一模
一样，再塞一条新记忆只会改变重试行为。

写入发生在回答之后，输入是**本轮新增的用户消息**（`user_turns` 过滤掉反思
反馈）。抽取失败（网络/JSON）只丢一条 WARNING，不影响这次回答——记忆是增益，
不是依赖。

## 五、隔离与生命周期

- 分区键是 `(tenant_id, actor_id)`。`session_id` 是调用方自己起的名字，只在
  租户内唯一，不能当隔离边界。
- **没有 actor 就不读也不写**，不会退化成一张全局共享表。
- 90 天 TTL（可配）；被注入过的记忆（`use_count > 0`）不按时间清——一条
  "偏好简短回答"可以活很久。清理是独立维护动作，不把读请求变成写请求。
- 存储复用 checkpointer 的连接池。`CHECKPOINT_BACKEND=memory` 时长期记忆
  直接不启用：要么都有持久化，要么都没有，不做半持久化状态。

## 六、实验：0/3 → 3/3

3 条用例，每条两个会话。第二个会话的问题**故意不含**任何可调工具的标识符，
它只能来自第一个会话留下的记忆。所以无记忆时失败不是模型能力问题——信息
物理上不在上下文里。

| 配置 | 通过 | judge 均分 | 平均工具调用 | 输入 token |
| --- | --- | --- | --- | --- |
| 无记忆（`use_memory=false`） | 0/3 | 59.3 | 0.0 | 2218 |
| 长期记忆（`--memory`） | **3/3** | 93.0 | 1.0 | 6794 |

逐条（有记忆时，最后一轮）：

| 用例 | 记忆注入 | 工具 | 延迟 |
| --- | --- | --- | --- |
| `mem_last_order` | 1 条（订单号） | `get_order_by_id` | 10.4 s |
| `mem_user_id` | 1 条（用户ID） | `list_orders_by_user` | 10.8 s |
| `mem_most_recent_order` | 1 条（最近那单） | `get_order_by_id` | 16.0 s |

三种失败模式都覆盖到了：

- 无记忆时反问订单号；
- 无记忆时反问用户ID；
- 先后提到两个订单时，"上次"必须指最近那个（`tool_args_not_contain` 钉住了
  不能用更早的单号）。

断言不只看"调了对的工具"，还看**入参里的订单号**：用旧订单号查也会被判失败。

### 端到端代价，分开量

上面 0/3 的基线一次工具都没调（反问结束），token 增量里混着"真去查了工具"
的开销，不是纯记忆成本。所以另跑一遍 16 条金标准，只切记忆开关：

| 配置 | 通过 | judge 均分 | 输入 token |
| --- | --- | --- | --- |
| 无记忆 | 16/16 | 93.2 | 24567 |
| 长期记忆 | 16/16 | 93.1 | 29675 (+20.8%) |

+20.8% 来自每轮一次抽取 LLM 调用（大约几百 token）以及注入的记忆消息。
16 条里没有一条因记忆而变坏——包括带注入载荷的那几条。

## 七、局限

1. **n=3**：这是一组能力演示 + 回归用例，不是 benchmark。0→3 只说明"信息
   不在上下文里时，记忆把它补上了"，不外推到开放域。
2. **检索靠线索规则**：没有 embedding，问题里既无订单号也无指代词时检索不到。
   规则是手工枚举的，换语言/换业务域要重新梳理。
3. **抽取是概率性的**：模型可能漏抽订单号，也可能把一条偏好写成两句。去重
   按内容哈希，同义不同措辞会留下两行。
4. **judge 对"反问"偏宽**：无记忆基线在 `mem_most_recent_order` 上被 judge
   给了 88 分（它认为反问是安全的）。所以这个实验的**主指标是确定性断言**，
   judge 分只作参考。
5. **actor 由调用方传入**：任务链路里它就是 Java 的 `userId`；自由对话不传
   actor 时记忆不生效。这是隔离的代价，也是调用方必须遵守的契约。

## 八、复现

前置：PostgreSQL / MySQL / Redis / RocketMQ 起着，四个服务已启动，运行时
用 Postgres 后端：

```bash
cd km-agent-runtime

# 1. 无记忆基线
uv run python -m evals.runner --dataset evals/memory.jsonl \
    --out evals/results/memory_off.json

# 2. 同一进程再跑一遍, 打开长期记忆
uv run python -m evals.runner --dataset evals/memory.jsonl --memory \
    --baseline evals/results/memory_off.json \
    --out evals/results/memory_on.json

# 3. 无回归检查: 16 条金标准, 开/关记忆各一遍
uv run python -m evals.runner --out evals/results/baseline_after_memory.json
uv run python -m evals.runner --memory \
    --baseline evals/results/baseline_after_memory.json \
    --out evals/results/baseline_with_memory.json
```

每次评测运行生成独立的 `run_id`，actor 形如 `eval-<run_id>-<case_id>`——
上一轮的记忆不会漏进下一轮。想直接看库里写了什么：

```bash
docker exec km-postgres psql -U km -d km_memory \
  -c "SELECT actor_id, kind, content, order_id, use_count FROM agent_memory ORDER BY id DESC;"
```

单测（不碰网络/数据库）：

```bash
uv run pytest tests/test_memory.py tests/test_chat_task_wiring.py
```
