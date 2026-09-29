"""pytest 全局配置。

Windows 控制台默认是 GBK 编码, 带 emoji 或特殊符号的输出会直接抛
UnicodeEncodeError, 让一个本来通过的测试显示成 FAILED —— 极容易误导。
这里统一把 stdout/stderr 切成 UTF-8。

注意: 这是【测试环境】的修复。业务代码 (app/main.py) 里有同样的处理,
但那是给 uvicorn 用的, 两者互不依赖。
"""

import sys

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")
