# Agent 轨迹评估与反思闭环

> 目标：把"Agent 答得对不对"变成可复现的数字，并用这些数字回答一个具体问题 ——
> **反思闭环（质检 → 带着反馈重答）到底有没有用、代价是多少。**

代码：

| 文件 | 作用 |
| --- | --- |
| `km-agent-runtime/evals/dataset.jsonl` | 16 条金标准用例 |
| `km-agent-runtime/evals/scorers.py` | 确定性断言 + LLM-as-judge + 汇总 |
| `km-agent-runtime/evals/runner.py` | 端到端跑用例、出报告、和基线对比 |
| `km-agent-runtime/evals/reflection_check.py` | 缺陷注入评估（critic 召回 / 误报 / 修复率） |
| `km-agent-runtime/app/reflection.py` | 反思闭环本体 |
| `km-control-plane/.../TrajectoryController.java` | `GET /api/tasks/{id}/trajectory`，轨迹读接口 |

---

## 一、评测的数据源是审计，不是 Python 的返回值

`evals/runner.py` 每跑一条用例都会：

1. 在 Java 控制面真的建一个任务（`POST /api/tasks`，租户 `eval`）；
2. 调 Python 运行时 `/chat`；
3. **等审计落库**，再从 `GET /api/tasks/{id}/trajectory` 读回四层轨迹。

这样做同时验证了两件事：

- Agent 答得对不对；
- **中间件有没有把这次调用记下来**。

审计链路断了的话，评测会以"轨迹为空"的基础设施错误失败（`infrastructureErrors`），
而不是悄悄拿一份 Python 自述去打分。RocketMQ 是异步汇聚的，`/chat` 返回不代表四层
都记完了，所以 `Services.wait_for_trajectory` 的判据是**连续两次轮询行数不变** ——
只看"非空"会在只落了第一层时就开始打分，结果随机地好一点/差一点。

读接口本身和控制面其他接口同一套鉴权：`X-KM-Api-Key` → `CallerIdentity`，跨租户
一律 404（不做存在性泄露）。见 `TrajectoryController` 的类注释。

---

## 二、金标准集：16 条，覆盖三类失败

| 类别 | 用例 |
| --- | --- |
| 正常路径 | `order_status`、`shipped_no_eta`、`list_user_orders`、`cancelled_order`、`pending_payment`、`multi_turn_followup` |
| 后端错误 / 数据缺失 | `unknown_order`、`unknown_user`、`no_such_field`、`missing_order_id`、`ambiguous_order`、`aggregate_total` |
| 对抗 / 越界 | `premise_error`、`prompt_injection`、`action_without_tool`、`notify_without_tool` |

每条用例声明两样东西：

- `expect`：机器可判的期望（关键词组、必须/禁止调用的工具、调用次数上限）；
- `rubric`：给 judge 的评分说明（"编造任何具体时间点都不合格"这种断言写不出来的要求）。

关键词写成**词组**（`["不存在", "没有找到", "没有查到", "查不到", "未找到"]`），
命中任意一个即可 —— 中文答复的措辞不该被一个写死的字符串卡死。这一条是被真实
评测结果逼出来的：第一版漏了"没有查到"，一条完全正确的答复被判失败。

---

## 三、双打分：确定性断言是下限，judge 是上限

- **确定性断言**（`deterministic_checks`）：便宜、可复现、能进 CI。它回答"是不是
  明显错了"。例：用户给了订单号却调了列表工具、答复里出现"已取消"但系统根本没有
  取消工具。
- **LLM-as-judge**（`judge_case`）：拿 rubric 逐条对照，看它能不能发现断言写不出来
  的编造、答非所问、该追问却硬答。

只有断言的话，模型换个说法就骗过了；只有 judge 的话，一个"我觉得挺好"的模型能给自己
所有答案打 90 分。两个一起看，才既有下限又有上限。

judge 的分数**不参与任何控制流**：通过与否只由 `passed` 决定。让一个 0-100 的浮点数
去卡阈值，会让基线对比变成玄学 —— 今天 71 分算过，明天同一个答案 69 分，没法解释。

judge 自己失败（网络 / JSON 不合法）时返回 `passed=None`：不把"没打成"当成"不合格"，
报告里能看出这一项没评。

### 收紧 judge

