"""Agent 轨迹评测 —— 让"Agent 有没有变好"变成数字, 而不是感觉。

模块划分刻意简单:

* ``dataset.jsonl`` —— 金标准用例 (输入 + 期望 + 评分说明);
* ``scorers.py``   —— 确定性断言 + LLM-as-judge, 纯函数, 不碰网络以外的东西;
* ``runner.py``    —— 跑用例、拉四层轨迹、出报告。

用法见 ``docs/agent-evals.md``。
"""
