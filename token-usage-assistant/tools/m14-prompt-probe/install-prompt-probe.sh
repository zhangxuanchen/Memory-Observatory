#!/bin/bash
# 安装 M14 事前探针：把 tua_prompt_probe.py + 包装器落进 ~/.qoder/hooks/，
# 并在 Qoder / Qoder-CN 配置里幂等新增一个 UserPromptSubmit hook（不碰既有 mo/tua hook）。
# 每个被改写的配置文件先备份（*.m14-bak-<时间戳>）。卸载：uninstall-prompt-probe.sh
set -uo pipefail

TOOLS="$(cd "$(dirname "$0")" && pwd)"
HOOK_DIR="$HOME/.qoder/hooks"
mkdir -p "$HOOK_DIR"

cp "$TOOLS/tua_prompt_probe.py" "$HOOK_DIR/tua_prompt_probe.py"
cp "$TOOLS/tua-prompt-probe.sh" "$HOOK_DIR/tua-prompt-probe.sh"
chmod +x "$HOOK_DIR/tua_prompt_probe.py" "$HOOK_DIR/tua-prompt-probe.sh"
mkdir -p "$HOME/.tua-probe/prompt"
rm -f "$HOME/.tua-probe/prompt/.fired"   # 重置一次性闸门，确保重装后能再拦一次
echo "${TUA_PROMPT_PROBE_MODE:-reject}" > "$HOME/.tua-probe/prompt/mode"

/usr/bin/python3 - "$HOME/.qoder/settings.json" "$HOME/.qoder-cn/settings.json" <<'EOP'
import json, sys, shutil, datetime
from pathlib import Path

CMD = "~/.qoder/hooks/tua-prompt-probe.sh"

def group(cmd):
    # UserPromptSubmit 无 tool matcher：与既有 tua-report 注册口径一致，不带 matcher。
    return {"hooks": [{"type": "command", "command": cmd, "timeout": 10}]}

for path in sys.argv[1:]:
    p = Path(path)
    if not p.parent.is_dir():
        print("skip (未安装):", p)
        continue
    if p.exists():
        bak = Path(str(p) + ".m14-bak-" + datetime.datetime.now().strftime("%Y%m%d%H%M%S"))
        shutil.copy2(p, bak)
        print("backup:", bak)
    cfg = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
    hooks = cfg.setdefault("hooks", {})
    cur = hooks.setdefault("UserPromptSubmit", [])
    already = any(any(h.get("command") == CMD for h in g.get("hooks", [])) for g in cur)
    if not already:
        cur.append(group(CMD))
        p.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("installed UserPromptSubmit probe →", p)
    else:
        print("already installed (未改动):", p)
EOP

MODE="$(cat "$HOME/.tua-probe/prompt/mode")"
echo "✅ M14 探针已装（模式=${MODE}）。接下来："
echo "   1) 换模式（免重启）：echo inject > ~/.tua-probe/prompt/mode   （reject/inject/plain/allow）"
echo "   2) 【重启 Qoder IDE】让 hook 生效；"
echo "   3) 按当前模式验证："
echo "      - reject：随便发一条 prompt，若在 Agent 干活前被挡下并弹出【TUA M14 探针 · 事前拒绝】→ exit2+stderr 通道成立（下一条会放行）；"
echo "      - inject：发任意 prompt，若模型回答开头复述『已收到 TUA-M14-PROBE 注入』→ additionalContext 通道成立；"
echo "      - plain：同上但看『纯文本注入』；"
echo "   4) 跑 read-prompt-captures.py 看真实 UserPromptSubmit 字段与流水。"
echo "   验证完记得跑 uninstall-prompt-probe.sh 卸载。"