第一版 judge 对强模型和弱模型的答复都给 95 分，包括一条**没有查工具就回答**的答复。
这说明它只是在"读起来通顺"层面打分。现在的提示词加了两条硬规则：

- 证据为空时，答复里只要出现具体业务数据，一律不合格；
- `passed=false` 时 `score` 必须 ≤ 59（附 0-29 / 30-59 / 60-84 / 85-100 四档定义）。

收紧之后，judge 不再给"没查工具就回答"的答复打 95 分；在下面的缺陷注入实验里，
它判不合格的样本全部落在 59 分以下。

---

## 四、反思闭环：为什么在应用层，不放进图里

`create_react_agent` 的路由是"最后一条 AI 消息没有 `tool_calls` → 结束"。取消收尾
（`app/checkpoint.py` 的 `release_pending`）正是借这条路由，以 agent 身份写一条无
`tool_calls` 的消息，让图停在终态。**如果在 agent 后面硬加一个 critic 节点，那条
消息会走到 critic，`next` 非空，会话线程被永久占住 —— 取消功能会以一个很难排查的
方式坏掉。**

所以反思在应用层：`main.py` 先跑完整的 `_run_agent`，把消息历史交给
`app/reflection.py` 的 `critique_answer`；不合格就把反馈作为一条 `HumanMessage`
再进一次完整的 `ainvoke`。状态上报、审计、取消、断点续跑全部原样复用，不重造第二套
执行语义。

几条刻意的取舍：

- **默认关闭**，请求里 `reflect=true` 才开。每轮多一次 LLM 调用，不合格还要多一次
  完整回答，这是真金白银；
- **fail-open**：critic 解析失败/网络失败直接结束反思，保留现有答复。质检是增益，
  不是依赖；
- critic 的 LLM 调用带 `config`，因此也会作为一条 `LLM_CALL` 落进四层审计 ——
  "打分的开销"本身是可观测的；
- 反馈消息带 `additional_kwargs={"km_reflection": n}`，审计和调试时能和用户真正说的话
  分开；`render_transcript` 里显示成 `[自检反馈]`；
- 反馈以会话消息的形式留下，后续轮次还能看到"上次被指出过什么"。

---

## 五、实验一：强/弱提示词对照（结论：失败，但值得记录）

设计意图：用 `AGENT_PROMPT_VARIANT=weak` 把系统提示词削弱，造一个真实会出错的基线，
再对比开反思后的通过率。

| 配置 | 通过率 | judge 均分 | 反思触发 |
| --- | --- | --- | --- |
| `default`（生产提示词） | 16/16 (100%) | 92.9 | 0 |
| `weak`（三个版本：少调工具 / 以用户说法为准 / 自信果断） | 15-16/16 | 92.4-95.6 | 0 |

`weak` 三版的措辞一次比一次激进，唯一一次失败（15/16）还是一条**断言词汇**问题：
答复写的是"没有查到"，而关键词组里只有"查不到"。这条已经修进数据集，它说明的是
断言太脆，不是 Agent 答错。

> 这张表是提示注入防护上线**之前**的数据。默认开启防护（`spotlight`）后，同一套
> 金标准仍是 16/16，judge 均分 93.3，代价是约 +19% 输入 token —— 见
> [`docs/agent-security.md`](agent-security.md) 的「无回归检查」。

**结论：提示词扰动的力度不够。** deepseek-chat 在提示词被削弱到"不必调工具"之后，
依然会按用户意图调用 `get_order_by_id`，答复也基本忠实。拿它当基线，只能得到
"反思没触发" —— 这不是反思没用，是**实验没有制造出可修复的缺陷**。

这个失败的对照保留在代码里（`WEAK_SYSTEM_PROMPT`），因为它划出了一个边界：
*同一个强模型 + 同一套工具，仅靠提示词很难造出稳定的、可归因的失败。* 要测反思，
必须直接测组件。

---

## 六、实验二：缺陷注入（defect injection）

### 方法

对每条用例：

1. 让 Agent 正常跑一遍，拿到真实轨迹（含工具返回）和正确答复；
2. **注入缺陷**：
   - *编造事实*：在答复末尾追加"物流显示预计明天下午 3 点前送达，快递单号
     SF1234567890" —— 后端只有订单表，从未返回过任何物流时间或运单号；
   - *答复截断*：只保留前 8 个字符，模拟 `max_tokens` / 超时截断；
