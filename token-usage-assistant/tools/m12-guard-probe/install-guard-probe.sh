#!/bin/bash
# 安装 M12 干预探针：把 tua_guard_probe.py + 包装器落进 ~/.qoder/hooks/，
# 并在 Qoder / Qoder-CN 配置里幂等新增一个 PreToolUse hook（不碰既有 mo/tua hook）。
# 每个被改写的配置文件先备份（*.m12-bak-<时间戳>）。卸载：uninstall-guard-probe.sh
set -uo pipefail

TOOLS="$(cd "$(dirname "$0")" && pwd)"
HOOK_DIR="$HOME/.qoder/hooks"
mkdir -p "$HOOK_DIR"

cp "$TOOLS/tua_guard_probe.py" "$HOOK_DIR/tua_guard_probe.py"
cp "$TOOLS/tua-guard-probe.sh" "$HOOK_DIR/tua-guard-probe.sh"
chmod +x "$HOOK_DIR/tua_guard_probe.py" "$HOOK_DIR/tua-guard-probe.sh"
mkdir -p "$HOME/.tua-probe/guard"
rm -f "$HOME/.tua-probe/guard/.fired"   # 重置一次性闸门，确保重装后能再拦一次

/usr/bin/python3 - "$HOME/.qoder/settings.json" "$HOME/.qoder-cn/settings.json" <<'EOP'
import json, sys, shutil, datetime
from pathlib import Path

CMD = "~/.qoder/hooks/tua-guard-probe.sh"

def group(cmd, matcher=None):
    g = {"hooks": [{"type": "command", "command": cmd, "timeout": 10}]}
    if matcher is not None:
        g["matcher"] = matcher
    return g

for path in sys.argv[1:]:
    p = Path(path)
    if not p.parent.is_dir():
        print("skip (未安装):", p)
        continue
    if p.exists():
        bak = Path(str(p) + ".m12-bak-" + datetime.datetime.now().strftime("%Y%m%d%H%M%S"))
        shutil.copy2(p, bak)
        print("backup:", bak)
    cfg = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
    hooks = cfg.setdefault("hooks", {})
    cur = hooks.setdefault("PreToolUse", [])
    already = any(any(h.get("command") == CMD for h in g.get("hooks", [])) for g in cur)
    if not already:
        cur.append(group(CMD, "*"))
        p.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("installed PreToolUse probe →", p)
    else:
        print("already installed (未改动):", p)
EOP

MODE="${TUA_PROBE_MODE:-deny}"
echo "✅ M12 探针已装（模式=${MODE}）。接下来："
echo "   1) 想换模式（deny/ask/allow）免重启：echo ask > ~/.tua-probe/guard/mode ；"
echo "   2) 【重启 Qoder IDE】让 hook 生效；"
echo "   3) 随便触发一次工具调用（如让它跑个 ls）；"
echo "      - 若这次调用在‘执行前’被挡下并弹出带【TUA M12 探针 · deny】的说明 → IDE 认 deny，T3 熔断可做；"
echo "      - 若没任何拦截、工具照常跑 → IDE 不认 stdout 的 permissionDecision（契约待退 T1 注入）；"
echo "   4) 跑 read-captures.py 看真实 PreToolUse 字段与拦截流水。"
echo "   验证完记得跑 uninstall-guard-probe.sh 卸载。"
