#!/usr/bin/env python3
"""回收 M14 探针捕获：分析 UserPromptSubmit 的字段契约 + 干预流水。

- captures.jsonl：每条 UserPromptSubmit 原始 stdin（IDE 到底喂了哪些字段）。
- decisions.log：探针每次的 mode/结果（看拦截/注入是否被触发、几号 fired）。
"""
from __future__ import annotations

import json
import os
from collections import Counter

PROBE_DIR = os.path.expanduser("~/.tua-probe/prompt")
CAPTURES = os.path.join(PROBE_DIR, "captures.jsonl")
DECISIONS = os.path.join(PROBE_DIR, "decisions.log")

# M14 关心的关键字段：prompt 原文、会话定位、轮次键、工作区。
KEYS_OF_INTEREST = [
    "session_id", "prompt", "request_set_id", "transcript_path",
    "cwd", "hook_event_name", "model", "workspace_roots",
]


def load_jsonl(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except Exception:
                rows.append({"__parse_error__": line[:120]})
    return rows


def main():
    rows = load_jsonl(CAPTURES)
    print("== 捕获条数：%d ==" % len(rows))
    if not rows:
        print("（还没有捕获。装好探针、重启 IDE、发过 prompt 后再跑本脚本。）")
    else:
        all_keys = Counter()
        for r in rows:
            for k in r.keys():
                all_keys[k] += 1
        print("\n== 字段覆盖率（字段: 出现次数/%d）==" % len(rows))
        for k, c in all_keys.most_common():
            mark = "  ← M14 关注" if k in KEYS_OF_INTEREST else ""
            print("  %-20s %d/%d%s" % (k, c, len(rows), mark))

        # 样例：看 prompt 是否真带原文、request_set_id 是否下发。
        sample = next((r for r in rows if isinstance(r.get("prompt"), str) and r.get("prompt")), rows[0])
        print("\n== 样例一条（截断）==")
        for k, v in sample.items():
            sv = str(v)
            print("  %s = %s" % (k, sv[:80] + "…" if len(sv) > 80 else sv))

    if os.path.exists(DECISIONS):
        print("\n== 干预流水（末 10 行）==")
        with open(DECISIONS, encoding="utf-8") as handle:
            for line in handle.read().splitlines()[-10:]:
                print("  " + line)

    fired = os.path.join(PROBE_DIR, ".fired")
    print("\n一次性闸门 .fired：%s" % ("已触发（reject 已拦过一次）" if os.path.exists(fired) else "未触发"))


if __name__ == "__main__":
    main()
