#!/bin/bash
# Token 用量助手 · M12 干预探针包装器（由 install-guard-probe.sh 生成，勿手改）
# 定位 python3 → 透传 TUA_PROBE_MODE（默认 deny）→ exec 探针本体。
HOOK_DIR="$HOME/.qoder/hooks"
PY="/usr/bin/python3"
[ -x "$PY" ] || PY="/opt/homebrew/bin/python3"
[ -x "$PY" ] || PY="/usr/local/bin/python3"
[ -x "$PY" ] || PY="$(command -v python3 || echo /usr/bin/python3)"
export TUA_PROBE_MODE="${TUA_PROBE_MODE:-deny}"
exec "$PY" "$HOOK_DIR/tua_guard_probe.py"
