#!/usr/bin/env python3
"""Token 用量助手 · IDE hook 适配器（纯标准库，零依赖）。

职责：把 IDE hook 经 stdin 喂进来的原始事件归一化成 TUA 事件契约，
POST 到本机接收端 ``http://127.0.0.1:<port>/api/v1/events``。

纪律（与 memory-observatory 的 mo-report 一致）：
  - stdout **恒**输出 ``{}``（IDE 在 exit 0 时把 stdout 当 HookOutput 解析）；
  - 一切诊断走 stderr；
  - 任何异常都吞掉并 ``exit 0``，绝不干扰 IDE。

计量口径（真机实测）：
  - hook 原始 stdin **不含**任何 token / 积分字段；
  - Qoder：hook 带 ``transcript_path``，真实积分在 transcript 的
    ``assistant.message.usage.credits``（逐条累加），prompt 原文在 ``user.message``。

因此本适配器**不写任何本地状态文件**，turn 切分交由 App 侧完成。

用法（由 tua-report.sh 包装脚本调用）：
    python3 tua_report.py --platform qoder            # 旁路上报（现状）
    python3 tua_report.py --platform qoder --guard    # 事中护航（M13，PreToolUse）：
        同步 POST /api/v1/guard（<2s），把 App 决策翻成 hookSpecificOutput.permissionDecision；
        超时 / App 不可达 / 任何异常 → 输出 {} 放行（fail-open，护航绝不弄坏 IDE）。
    python3 tua_report.py --platform qoder --precheck  # 事前护航（M14，UserPromptSubmit）：
        同步 POST /api/v1/precheck（<2s）；App 判就绪度低 → block，本脚本写 stderr 并 exit 2
        拦下该条 prompt（IDE 展示 stderr 给用户）；pass / 异常 → {} 放行。
环境变量：
    TUA_ENDPOINT   上报地址（默认 http://127.0.0.1:48732/api/v1/events）
    TUA_GUARD_ENDPOINT 护航查询地址（缺省由 TUA_ENDPOINT 换路径得到）
    TUA_PRECHECK_ENDPOINT 事前护航查询地址（缺省由 TUA_ENDPOINT 换路径得到）
    TUA_API_KEY    Bearer 密钥（必需，否则 401）
    TUA_DRY_RUN    置 1 时只解析、不上报也不查询（探针干跑）
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.request

DEFAULT_ENDPOINT = "http://127.0.0.1:48732/api/v1/events"
HTTP_TIMEOUT = 3.0
GUARD_TIMEOUT = 2.0   # 护航同步预算：超过即放行（策划 §3.5：hook 超时拖慢 IDE 是高危风险）

_emitted = {"v": False}


def emit(text: str) -> None:
    """唯一 stdout 出口：写过什么就写什么；没写则由 __main__ 兜底补 {}。"""
    _emitted["v"] = True
    sys.stdout.write(text)

# 工具名 → io_kind（认不出落 tool_other，不猜）。覆盖 Qoder 原生名与
# Claude Code 兼容名两套。
TOOL_IO_KIND = {
    "read": "file_read", "read_file": "file_read", "notebookread": "file_read",
    "write": "file_write", "create_file": "file_write", "edit": "file_write",
    "edit_file": "file_write", "multiedit": "file_write", "notebookedit": "file_write",
    "grep": "content_search", "grep_code": "content_search",
    "glob": "content_search", "search_file": "content_search",
    "search_codebase": "content_search", "codebase_search": "content_search",
    "readmemory": "memory_read", "writememory": "memory_write",
    "mcp__memory__read": "memory_read", "mcp__memory__write": "memory_write",
}

LIFECYCLE_EVENTS = {"SessionStart", "SessionEnd", "Stop", "UserPromptSubmit"}


def log(message: str) -> None:
    sys.stderr.write("[tua-hook] " + message + "\n")


def now_millis() -> int:
    return int(time.time() * 1000)


def as_dict(value) -> dict:
    return value if isinstance(value, dict) else {}


def tool_io_kind(tool_name: str) -> str:
    return TOOL_IO_KIND.get((tool_name or "").strip().lower(), "tool_other")


def tool_status(tool_response) -> str:
    """从 tool_response 多形态推断成功 / 失败（推断口径，非读数）。"""
    if isinstance(tool_response, dict):
        if tool_response.get("is_error") is True or tool_response.get("error"):
            return "failed"
        exit_code = tool_response.get("exit_code")
        if isinstance(exit_code, int) and exit_code != 0:
            return "failed"
        status = str(tool_response.get("status") or "").lower()
        if status in ("error", "failed", "failure"):
            return "failed"
    return "ok"


def short(text, limit: int = 120) -> str:
    """截断成一行（保留开头，命令/路径首部最有信息量）。"""
    text = str(text or "")
    return text if len(text) <= limit else text[: limit - 1] + "…"


def summarize_tool(tool_name: str, tool_input) -> str:
    """把工具调用压成一行人类可读详情（对齐 memory-observatory 的 summarize_tool）。

    先按常见参数键取第一个有意义的值，取不到再退化成整段 JSON。
    """
    name = tool_name or "tool"
    if isinstance(tool_input, dict):
        for k in ("command", "file_path", "path", "pattern", "query", "url", "description"):
            v = tool_input.get(k)
            if isinstance(v, str) and v.strip():
                return "%s: %s" % (name, short(v))
        return "%s: %s" % (name, short(json.dumps(tool_input, ensure_ascii=False)))
    if isinstance(tool_input, str) and tool_input.strip():
        return "%s: %s" % (name, short(tool_input))
    return name


# ----------------------------------------------------------------------
# Qoder：读 transcript 取真实积分
# ----------------------------------------------------------------------

def read_transcript_credits(path: str) -> dict:
    """读 transcript 的**最后一段**（最后一个 user 消息之后的所有 assistant 消息）。

    返回 ``{"credits": 累计, "request_set_id": 轮次键, "prompt": 提问原文,
    "model": 模型, "ts": 末条时间戳}``；读不到则给空结果（不猜、不兜底）。
    """
    out = {"credits": 0.0, "request_set_id": "", "prompt": "", "model": "", "ts": 0}
    if not path or not os.path.isfile(path):
        return out
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                except Exception:
                    continue
                if not isinstance(record, dict):
                    continue
                kind = record.get("type")
                if kind == "user":
                    # 新一段开始：重置累计
                    out["credits"] = 0.0
                    out["request_set_id"] = str(
                        record.get("requestSetId") or record.get("request_set_id") or ""
                    )
                    message = as_dict(record.get("message"))
                    out["prompt"] = extract_text(message.get("content"))
                elif kind == "assistant":
                    message = as_dict(record.get("message"))
                    usage = as_dict(message.get("usage"))
                    credits = usage.get("credits")
                    if isinstance(credits, (int, float)):
                        out["credits"] += float(credits)
                    if message.get("model"):
                        out["model"] = str(message.get("model"))
                    if record.get("timestamp"):
                        out["ts"] = parse_ts(record.get("timestamp"))
    except Exception as error:  # pragma: no cover - 旁路不干扰
        log("transcript 解析异常（忽略）: " + str(error))
    return out


def extract_text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, dict) and item.get("type") == "text":
                parts.append(str(item.get("text") or ""))
        return "\n".join(parts)
    return ""


def extract_thinking(path: str) -> list:
    """从 transcript **末段**（最后一次真实提问之后）抽 thinking 块。

    Qoder hook 不发思考事件，只能反推：thinking 块自身无时长字段，
    用「本条记录时间戳 − 上一条记录时间戳」估算，与 UI「深度思考 · Ns」同口径。
    真实提问是 content 为字符串的 user 记录（tool_result 也是 user，需区分）。
    """
    out = []
    try:
        records = []
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                except Exception:
                    continue
                if isinstance(record, dict) and record.get("type") in ("user", "assistant"):
                    records.append(record)
        start = -1
        for i in range(len(records) - 1, -1, -1):
            message = as_dict(records[i].get("message"))
            if records[i].get("type") == "user" and isinstance(message.get("content"), str):
                start = i
                break
        if start < 0:
            return out
        prev_ts = parse_ts(records[start].get("timestamp"))
        for record in records[start + 1:]:
            ts = parse_ts(record.get("timestamp"))
            content = as_dict(record.get("message")).get("content")
            if isinstance(content, list):
                for block in content:
                    if not isinstance(block, dict) or block.get("type") != "thinking":
                        continue
                    text = " ".join(str(block.get("thinking") or "").split())
                    if not text:
                        continue
                    duration = max(ts - prev_ts, 0) if ts and prev_ts else 0
                    out.append({"ts": ts, "duration_ms": duration, "snippet": short(text, 80)})
            if ts:
                prev_ts = ts
    except Exception as error:  # pragma: no cover - 旁路不干扰
        log("thinking 解析异常（忽略）: " + str(error))
    return out


def thinking_events(base: dict, path: str) -> list:
    """把末段 thinking 块合成 Thinking 事件（同轮重发靠 ts 参与幂等键去重）。"""
    items = []
    for thinking in extract_thinking(path):
        item = dict(base)
        if thinking["ts"]:
            item["ts"] = thinking["ts"]
        item["event_name"] = "Thinking"
        item["tool_name"] = "Thinking"
        item["io_kind"] = "thinking"
        item["status"] = "ok"
        if thinking["duration_ms"]:
            item["latency_ms"] = thinking["duration_ms"]
        seconds = thinking["duration_ms"] / 1000.0
        shown = str(round(seconds)) if seconds >= 1 else "%.1f" % seconds
        item["detail"] = "深度思考 %ss：%s" % (shown, thinking["snippet"])
        items.append(item)
    return items


def parse_ts(value) -> int:
    """时间戳兼容：ISO8601 字符串 / epoch 秒 / epoch 毫秒。"""
    if isinstance(value, (int, float)):
        number = float(value)
        return int(number * 1000) if number < 1e12 else int(number)
    if isinstance(value, str):
        text = value.replace("Z", "+00:00")
        try:
            import datetime

            return int(datetime.datetime.fromisoformat(text).timestamp() * 1000)
        except Exception:
            return 0
    return 0


# ----------------------------------------------------------------------
# 事件归一化
# ----------------------------------------------------------------------

def build_events(platform: str, event: dict) -> list:
    name = str(event.get("hook_event_name") or event.get("event_name") or "").strip()
    if not name:
        return []

    session_id = str(event.get("session_id") or "").strip()
    model = str(event.get("model") or "").strip()
    cwd = str(event.get("cwd") or "").strip()
    base = {
        "ts": parse_ts(event.get("timestamp")) or now_millis(),
        "ide": platform,
        "session_id": session_id,
        "event_name": name,
    }
    if model:
        base["model"] = model
    if cwd:
        base["workspace_root"] = cwd
    roots = event.get("workspace_roots")
    if not cwd and isinstance(roots, list) and roots:
        base["workspace_root"] = str(roots[0])

    # 轮次键（Qoder 有 request_set_id）仅用于诊断留痕，**不**决定 turn 切分：
    # turn 统一由 App 侧按「UserPromptSubmit 边界」切分，避免各事件字段不一导致串号。
    if name == "UserPromptSubmit":
        prompt = extract_text(event.get("prompt"))
        item = dict(base)
        item["prompt"] = prompt
        item["detail"] = "提问: " + short(prompt, 200) if prompt.strip() else name
        return [item]

    if name in ("PostToolUse", "PostToolUseFailure"):
        tool_name = str(event.get("tool_name") or "tool")
        item = dict(base)
        item["tool_name"] = tool_name
        item["io_kind"] = tool_io_kind(tool_name)
        status = "failed" if name == "PostToolUseFailure" else tool_status(event.get("tool_response"))
        item["status"] = status
        detail = summarize_tool(tool_name, event.get("tool_input"))
        item["detail"] = detail + "（失败）" if status == "failed" else detail
        return [item]

    if name == "Stop":
        item = dict(base)
        item["detail"] = "Turn 结束"
        thinking_items = []
        if platform == "qoder":
            transcript = str(event.get("transcript_path") or "")
            summary = read_transcript_credits(transcript)
            if summary["model"] and not model:
                item["model"] = summary["model"]
            if summary["prompt"]:
                item["prompt"] = summary["prompt"]
            item["credits"] = round(summary["credits"], 6)
            item["token_source"] = "measured"
            if summary["request_set_id"]:
                item["turn_hint"] = summary["request_set_id"]
            thinking_items = thinking_events(base, transcript)
        return thinking_items + [item]

    # SessionStart / SessionEnd / 其它：仅记生命周期
    item = dict(base)
    item["io_kind"] = "lifecycle"
    return [item]


# ----------------------------------------------------------------------
# 护航（M13）：PreToolUse 同步探询，决策回传给 IDE
# ----------------------------------------------------------------------

def guard_endpoint() -> str:
    explicit = os.environ.get("TUA_GUARD_ENDPOINT") or ""
    if explicit:
        return explicit
    base = os.environ.get("TUA_ENDPOINT") or DEFAULT_ENDPOINT
    return base.replace("/api/v1/events", "/api/v1/guard")


def query_guard(url: str, api_key: str, payload: dict) -> tuple:
    """同步查护航决策，返回 (decision, reason)。任何异常 → ('allow','')（fail-open）。"""
    try:
        request = urllib.request.Request(
            url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + api_key},
        )
        with urllib.request.urlopen(request, timeout=GUARD_TIMEOUT) as response:
            body = json.loads(response.read().decode("utf-8") or "{}")
        decision = str(body.get("decision") or "allow")
        if decision in ("ask", "deny"):
            return decision, str(body.get("reason") or "TUA 护航提醒")
        return "allow", ""
    except Exception as error:
        log("WARN 护航查询失败（放行）: " + str(error))
        return "allow", ""


def handle_guard(event: dict, platform: str) -> None:
    """把 PreToolUse 摘要 POST 给 App，回写 permissionDecision（或 {} 放行）。"""
    tool_name = str(event.get("tool_name") or "")
    payload = {
        "session_id": str(event.get("session_id") or ""),
        "tool_name": tool_name,
        "io_kind": tool_io_kind(tool_name),
        "ide": platform,
    }
    api_key = os.environ.get("TUA_API_KEY") or ""
    if not api_key:
        log("WARN 缺少 TUA_API_KEY（护航放行）")
        emit("{}")
        return
    decision, reason = query_guard(guard_endpoint(), api_key, payload)
    if decision in ("ask", "deny"):
        log("护航命中 %s (%s)" % (decision, payload["tool_name"]))
        emit(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": decision,
                "permissionDecisionReason": reason,
            }
        }, ensure_ascii=False))
    else:
        emit("{}")


# ----------------------------------------------------------------------
# 事前护航（M14 T1）：UserPromptSubmit 同步探询，低就绪度则拦下重提
# ----------------------------------------------------------------------

def precheck_endpoint() -> str:
    explicit = os.environ.get("TUA_PRECHECK_ENDPOINT") or ""
    if explicit:
        return explicit
    base = os.environ.get("TUA_ENDPOINT") or DEFAULT_ENDPOINT
    return base.replace("/api/v1/events", "/api/v1/precheck")


def query_precheck(url: str, api_key: str, payload: dict) -> tuple:
    """同步查事前决策，返回 (decision, reason)。任何异常 → ('pass','')（fail-open，绝不拖住 IDE）。"""
    try:
        request = urllib.request.Request(
            url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + api_key},
        )
        with urllib.request.urlopen(request, timeout=GUARD_TIMEOUT) as response:
            body = json.loads(response.read().decode("utf-8") or "{}")
        if str(body.get("decision") or "pass") == "block":
            return "block", str(body.get("reason") or "TUA 拦下了这条提问：说得不太清楚，AI 容易理解偏或白跑。")
        return "pass", ""
    except Exception as error:
        log("WARN 事前查询失败（放行）: " + str(error))
        return "pass", ""


def handle_precheck(event: dict, platform: str) -> int:
    """UserPromptSubmit 事前评估：block → 写 stderr 并 exit 2（IDE 拦下该条并展示）；否则 {} 放行。

    真机验证（M14 探针）：UserPromptSubmit + exit 2 + stderr 能在 Agent 处理前挡下 prompt，
    IDE 把 stderr 原样展示给用户。故本模式**不**走 stdout 注入，只走 stderr + 退出码。
    """
    prompt = extract_text(event.get("prompt"))
    if not prompt.strip():
        emit("{}")   # 空 / 纯图提交：无原文可评，直接放行
        return 0
    payload = {
        "session_id": str(event.get("session_id") or ""),
        "request_set_id": str(event.get("request_set_id") or ""),
        "prompt": prompt,
        "cwd": str(event.get("cwd") or ""),
        "ide": platform,
    }
    api_key = os.environ.get("TUA_API_KEY") or ""
    if not api_key:
        log("WARN 缺少 TUA_API_KEY（事前放行）")
        emit("{}")
        return 0
    decision, reason = query_precheck(precheck_endpoint(), api_key, payload)
    if decision == "block":
        log("事前拦下：就绪度偏低")
        sys.stderr.write(reason + "\n")
        return 2
    emit("{}")
    return 0


# ----------------------------------------------------------------------
# 上报
# ----------------------------------------------------------------------

def post(endpoint: str, api_key: str, events: list) -> None:
    payload = json.dumps({"events": events}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + api_key,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=HTTP_TIMEOUT) as response:
            log("OK status=%s" % response.status)
    except Exception as error:
        # 旁路：上报失败不影响 IDE，仅诊断。
        log("WARN 上报失败（不影响主流程）: " + str(error))


def main(argv: list) -> int:
    platform = "qoder"
    if "--platform" in argv:
        index = argv.index("--platform")
        if index + 1 < len(argv):
            platform = argv[index + 1]
    guard_mode = "--guard" in argv
    precheck_mode = "--precheck" in argv

    try:
        raw = sys.stdin.read()
    except Exception:
        raw = ""
    try:
        event = json.loads(raw) if raw.strip() else {}
    except Exception:
        log("stdin 非法 JSON（忽略）")
        event = {}
    if not isinstance(event, dict):
        event = {}

    # 事前护航模式（UserPromptSubmit 专用）：同步问 App 给 block/pass；block 则 stderr+exit2 拦下。
    if precheck_mode:
        if os.environ.get("TUA_DRY_RUN") == "1":
            log("DRY_RUN 事前干跑，不查询")
            emit("{}")
            return 0
        return handle_precheck(event, platform)

    # 护航同步模式（PreToolUse 专用）：只查询、不重复上报（PostToolUse 仍走旁路）。
    if guard_mode:
        if os.environ.get("TUA_DRY_RUN") == "1":
            log("DRY_RUN 护航干跑，不查询")
            emit("{}")
            return 0
        handle_guard(event, platform)
        return 0

    events = build_events(platform, event)
    log("输入事件=%s 归一化条数=%d" % (event.get("hook_event_name") or "?", len(events)))

    if not events:
        return 0

    if os.environ.get("TUA_DRY_RUN") == "1":
        log("DRY_RUN 不上报，payload=" + json.dumps(events, ensure_ascii=False))
        return 0

    endpoint = os.environ.get("TUA_ENDPOINT") or DEFAULT_ENDPOINT
    api_key = os.environ.get("TUA_API_KEY") or ""
    if not api_key:
        log("WARN 缺少 TUA_API_KEY，跳过上报")
        return 0
    post(endpoint, api_key, events)
    return 0


if __name__ == "__main__":
    try:
        code = main(sys.argv[1:])
    except Exception as error:  # pragma: no cover - 旁路不干扰
        log("异常（已吞掉）: " + str(error))
        code = 0
    if not _emitted["v"]:
        sys.stdout.write("{}")
    sys.exit(code)