#!/bin/bash
# 幂等安装 stdin 探针为「额外的」hook（不替换既有的 mo-report hook）。
# 每个被改写的配置文件都会先备份（*.tua-bak-<时间戳>）。
# 卸载：跑同目录的 uninstall-probe.sh
set -uo pipefail

TOOLS="$(cd "$(dirname "$0")" && pwd)"
DUMP="$TOOLS/dump-hook.sh"
chmod +x "$DUMP"
mkdir -p "$HOME/.tua-probe/captures"

/usr/bin/python3 - "$DUMP" \
  "$HOME/.qoder/settings.json:qoder" \
  "$HOME/.qoder-cn/settings.json:qoder" <<'EOP'
import json, sys, shutil, datetime
from pathlib import Path

dump = sys.argv[1]
targets = [a.rsplit(":", 1) for a in sys.argv[2:]]

def group(cmd, matcher=None):
    g = {"hooks": [{"type": "command", "command": cmd, "timeout": 10}]}
    if matcher is not None:
        g["matcher"] = matcher
    return g

for path, label in targets:
    p = Path(path)
    if not p.parent.is_dir():
        print("skip (未安装):", p)
        continue
    if p.exists():
        bak = Path(str(p) + ".tua-bak-" + datetime.datetime.now().strftime("%Y%m%d%H%M%S"))
        shutil.copy2(p, bak)
        print("backup:", bak)
    cfg = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
    cmd = dump + " " + label
    hooks = cfg.setdefault("hooks", {})
    want = {
        "SessionStart":     [group(cmd)],
        "UserPromptSubmit": [group(cmd)],
        "PostToolUse":      [group(cmd, "*")],
        "Stop":             [group(cmd)],
    }
    for ev, groups in want.items():
        cur = hooks.setdefault(ev, [])
        already = any(any(h.get("command") == cmd for h in g.get("hooks", [])) for g in cur)
        if not already:
            cur.extend(groups)
    p.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("installed probe →", p)
EOP

echo "✅ 探针安装完成。请【重启 IDE】，然后正常用几轮（发几条 prompt + 触发一次工具调用），再跑 analyze-captures.py 分析。"