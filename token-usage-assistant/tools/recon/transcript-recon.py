#!/usr/bin/env python3
# M9 回灌器数据侦察：对 ~/.qoder-cn/projects 下全部 Qoder transcript 做结构量化，
# 目的——把 TranscriptBackfill 的字段映射从「照抄方案」变成「实测锁定」。
# 只读，不改任何文件。用法：python3 transcript-recon.py [最多扫描文件数]
import json, os, sys, glob, collections, re

HOME = os.path.expanduser("~")
BASE = os.path.join(HOME, ".qoder-cn", "projects")
LIMIT = int(sys.argv[1]) if len(sys.argv) > 1 else 200

files = sorted(glob.glob(os.path.join(BASE, "**", "*.jsonl"), recursive=True))
# 只看 transcript 目录下的会话流（工具产物/其它 jsonl 不算）
tr_files = [f for f in files if os.sep + "transcript" + os.sep in f]
if not tr_files:
    tr_files = files
tr_files = tr_files[:LIMIT]

print(f"== jsonl 总数={len(files)}  transcript 命中={len(tr_files)}  本次扫描={len(tr_files)} ==")
# 路径布局样本（看 sessionId / workspace 能否从路径推）
print("== 路径样本(前5) ==")
for f in tr_files[:5]:
    print("  ", f.replace(BASE, "~/.qoder-cn/projects"))

type_counter = collections.Counter()
top_keys = collections.Counter()                 # 每条记录顶层键分布
user_keys = collections.Counter()                # user 记录顶层键
asst_keys = collections.Counter()                # assistant 记录顶层键
user_content_kinds = collections.Counter()       # user.message.content 块类型
asst_content_kinds = collections.Counter()       # assistant.message.content 块类型
tool_names = collections.Counter()               # tool_use.name 分布
reqset_present = 0; reqset_absent = 0             # user 记录是否带 requestSetId
promptid_present = 0
humaninp_present = 0
cwd_present = 0; gitbranch_present = 0
usage_records = 0                                # 带 usage 的 assistant 记录数
usage_credits = 0                                # usage 里真有 credits 的
context_ratio_present = 0
ts_present = 0                                   # 顶层有 timestamp 的记录
ts_samples = []
session_field_keys = collections.Counter()       # 疑似会话 id 的字段名
sample_user = None; sample_asst_tooluse = None; sample_usage = None
total_records = 0

def kinds(content):
    if isinstance(content, list):
        return [b.get("type") if isinstance(b, dict) else type(b).__name__ for b in content]
    if isinstance(content, str):
        return ["<str>"]
    return ["?"]

for f in tr_files:
    with open(f, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line: continue
            try: d = json.loads(line)
            except Exception: continue
            if not isinstance(d, dict): continue
            total_records += 1
            t = d.get("type") or d.get("role") or "?"
            type_counter[t] += 1
            for k in d.keys(): top_keys[k] += 1
            if d.get("timestamp"):
                ts_present += 1
                if len(ts_samples) < 5: ts_samples.append(d.get("timestamp"))
            # 猜会话字段
            for k in d.keys():
                if re.search(r"(session|conversation|chat).*id$|^sessionId$|^session_id$", k, re.I):
                    session_field_keys[k] += 1
            msg = d.get("message") if isinstance(d.get("message"), dict) else None
            if t == "user":
                for k in d.keys(): user_keys[k] += 1
                if "requestSetId" in d or (msg and "requestSetId" in msg): reqset_present += 1
                else: reqset_absent += 1
                if "promptId" in d: promptid_present += 1
                if "humanInput" in d: humaninp_present += 1
                if "cwd" in d: cwd_present += 1
                if "gitBranch" in d: gitbranch_present += 1
                if msg and "content" in msg:
                    for kk in kinds(msg.get("content")): user_content_kinds[kk] += 1
                if sample_user is None: sample_user = d
            elif t == "assistant":
                for k in d.keys(): asst_keys[k] += 1
                if msg:
                    if "content" in msg:
                        for b in (msg.get("content") or []):
                            if isinstance(b, dict) and b.get("type") == "tool_use":
                                asst_content_kinds["tool_use"] += 1
                                tool_names[b.get("name") or "?"] += 1
                                if sample_asst_tooluse is None: sample_asst_tooluse = b
                            elif isinstance(b, dict):
                                asst_content_kinds[b.get("type") or "?"] += 1
                            else:
                                asst_content_kinds[type(b).__name__] += 1
                    if isinstance(msg.get("usage"), dict):
                        usage_records += 1
                        u = msg["usage"]
                        if any(x in u for x in ("credits","original_credits")): usage_credits += 1
                        if "context_usage_ratio" in u: context_ratio_present += 1
                        if sample_usage is None: sample_usage = u

print(f"\n== 总记录数={total_records} ==")
print("type 分布:", dict(type_counter))
print("\n== 顶层键分布(出现率) ==")
for k, c in top_keys.most_common(30):
    print(f"  {k:28} {c:7}  {100*c/total_records:5.1f}%")

us = type_counter.get("user", 0)
print(f"\n== 轮次切分信号(user 记录 n={us}) ==")
print(f"  带 requestSetId: {reqset_present}  缺失: {reqset_absent}")
print(f"  带 promptId:     {promptid_present}  带 humanInput: {humaninp_present}")
print(f"  带 cwd:          {cwd_present}  带 gitBranch: {gitbranch_present}")
print("  user.content 块类型:", dict(user_content_kinds))
print("  疑似会话字段:", dict(session_field_keys))

ast = type_counter.get("assistant", 0)
print(f"\n== 动作重建(assistant 记录 n={ast}) ==")
print("  assistant.content 块类型:", dict(asst_content_kinds))
print("  tool_use.name 分布:", dict(tool_names.most_common(20)))

print(f"\n== credits 覆盖(诚实边界) ==")
print(f"  带 usage 的 assistant 记录: {usage_records} / {ast} = {100*usage_records/max(ast,1):.2f}%")
print(f"  其中真有 credits: {usage_credits}  context_usage_ratio: {context_ratio_present}")
print(f"  占全部记录比例: {100*usage_records/max(total_records,1):.3f}%")
print(f"  有顶层 timestamp: {ts_present} / {total_records} = {100*ts_present/max(total_records,1):.1f}%  样本: {ts_samples}")

def brief(d, n=1200):
    s = json.dumps(d, ensure_ascii=False)
    return s[:n] + ("…" if len(s) > n else "")

print("\n== SAMPLE: user 记录 ==");  print(brief(sample_user) if sample_user else "(none)")
print("\n== SAMPLE: assistant tool_use 块 =="); print(brief(sample_asst_tooluse) if sample_asst_tooluse else "(none)")
print("\n== SAMPLE: usage 对象 =="); print(brief(sample_usage) if sample_usage else "(none)")
