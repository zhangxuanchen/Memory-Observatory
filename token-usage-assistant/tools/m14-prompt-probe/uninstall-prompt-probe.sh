#!/bin/bash
# 卸载 M14 事前探针：从 Qoder / Qoder-CN 配置里移除含 tua-prompt-probe.sh 的 hook 分组，
# 删除落到 ~/.qoder/hooks/ 的探针脚本。幂等；不改动其他 hook（mo/tua）。
# 捕获数据（~/.tua-probe/prompt/）保留，方便事后核对。
set -uo pipefail

/usr/bin/python3 - "$HOME/.qoder/settings.json" "$HOME/.qoder-cn/settings.json" <<'EOP'
import json, sys
from pathlib import Path

MARK = "tua-prompt-probe.sh"
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

rm -f "$HOME/.qoder/hooks/tua-prompt-probe.sh" "$HOME/.qoder/hooks/tua_prompt_probe.py"
rm -f "$HOME/.tua-probe/prompt/.fired"   # 清一次性闸门
echo "✅ 探针已卸载（重启 IDE 后彻底不再拦截）。捕获数据保留在 ~/.tua-probe/prompt/。"
