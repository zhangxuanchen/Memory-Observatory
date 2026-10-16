#!/usr/bin/env python3
"""M12 探针结果分析：确认 PreToolUse 是否真被 IDE 调用、喂进来的字段契约、拦截流水。

跑法：python3 read-captures.py
- 若捕获为空 → IDE 没触发 PreToolUse hook（没重启 / matcher 不认 / 该 IDE 无此事件）。
- 若有捕获但你在 IDE 里没看到拦截 → IDE 收到了 deny 却不执行 → 契约退化（退 T1 注入）。
"""
import collections
import json
import os

PROBE_DIR = os.path.expanduser("~/.tua-probe/guard")
CAPTURES = os.path.join(PROBE_DIR, "captures.jsonl")
DECISIONS = os.path.join(PROBE_DIR, "decisions.log")

print("== 捕获目录:", PROBE_DIR)
if not os.path.exists(CAPTURES):
    print("!! 还没有任何 PreToolUse 捕获。确认已重启 IDE 并触发过工具调用。")
else:
    key_freq = collections.Counter()
    tools = collections.Counter()
    events = collections.Counter()
    total = 0
    sample = None
    with open(CAPTURES, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            total += 1
            try:
                obj = json.loads(line)
            except Exception:
                key_freq["<非法 JSON>"] += 1
                continue
            if isinstance(obj, dict):
                for k in obj:
                    key_freq[k] += 1
                tools[str(obj.get("tool_name") or "?")] += 1
                events[str(obj.get("hook_event_name") or obj.get("event_name") or "?")] += 1
                if sample is None:
                    sample = obj
    print("捕获条数:", total)
    print("hook_event_name 分布:", dict(events))
    print("顶层字段出现次数（核对 M13 契约：需要 session_id/tool_name/tool_input/transcript_path）:")
    for k, c in key_freq.most_common():
        print("   %-24s %d" % (k, c))
    print("tool_name 分布:", dict(tools.most_common(12)))
    if sample is not None:
        print("首条样例（截断）:")
        print("  ", json.dumps(sample, ensure_ascii=False)[:600])

if os.path.exists(DECISIONS):
    print("\n== 干预流水（最后 15 行）==")
    with open(DECISIONS, encoding="utf-8") as handle:
        rows = [r.rstrip() for r in handle if r.strip()]
    for r in rows[-15:]:
        print("  ", r)
    deny = sum(1 for r in rows if "deny(首次)" in r)
    print("实际下发过 deny 次数:", deny, "（>0 说明探针确实拦过一次，请在 IDE 端确认是否真被挡住）")
