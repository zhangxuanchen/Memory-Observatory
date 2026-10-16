#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为异常详情弹窗「模拟」其余几类异常：直接往 SQLite 灌合成 turns + events。

覆盖 4 类（loop_thrash / slow_call 已有真实数据）：
  - credit_spike    太费钱
  - frequent_compact 反复压缩
  - retry_storm     反复失败
  - expire_storm    突然失忆

灌完重启 App 即可：AppState 启动会跑 runDetection(days:7)，检测器据这些 events
产出 findings，弹窗里就能对这 4 类逐轮做 AI 分析。

幂等：每次先删旧的 sim:* 行再插。仅用标准库 sqlite3。
用法：python3 tools/seed_anomalies.py
"""
import os
import sqlite3
import time

DB = os.path.expanduser("~/Library/Application Support/TokenUsageAssistant/tua.sqlite")
WS_ID = "ws_sim_demo"
WS_NAME = "sim-demo"
IDE = "qoder"
MODEL = "deepseek-v4-1-flash-260910"

now_ms = int(time.time() * 1000)


def ts(minutes_ago: int) -> int:
    return now_ms - minutes_ago * 60_000


EVENT_COLS = (
    "event_uid, ts, ide, model, workspace_id, session_id, turn_id, event_name, "
    "io_kind, tool_name, action_idx, credits, token_in, token_out, token_cache, "
    "token_source, latency_ms, status, payload, detail"
)


def ev(uid, t, name, *, io=None, tool=None, idx=0, credits=0.0,
       lat=0, status="ok", detail=None):
    return (
        uid, t, IDE, MODEL, WS_ID, f"sim:{name}", None, name,
        io, tool, idx, credits, 0, 0, 0, "none", lat, status, "{}", detail,
    )


def build_turns():
    """返回 [(turn_row, [event_row, ...]), ...]。turn_id 由 events 回填。"""
    turns = []

    # ① credit_spike：单轮积分远超基线（>5 绝对阈值、>P95）。
    tid = "sim:credit#1"
    events = [
        ev("sim-credit-submit", ts(9), "UserPromptSubmit",
           detail="提问: 把整个解析模块重构一遍并补全单元测试"),
        ev("sim-credit-e1", ts(9), "PostToolUse", io="file_write", tool="Edit",
           idx=1, credits=6.0, lat=3000, detail="Edit: 重写 Core/Parser.swift 主流程"),
        ev("sim-credit-e2", ts(8), "PostToolUse", io="file_write", tool="Write",
           idx=2, credits=7.0, lat=4000, detail="Write: 新增 ParserRoundTripTest.swift"),
        ev("sim-credit-stop", ts(8), "Stop"),
    ]
    turns.append((tid, 14.0, 9000, 3, ts(9), ts(8),
                  "把整个解析模块重构一遍并补全单元测试", events))

    # ② frequent_compact：同轮压缩类事件 > 3 次。
    tid = "sim:compact#1"
    events = [ev("sim-compact-submit", ts(7), "UserPromptSubmit", detail="提问: 接着上次继续")]
    for i in range(5):
        events.append(ev(f"sim-compact-{i}", ts(7) - i * 4000, "PostToolUse",
                         io="memory_read", tool="CompactContext", idx=i + 1,
                         credits=0.1, lat=500,
                         detail="CompactContext: 压缩历史对话为摘要"))
    events.append(ev("sim-compact-stop", ts(6), "Stop"))
    turns.append((tid, 1.2, 20000, 6, ts(7), ts(6), "接着上次继续", events))

    # ③ retry_storm：同工具连续失败 >= 3 次。
    tid = "sim:retry#1"
    events = [ev("sim-retry-submit", ts(5), "UserPromptSubmit", detail="提问: 跑一下全量测试")]
    for i in range(4):
        events.append(ev(f"sim-retry-f{i}", ts(5) - i * 6000, "PostToolUse",
                         io="tool_other", tool="Bash", idx=i + 1,
                         credits=0.2, lat=2500, status="failed",
                         detail="Bash: mvn -o test（编译失败：找不到符号 ConfigLoader）"))
    events.append(ev("sim-retry-ok", ts(4), "PostToolUse", io="tool_other", tool="Bash",
                     idx=5, credits=0.2, lat=2500, detail="Bash: mvn -o test（仍失败）"))
    events.append(ev("sim-retry-stop", ts(4), "Stop"))
    turns.append((tid, 2.0, 30000, 5, ts(5), ts(4), "跑一下全量测试", events))

    # ④ expire_storm：60s 窗口内过期事件 > 20 条（非动作事件，避免误判 loop）。
    tid = "sim:expire#1"
    base = ts(3)
    events = [ev("sim-expire-submit", base, "UserPromptSubmit",
                 detail="提问: 刚才定的命名约束还记得吗")]
    for i in range(25):
        events.append(ev(f"sim-expire-{i}", base + i * 1000, "MemoryExpire",
                         io="memory_read", tool="MemoryExpire", idx=0,
                         detail="MemoryExpire: 短期上下文条目批量过期"))
    events.append(ev("sim-expire-stop", base + 30_000, "Stop"))
    turns.append((tid, 0.5, 5000, 0, base, base + 30_000,
                  "刚才定的命名约束还记得吗", events))

    return turns


def main():
    if not os.path.exists(DB):
        raise SystemExit(f"找不到数据库：{DB}")
    con = sqlite3.connect(DB)
    cur = con.cursor()

    # 清旧 sim 数据（幂等）。
    cur.execute("DELETE FROM events WHERE turn_id LIKE 'sim:%' OR event_uid LIKE 'sim-%'")
    cur.execute("DELETE FROM turns WHERE turn_id LIKE 'sim:%'")
    cur.execute("DELETE FROM findings WHERE ref_id LIKE 'sim:%'")

    # 演示工作区。
    cur.execute(
        "INSERT OR REPLACE INTO workspaces (id, root_path, name, ide, source) "
        "VALUES (?,?,?,?,?)",
        (WS_ID, "/tmp/sim-demo", WS_NAME, IDE, "seed"),
    )

    event_sql = f"INSERT OR REPLACE INTO events ({EVENT_COLS}) VALUES ({','.join('?' * 20)})"
    turn_sql = (
        "INSERT OR REPLACE INTO turns (turn_id, workspace_id, session_id, ide, model, "
        "user_prompt, started_at, ended_at, action_count, credits_total, token_total, "
        "token_in, token_out, token_cache, latency_total_ms, latency_max_ms, outcome, flags) "
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
    )

    for tid, credits, lat_max, action_count, started, ended, prompt, events in build_turns():
        for row in events:
            # 回填 turn_id（第 7 个字段，索引 6）。
            r = list(row)
            r[6] = tid
            cur.execute(event_sql, r)
        cur.execute(turn_sql, (
            tid, WS_ID, f"sim:{tid}", IDE, MODEL, prompt, started, ended,
            action_count, credits, 0, 0, 0, 0, lat_max, lat_max, "ended", None,
        ))

    con.commit()
    n_ev = cur.execute("SELECT COUNT(*) FROM events WHERE turn_id LIKE 'sim:%'").fetchone()[0]
    n_turn = cur.execute("SELECT COUNT(*) FROM turns WHERE turn_id LIKE 'sim:%'").fetchone()[0]
    con.close()
    print(f"已灌入 sim 数据：{n_turn} 轮 / {n_ev} 条事件 → 重启 App 后自动检出 4 类异常")


if __name__ == "__main__":
    main()
