#!/usr/bin/env python3
"""Token 用量助手 · M12 干预探针（PreToolUse ask/deny 语义验证）。

目的（单变量实验）：确认 Qoder IDE 到底认不认 `PreToolUse` 的
`hookSpecificOutput.permissionDecision`。这是策划 §2「待真机验证」项，
结论直接决定 T2 询问 / T3 熔断能不能做。

行为：
  - 每次 PreToolUse 触发都把**原始 stdin payload** 追加进捕获日志
    （供 read-captures.py 核对字段契约，M13 要用）。
  - 决策由环境变量 TUA_PROBE_MODE 控制（默认 deny）：
      * deny：安装后**第一轮**返回 permissionDecision=deny + reason（一次性拦截），
              之后一律放行 —— 绝不长期拦，防止把 IDE 锁死。
      * ask ：每轮返回 permissionDecision=ask（需用户授权），验证「弹授权」形态。
      * allow：只捕获、不干预（纯旁路），验证 hook 确实在被调用。
  - stdout 恒为合法 HookOutput JSON、异常全吞、始终 exit 0（fail-open，绝不弄坏 IDE）。

卸载：跑同目录 uninstall-guard-probe.sh（移除 hook + 脚本 + marker）。
"""
from __future__ import annotations

import json
import os
import sys
import time

PROBE_DIR = os.path.expanduser("~/.tua-probe/guard")
CAPTURES = os.path.join(PROBE_DIR, "captures.jsonl")        # 原始 payload 逐行留档
DECISIONS = os.path.join(PROBE_DIR, "decisions.log")         # 人读一行式干预流水
FIRE_MARK = os.path.join(PROBE_DIR, ".fired")                # deny 一次性闸门
MODE_FILE = os.path.join(PROBE_DIR, "mode")                  # 切档免重启：deny/ask/allow


def resolve_mode() -> str:
    """优先级：env TUA_PROBE_MODE > ~/.tua-probe/guard/mode 文件 > 默认 deny。
    改 mode 文件即时生效（每次触发都重读），无需重启 IDE。"""
    mode = (os.environ.get("TUA_PROBE_MODE") or "").strip().lower()
    if mode in ("deny", "ask", "allow"):
        return mode
    try:
        with open(MODE_FILE, encoding="utf-8") as handle:
            file_mode = handle.read().strip().lower()
        if file_mode in ("deny", "ask", "allow"):
            return file_mode
    except Exception:
        pass
    return "deny"


def now_ms() -> int:
    return int(time.time() * 1000)


def append(path: str, text: str) -> None:
    os.makedirs(PROBE_DIR, exist_ok=True)
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(text)


def log_line(fields: dict) -> None:
    line = "[guard-probe] " + time.strftime("%Y-%m-%d %H:%M:%S") + " " + " ".join(
        "%s=%s" % (k, v) for k, v in fields.items()
    )
    append(DECISIONS, line + "\n")


def already_denied_once() -> bool:
    """deny 模式下的闸门：首次返回 False（应拦截），并置位；之后返回 True（放行）。"""
    os.makedirs(PROBE_DIR, exist_ok=True)
    if os.path.exists(FIRE_MARK):
        return True
    try:
        with open(FIRE_MARK, "w", encoding="utf-8") as handle:
            handle.write(str(now_ms()))
    except Exception:
        pass
    return False


def emit(decision: str, reason: str) -> None:
    """把决策写成 PreToolUse 的 hookSpecificOutput（exit 0）。"""
    out = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": reason,
        }
    }
    sys.stdout.write(json.dumps(out, ensure_ascii=False))


def main() -> int:
    raw = ""
    try:
        raw = sys.stdin.read()
    except Exception:
        raw = ""

    # 1) 原样留档（哪怕非法 JSON 也存，方便看 IDE 到底喂了什么）。
    append(CAPTURES, (raw if raw.strip() else "{}") + "\n")

    try:
        event = json.loads(raw) if raw.strip() else {}
    except Exception:
        event = {}
    if not isinstance(event, dict):
        event = {}

    tool = str(event.get("tool_name") or "?")
    mode = resolve_mode()

    # 2) 出决策。
    if mode == "ask":
        reason = ("【TUA M12 探针 · ask】若你看到需要为这次工具调用手动授权，"
                  "说明 IDE 认 permissionDecision=ask —— T2 询问档可做。")
        emit("ask", reason)
        log_line({"mode": mode, "tool": tool, "decision": "ask"})
    elif mode == "allow":
        log_line({"mode": mode, "tool": tool, "decision": "allow(纯捕获)"})
        sys.stdout.write("{}")
    else:  # deny（默认）
        if already_denied_once():
            log_line({"mode": mode, "tool": tool, "decision": "allow(已拦过一次)"})
            sys.stdout.write("{}")
        else:
            reason = ("【TUA M12 探针 · deny】如果你在工具执行**之前**看到了这条拦截说明，"
                      "说明 Qoder IDE 认 PreToolUse 的 permissionDecision=deny —— "
                      "熔断档(T3)可做。这是一次性探针，下一次起就放行，不会锁死 IDE。")
            emit("deny", reason)
            log_line({"mode": mode, "tool": tool, "decision": "deny(首次)"})
    return 0


if __name__ == "__main__":
    try:
        code = main()
    except Exception as error:  # fail-open：任何异常都放行，绝不弄坏 IDE
        sys.stderr.write("[guard-probe] 异常(已吞): %s\n" % error)
        code = 0
        try:
            sys.stdout.write("{}")
        except Exception:
            pass
    sys.exit(code)
