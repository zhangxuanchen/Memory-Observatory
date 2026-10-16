#!/bin/bash
# Token 用量助手 · M14 事前探针包装器（由 install-prompt-probe.sh 生成，勿手改）
# 定位 python3 → 透传 TUA_PROMPT_PROBE_MODE → exec 探针本体。
HOOK_DIR="$HOME/.qoder/hooks"
PY="/usr/bin/python3"
[ -x "$PY" ] || PY="/opt/homebrew/bin/python3"
[ -x "$PY" ] || PY="/usr/local/bin/python3"
[ -x "$PY" ] || PY="$(command -v python3 || echo /usr/bin/python3)"
exec "$PY" "$HOOK_DIR/tua_prompt_probe.py"
