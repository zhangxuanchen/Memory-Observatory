#!/usr/bin/env python3
"""M13 验收：构造「进行中 turn」样本，验证护航对原地打转弹出 ask。

对真实库操作前后用独立 session_id 隔离，跑完删测试数据。
依赖：App 已启动、config.guardOn=true、本机 /api/v1/events + /api/v1/guard。
"""
import json
import sys
import time
import urllib.request

CFG = json.load(open(sys.argv[1]))
PORT = CFG["port"]
KEY = CFG["apiKey"]
BASE = "http://127.0.0.1:%d" % PORT
SID = "tua-m13-selftest-%d" % int(time.time())
ROOT = "/tmp/tua-m13-test"
NOW = int(time.time() * 1000)


def post(path, payload):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY},
    )
    with urllib.request.urlopen(req, timeout=5) as resp:
        return json.loads(resp.read().decode("utf-8") or "{}")


def event(name, **extra):
    item = {"ts": extra.pop("ts", NOW), "ide": "qoder", "session_id": SID,
            "event_name": name, "workspace_root": ROOT}
    item.update(extra)
    return item


# 1) 开轮 + 15 条同工具动作（阈值 loopRepeatN=15 → 应命中 ask）
events = [event("UserPromptSubmit", prompt="M13 自测：原地打转", ts=NOW)]
for i in range(15):
    events.append(event("PostToolUse", ts=NOW + i + 1, tool_name="Read",
                        io_kind="file_read", status="ok"))
r = post("/api/v1/events", {"events": events})
print("灌入 %d 条 → %s" % (len(events), r))

# 2) 首次护航查询：期望 ask(loop_thrash_inflight)
g = post("/api/v1/guard", {"session_id": SID, "tool_name": "Read",
                           "io_kind": "file_read", "ide": "qoder"})
print("首次 guard →", json.dumps(g, ensure_ascii=False))

# 3) 二次同轮查询：同规则只问一次 → 期望 allow
g2 = post("/api/v1/guard", {"session_id": SID, "tool_name": "Read",
                            "io_kind": "file_read", "ide": "qoder"})
print("二次 guard →", json.dumps(g2, ensure_ascii=False))

ok = (g.get("decision") == "ask" and g.get("rule") == "loop_thrash_inflight"
      and g2.get("decision") == "allow")
print("RESULT", "PASS" if ok else "FAIL", "session=" + SID)
