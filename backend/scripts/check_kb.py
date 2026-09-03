# -*- coding: utf-8 -*-
"""knowledge.json 校验脚本：JSON 合法性、重复键、科目/条目统计。

用法（在 backend/ 目录下）：python scripts/check_kb.py
"""
import io
import json
import re
import sys
from collections import Counter
from pathlib import Path

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

KB_PATH = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "knowledge.json"

raw = open(KB_PATH, encoding="utf-8").read()
try:
    data = json.loads(raw)
except json.JSONDecodeError as e:
    print("JSON 解析失败:", e)
    sys.exit(1)

print("JSON 合法 | 科目数:", len(data), "| 总条数:", sum(len(v) for v in data.values()))

keys = re.findall(r'^  "([^"]+)": \{', raw, re.M)
dups = {k: c for k, c in Counter(keys).items() if c > 1}
print("重复科目键:", dups if dups else "无")

topic_keys = re.findall(r'^    "([^"]+)": "【', raw, re.M)
tdups = {k: c for k, c in Counter(topic_keys).items() if c > 1}
print("跨科目重复主题名:", tdups if tdups else "无")

for s, v in data.items():
    print(f"  {s}: {len(v)} 条")
