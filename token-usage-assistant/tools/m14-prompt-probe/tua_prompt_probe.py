#!/usr/bin/env python3
"""Token 用量助手 · M14 事前干预探针（UserPromptSubmit 语义验证）。

目的（单变量实验）：确认 Qoder IDE 的 `UserPromptSubmit` hook 到底支持哪些事前
干预通道。这是策划 §2 里 T1 注入的**未验证前提**（M12 当年只验了 PreToolUse）。
结论直接决定「事前拦下 prompt → 展示问题/损失/改写 → 让用户重提」能不能做。

三条待验通道（每次只验一条，用 mode 切档、免重启）：
  - reject（默认）：首轮把一段说明写 **stderr** 并 `exit 2` 拒绝该 prompt。
        若 IDE 把 stderr 展示给用户、且 Agent 不处理这条 → exit2 拒绝通道成立。
        一次性闸门：只拦第一次，之后一律放行，绝不把人锁死。
  - inject：stdout 输出 `hookSpecificOutput.additionalContext`，exit 0。
        若 Agent 的回答里体现了这段注入内容 → additionalContext 注入通道成立。
  - plain：stdout 输出**纯文本**（非 JSON），exit 0。
        若也被注入 → 纯文本 stdout 注入通道成立（最省事的注入形态）。
  - allow：只捕获、不干预，验证 hook 确实在被调用 + 抓字段契约。

无论哪种模式，每次触发都把原始 stdin payload 追加进 captures.jsonl（供
read-prompt-captures.py 核对 M14 到底能拿到哪些字段：prompt / session_id / …）。

纪律：异常全吞；除 reject 通道有意 exit 2 外，其余一律 exit 0；绝不弄坏 IDE。
卸载：跑同目录 uninstall-prompt-probe.sh。
"""
from __future__ import annotations

import json
import os
import sys
import time

PROBE_DIR = os.path.expanduser("~/.tua-probe/prompt")
CAPTURES = os.path.join(PROBE_DIR, "captures.jsonl")   # 原始 payload 逐行留档
DECISIONS = os.path.join(PROBE_DIR, "decisions.log")    # 人读一行式干预流水
FIRE_MARK = os.path.join(PROBE_DIR, ".fired")           # reject 一次性闸门
MODE_FILE = os.path.join(PROBE_DIR, "mode")             # 切档免重启：reject/inject/plain/allow

VALID_MODES = ("reject", "inject", "plain", "allow")

# 注入/纯文本里放的探针标记：让 Agent「复述」这段，便于在回答里一眼确认是否真注入成功。
PROBE_TOKEN = "TUA-M14-PROBE"


def resolve_mode() -> str:
    """优先级：env TUA_PROMPT_PROBE_MODE > mode 文件 > 默认 reject。改文件即时生效。"""
    mode = (os.environ.get("TUA_PROMPT_PROBE_MODE") or "").strip().lower()
    if mode in VALID_MODES:
        return mode
    try:
        with open(MODE_FILE, encoding="utf-8") as handle:
            file_mode = handle.read().strip().lower()
        if file_mode in VALID_MODES:
            return file_mode
    except Exception:
        pass
    return "reject"


def now_ms() -> int:
    return int(time.time() * 1000)


def append(path: str, text: str) -> None:
    os.makedirs(PROBE_DIR, exist_ok=True)
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(text)


def log_line(fields: dict) -> None:
    line = "[prompt-probe] " + time.strftime("%Y-%m-%d %H:%M:%S") + " " + " ".join(
        "%s=%s" % (k, v) for k, v in fields.items()
    )
    append(DECISIONS, line + "\n")


def already_rejected_once() -> bool:
    """reject 闸门：首次 False（应拒绝）并置位；之后 True（放行）。"""
    os.makedirs(PROBE_DIR, exist_ok=True)
    if os.path.exists(FIRE_MARK):
        return True
    try:
        with open(FIRE_MARK, "w", encoding="utf-8") as handle:
            handle.write(str(now_ms()))
    except Exception:
        pass
    return False


def main() -> int:
    raw = ""
    try:
        raw = sys.stdin.read()
    except Exception:
        raw = ""

    # 1) 原样留档（哪怕非法 JSON 也存，看 IDE 到底喂了什么字段）。
    append(CAPTURES, (raw if raw.strip() else "{}") + "\n")

    try:
        event = json.loads(raw) if raw.strip() else {}
    except Exception:
        event = {}
    if not isinstance(event, dict):
        event = {}
    prompt = str(event.get("prompt") or "")[:40]
    mode = resolve_mode()

    # 2) 出决策。
    if mode == "reject":
        if already_rejected_once():
            log_line({"mode": mode, "prompt": prompt, "result": "pass(已拒过一次)"})
            sys.stdout.write("{}")
            return 0
        # 写 stderr 供 IDE 展示；exit 2 拒绝该 prompt。
        msg = (
            "【TUA M14 探针 · 事前拒绝】如果你（用户）在提交 prompt 后、"
            "Agent 真正开始干活前看到了这条提示，说明 IDE 认 UserPromptSubmit 的 "
            "exit 2 + stderr —— 「事前拦下、展示问题/损失/改写、让你重提」这条路可做。\n"
            "模拟干预内容：⚠️ 这条提问可能触发历史高频异常「原地打转」，预计多烧 ~14 积分；"
            "建议改写为：『只做 X，不要 Y，产出 Z 即可』。\n"
            "（一次性探针，下一条 prompt 起自动放行，不会锁死。）"
        )
        sys.stderr.write(msg + "\n")
        log_line({"mode": mode, "prompt": prompt, "result": "reject(首次,exit2)"})
        return 2

    if mode == "inject":
        ctx = (
            "[%s] 系统注入的护航上下文：若你（模型）看到本行，请在回答开头原样复述"
            "『已收到 %s 注入』。真实护航会在此注入：上轮账单、个人基线、就绪度补充建议。"
            % (PROBE_TOKEN, PROBE_TOKEN)
        )
        out = {"hookSpecificOutput": {"hookEventName": "UserPromptSubmit", "additionalContext": ctx}}
        sys.stdout.write(json.dumps(out, ensure_ascii=False))
        log_line({"mode": mode, "prompt": prompt, "result": "inject(additionalContext)"})
        return 0

    if mode == "plain":
        # 纯文本 stdout（非 JSON）：验证是否也被当上下文注入。
        sys.stdout.write(
            "%s 纯文本注入探针：若你（模型）在上下文里看到本句，请在回答开头复述"
            "『已收到 %s 纯文本注入』。" % (PROBE_TOKEN, PROBE_TOKEN)
        )
        log_line({"mode": mode, "prompt": prompt, "result": "plain(stdout注入)"})
        return 0

    # allow：纯捕获。
    log_line({"mode": mode, "prompt": prompt, "result": "allow(纯捕获)"})
    sys.stdout.write("{}")
    return 0


if __name__ == "__main__":
    code = 0
    try:
        code = main()
    except Exception as error:  # fail-open：异常一律放行，绝不弄坏 IDE
        try:
            sys.stderr.write("[prompt-probe] 异常(已吞): %s\n" % error)
        except Exception:
            pass
        code = 0
        try:
            sys.stdout.write("{}")
        except Exception:
            pass
    sys.exit(code)
