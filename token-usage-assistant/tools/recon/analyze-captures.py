#!/usr/bin/env python3
"""分析 hook 探针捕获：判断原始 stdin 里是否携带 token / 积分 / prompt。

用法：python3 analyze-captures.py
读取 ~/.tua-probe/captures/*.json（由 dump-hook.sh 落盘），汇总：
  1) 每个事件里出现过哪些候选字段（token / usage / credit / cost / prompt ...）
  2) 各事件的顶层键集合（看契约全貌）
  3) 命中字段的实际值（判断是不是真读数、还是 0 / 缺失）
"""
import json
import re
import sys
from pathlib import Path

CAP = Path.home() / ".tua-probe" / "captures"
PAT = re.compile(r"token|usage|credit|cost|billable|prompt|context_usage|price|quota|consume", re.I)


def walk(o, path=""):
    if isinstance(o, dict):
        for k, v in o.items():
            p = f"{path}.{k}" if path else str(k)
            yield p, v
            yield from walk(v, p)
    elif isinstance(o, list):
        for i, v in enumerate(o[:3]):
            yield from walk(v, f"{path}[{i}]")


def main():
    files = sorted(CAP.glob("*.json"))
    if not files:
        print(f"还没有捕获。确认：1) 已跑 install-probe.sh；2) 已重启 IDE；3) 用过几轮。")
        print(f"捕获目录：{CAP}")
        return
    print(f"捕获文件数：{len(files)}")
    for f in files:
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            print(f"\n=== {f.name} ===\n  [跳过] 非法 JSON / 空文件")
            continue
        ev = "?"
        if isinstance(data, dict):
            ev = data.get("hook_event_name") or data.get("event") or data.get("hookEventName") or "?"
        print(f"\n=== {f.name}（事件：{ev}）===")
        if not isinstance(data, dict):
            print(f"  顶层不是对象：{type(data)}")
            continue
        print(f"  顶层键：{sorted(data.keys())}")
        hits = [(p, v) for p, v in walk(data) if PAT.search(p)]
        if hits:
            print("  ⚠ 疑似 token / 积分 / prompt 字段：")
            for p, v in hits:
                print(f"    {p} = {str(v)[:140]}")
        else:
            print("  未命中任何 token / 积分 / prompt 相关键")


if __name__ == "__main__":
    sys.exit(main())