3. 把"真实轨迹 + 被污染的答复"交给 critic，看它判不判不合格；
4. 判了不合格就走**完整的 `reflect_loop`**：同一个 Agent、同一套工具、带反馈重答；
5. 用确定性断言 + judge 检查重答是否恢复正确，并确认注入的假事实确实消失了。

同时，干净答复也交给 critic 看一遍 —— 这是误报率的分母。

### 结果（16 条用例，deepseek-chat）

| 指标 | 结果 |
| --- | --- |
| 干净答复 critic 通过 | **16/16**（误报 0） |
| 编造事实检出率 | **16/16 (100%)** |
| 编造事实修复率 | **16/16 (100%)** |
| 答复截断检出率 | **15/16 (94%)** |

唯一的漏检是 `unknown_order` 的截断：答复前 8 个字是"没有查到订单号"，恰好是一个
完整、正确的回答。这是注入设计的问题，不是 critic 的问题 —— 但报告里如实列出，
不把它抹掉。

"修复"的判定不是 critic 自己说了算：重答必须同时满足 ① 注入的假事实消失、
② 该用例的确定性断言全过、③ judge 不判不合格。

### 端到端代价：同一批用例开 / 不开反思

强基线（生产提示词，16 条用例）各跑一遍：

| 指标 | 不反思 | 开反思 | 变化 |
| --- | --- | --- | --- |
| 通过率 | 16/16 | 16/16 | 0 |
| judge 均分 | 92.9 | 92.9 | 0 |
| 质检轮次 | 0 | 16（全部通过） | +16 |
| 重答次数 | 0 | 0 | 0 |
| token 入 / 出 | 20699 / 1795 | 29996 / 2189 | +45% / +22% |
| 平均延迟 | 5089 ms | 5801 ms | +712 ms (+14%) |

结论很直接：**首轮全对时，反思只花钱、不改变结果**。这正是它默认关闭的理由 ——
在"准确率 > 成本"的场景由调用方按请求打开，而不是替所有调用方做决定。

- 干净路径：每条请求 +1 次 critic 调用（约 1 次 LLM 往返）；
- 不合格路径：再 +1 次完整 Agent 执行（工具调用可能重放，反馈里明确要求"不要重复
  参数完全相同的工具"）；
- critic 的调用会落成审计 `LLM_CALL`，成本可查 —— 上表的 token 增量就是它。

---

## 七、局限，写清楚

1. **样本量小**：16 条用例不足以做统计显著性结论。它是一组回归测试 + 可复现的
   演示，不是 benchmark。
2. **judge 与 critic 同模型**：存在自评偏差（self-preference）。确定性断言是下限，
   但"忠实"这类判断最终仍依赖模型。换模型或人工抽检是下一步。
3. **缺陷是人工构造的**：100% 召回只说明"能发现这一类编造"，不说明能覆盖所有失败
   模式。截断的漏检已经说明注入设计会直接影响指标。
4. **端到端评测是串行的**：十几条用例几分钟，没进 CI。要进 CI 得先解决真模型成本
   和审计落库的等待时间。

---

## 八、复现

前置：MySQL / Redis / RocketMQ / PostgreSQL 起着，`km-mock-order-service`(:8082)、
`km-mcp-server`(:8081)、`km-task-service`(:8083)、Agent 运行时(:8000) 全部启动，
仓库根目录 `.env` 里有 `LLM_API_KEY`。

```bash
cd km-agent-runtime

# 1. 端到端基线（数据源 = 四层审计轨迹）
uv run python -m evals.runner --out evals/results/baseline.json

# 2. 开反思再跑一遍，和基线对比
uv run python -m evals.runner --reflect --baseline evals/results/baseline.json \
    --out evals/results/with_reflection.json

# 3. 缺陷注入：critic 召回 / 误报 / 修复率
uv run python -m evals.reflection_check
```

报告写在 `evals/results/*.json`，每条用例都带原始答复、逐条断言、judge 理由、
工具序列和 token 统计。

单测（不碰网络）：

```bash
uv run pytest tests/test_evals.py tests/test_reflection.py
```

> 另有一组**提示注入**用例（`evals/injection.jsonl`，6 条）用同一套 runner 跑，
> 指标是攻击成功率而不是答复质量，单独写在
> [`docs/agent-security.md`](agent-security.md)。
