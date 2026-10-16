#!/bin/bash
# 卸载 stdin 探针：从 Qoder / Qoder-CN 配置里移除含 dump-hook.sh 的 hook 分组。
# 幂等；不改动其他 hook（如 mo-report）。
set -uo pipefail

/usr/bin/python3 - \
  "$HOME/.qoder/settings.json" \
  "$HOME/.qoder-cn/settings.json" <<'EOP'
import json, sys
from pathlib import Path

MARK = "dump-hook.sh"
for path in sys.argv[1:]:
    p = Path(path)
    if not p.exists():
        print("skip (不存在):", p)
        continue
    cfg = json.loads(p.read_text(encoding="utf-8"))
    hooks = cfg.get("hooks", {})
    removed = 0
    for ev, groups in list(hooks.items()):
        kept = []
        for g in groups:
            cmds = [h.get("command", "") for h in g.get("hooks", [])]
            if any(MARK in c for c in cmds):
                removed += 1
            else:
                kept.append(g)
        if kept:
            hooks[ev] = kept
        else:
            hooks.pop(ev, None)
    p.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"removed {removed} probe group(s) from {p}")
EOP
echo "✅ 探针已卸载。重启 IDE 后不再写入捕获。"