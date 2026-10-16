# Qoder Hooks 接入 Memory Observatory（实时旁路观测）

> **本文只讲「怎么把 Qoder 接进来」**。为什么这么做、事件怎么翻译成 turn 契约，见
> [mo_ingest/hook/qoder.py](../mo_ingest/hook/qoder.py) 的模块 docstring。
>
> **面向对象**：既给人看，也给**后续 Agent 一键执行**。全文命令可直接复制粘贴；
> 所有步骤**幂等**（重复执行不会重复注册、不会覆盖既有配置）。
>
> **敏感信息纪律**：本文**不含任何密钥明文**。API key 一律从仓库 `.env` 现取，
> 落到 `~/.qoder/hooks/.mo_env`（权限 600、不在任何 git 仓库内）。
> 执行 Agent **不得**把真实 key 回写进本文或任何被 git 跟踪的文件。

---

## 一、这套东西是什么

Qoder 在关键节点把事件 JSON 从 **stdin** 喂给一个脚本（Hook `type: "command"`）。
本方案用**统一接入层** [mo_ingest](../mo_ingest/) 的 hook 适配器
（[hook/qoder.py](../mo_ingest/hook/qoder.py)）把这些事件翻译成项目的
turn 契约 + `memory.meta.*` OTLP 属性，POST 到 mo-server。**不改 Qoder 源码、不改被测项目**。

| Qoder 事件 | 作用 | 落到的字段 |
|---|---|---|
| `UserPromptSubmit` | 用户提交提问后 | `turn_user`（**提问原文**，L6 判据唯一可信测量面） |
| `PostToolUse` | 工具成功后 | 子事件，带 `action_idx` / `io_kind` |
| `PostToolUseFailure` | 工具失败后 | 同上（摘要追加「（失败）」） |
| `Stop` | Agent 本轮结束 | 汇总主事件：`turn_actions` / `action_count` / `turn_outcome` |
| `SessionEnd` | 会话结束 | 顺手清理超 6h 的残留状态文件 |

旁路纪律（脚本内已实现）：**一切诊断走 stderr、stdout 恒输出 `{}`、任何异常都吞掉并 exit 0**。
原因是 Qoder 在 exit 0 时会解析 stdout 的 JSON 当 HookOutput，乱打文本会被误当决策。

---

## 二、前置条件（先自检）

| 前置 | 自检命令 | 期望 |
|---|---|---|
| mo-server 在跑 | `docker ps \| grep mo-server` | 有 `memory-observatory-mo-server-1` 且 `Up` |
| OTLP 可达 | `curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:4318/v1/traces -H 'Content-Type: application/json' -d '{}'` | `401`（鉴权开）或 `200`（开放模式） |
| 有 `python3` | `ls -l /usr/bin/python3` | 存在即可（脚本仅用 stdlib） |

> **注意 401**：若上面的探测返回 `401`，说明 mo-server 开了鉴权，**hook 必须带 API key**，
> 否则数据一条都进不来（脚本会在 stderr 打 `WARN 上报失败`，但不影响 Qoder 使用）。
> key 的位置见第三步。

---

## 三、一键执行（Agent 直接跑这段）

把 `REPO` 改成仓库根目录即可。脚本会：复制适配器 → 生成包装脚本 → 从 `.env` 取 key →
幂等合并 `~/.qoder/settings.json`。

```bash
set -euo pipefail

REPO="/Users/zxc/Documents/ai/Memory-Observatory/memory-observatory"   # ← 按需修改
HOOK_DIR="$HOME/.qoder/hooks"
mkdir -p "$HOOK_DIR"

# 1) 复制接入层 mo_ingest 整包（仓库内文件永不改动）
rm -rf "$HOOK_DIR/mo_ingest"
cp -R "$REPO/mo_ingest" "$HOOK_DIR/mo_ingest"

# 2) 生成包装脚本：注入连接参数后调用接入层
#    用包装脚本而非把 key 写进 settings.json 的原因：
#      a. Qoder 从 Dock 启动时不继承 shell 环境变量；
#      b. key 留在 ~/.qoder 下、不进任何 git 仓库。
cat > "$HOOK_DIR/mo-report.sh" <<'EOS'
#!/bin/bash
# Memory Observatory · Qoder hook 包装器（自动生成，勿手改；改动走 QODER-HOOK-SETUP.md）
HOOK_DIR="$HOME/.qoder/hooks"
# API key 从 .mo_env 读（若存在）。.mo_env 由安装步骤生成、权限 600、不在 git 内。
if [ -f "$HOOK_DIR/.mo_env" ]; then
  set -a            # 关键：让 .mo_env 里的赋值自动 export 给子进程
  . "$HOOK_DIR/.mo_env"
  set +a
fi
export MO_OTLP_ENDPOINT="${MO_OTLP_ENDPOINT:-http://localhost:4318/v1/traces}"
export MO_AGENT_ID="${MO_AGENT_ID:-qoder}"
export PYTHONPATH="$HOOK_DIR"     # 让 python -m mo_ingest 能在 HOOK_DIR 下找到包
exec /usr/bin/python3 -m mo_ingest --platform qoder --mode hook
EOS
chmod +x "$HOOK_DIR/mo-report.sh"

# 3) 从仓库 .env 取 MO_API_KEY → ~/.qoder/hooks/.mo_env（密钥落地在仓库外）
if [ -f "$REPO/.env" ]; then
  grep -E '^MO_API_KEY=' "$REPO/.env" > "$HOOK_DIR/.mo_env" || true
  chmod 600 "$HOOK_DIR/.mo_env"
fi

# 4) 幂等合并 hooks 块（保留既有 enabledPlugins 等键）
#    国际版读 ~/.qoder/settings.json，CN 版读 ~/.qoder-cn/settings.json。
#    两个都写（等价于「谁在读就写谁」），否则重启后界面会显示 Hook: 0（实测踩过）。
/usr/bin/python3 - "$HOME/.qoder/settings.json" "$HOME/.qoder-cn/settings.json" <<'EOP'
import json, sys
from pathlib import Path

CMD = "~/.qoder/hooks/mo-report.sh"

def group(matcher=None):
    g = {"hooks": [{"type": "command", "command": CMD, "timeout": 10}]}
    if matcher is not None:
        g["matcher"] = matcher
    return g

want = {
    "UserPromptSubmit":    [group()],
    "PostToolUse":         [group("*")],
    "PostToolUseFailure":  [group("*")],
    "Stop":                [group()],
}

for path in sys.argv[1:]:
    p = Path(path)
    if not p.parent.is_dir():
        print("skip (not installed):", p)
        continue
    cfg = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
    hooks = cfg.setdefault("hooks", {})
    for ev, groups in want.items():
        cur = hooks.setdefault(ev, [])
        already = any(any(h.get("command") == CMD for h in g.get("hooks", [])) for g in cur)
        if not already:
            cur.extend(groups)
    p.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("merged hooks into", p)
EOP

echo "✅ 安装完成。下一步：重启 Qoder IDE，再按第四步验证。"
```

**为什么用同步（不加 `async`）**：异步会让 `Stop` 可能跑在 `PostToolUse` 之前，
导致 `action_count` 少算。本方案要的是 turn 聚合正确，故保持同步；
脚本自身有 3s 的 HTTP 超时且吞异常，不会长时间阻塞 Agent。

---

## 四、验证（三步）

### 1. 干跑：只解析、不上报

```bash
echo '{"session_id":"probe-s1","request_set_id":"probe-r1","cwd":"/tmp","hook_event_name":"UserPromptSubmit","prompt":"探针：写个快排"}' \
  | MO_HOOK_DRY_RUN=1 "$HOME/.qoder/hooks/mo-report.sh" 2>&1 | tail -3
```

期望：stderr 出现 `输入事件=…` 与 `DRY_RUN 不上报，payload=…`，且 **stdout 为 `{}`**。

### 2. 真上报一条，再查库

```bash
echo '{"session_id":"probe-s1","request_set_id":"probe-r1","cwd":"/tmp","hook_event_name":"UserPromptSubmit","prompt":"探针：写个快排"}' \
  | "$HOME/.qoder/hooks/mo-report.sh"

docker exec memory-observatory-postgres-1 psql -U mo -d mo -c \
  "SELECT metadata->>'turn_message_id' AS turn, metadata->>'io_kind' AS io_kind,
          left(metadata->>'turn_user', 30) AS user_text
     FROM memory_events WHERE metadata->>'source' = 'qoder_hook';"
```

期望：出现一行 `probe-r1 | lifecycle | 探针：写个快排`。

> **排障（实测踩过）**：第 1 步干跑**不发送请求，验证不了鉴权**，只看第 2 步。
> - 脚本 stderr 出现 `[qoder-hook] OK status=200` → 通了。
> - stderr 出现 `[qoder-hook] WARN 上报失败（不影响主流程）: HTTP Error 401` → key 没送到 python。
>   八成是包装脚本 source `.mo_env` 时**漏了 `set -a`**（未 export），或 `.mo_env` 为空。
>   自查：`bash -c 'set -a; . ~/.qoder/hooks/.mo_env; set +a; [ -n "$MO_API_KEY" ] && echo key-ok || echo key-missing'`。
> - 查库为空但脚本报 200 → 写入有**秒级可见性延迟**，等两三秒再查。

### 3. 清理探针（别污染真库）

```bash
docker exec memory-observatory-postgres-1 psql -U mo -d mo -c \
  "DELETE FROM memory_events WHERE metadata->>'turn_message_id' = 'probe-r1';"
```

---

## 五、让真实数据正确归类（重要）

装好 hook 后，Qoder 的 turn 就能进库。服务端把平台原始 `source`（`qoder_hook`）归类到
家族 `hook`，清单存在 DB 表 `event_source_family`，由
[SourceFamilyClassifier.java](../mo-server/src/main/java/io/memobservatory/server/turn/SourceFamilyClassifier.java)
以 60s TTL 惰性装载。

**本次升级需要重建一次 mo-server**（新增了 `SourceFamilyClassifier` 与 `event_source_family` 表，
`schema.sql` 在启动时自动建表并播种 `qoder_hook → hook`）：

```bash
cd "$REPO" && docker compose up -d --build mo-server
```

> 归类改造的意义正在**下一次**：以后**新增平台只需运行时插一行，零改 Java、零重建**。
> 例如接入某新 Agent（`source=xxx_hook`）：
> ```bash
> docker exec memory-observatory-postgres-1 psql -U mo -d mo -c \
>   "INSERT INTO event_source_family(source, family) VALUES ('xxx_hook','hook') ON CONFLICT DO NOTHING;"
> ```
> 60 秒内 `SourceFamilyClassifier` 自动刷新，无需重启。

---

## 六、已知边界（如实标注）

| 项 | 现状 |
|---|---|
| `request_set_id` | Qoder 文档标为**可选**；缺省时整段会话并成一个 turn（`_turn_key` 会退化并打 WARN）。**待真机确认它是否稳定填充**。 |
| `turn_outcome` | `Stop` 事件的结果摘要字段文档未明确；适配器按可选消费 `outcome`/`summary`/`response`，取不到就留空。 |
| 工具名覆盖 | 适配器已列原生名（`read_file`/`create_file`/`grep_code`…）与 Claude Code 兼容名（`Read`/`Write`/`Edit`…）两套；未列入的新工具名落 `tool_other`（如实标注、不猜）。 |
| `operation` 轴 | 从真工具名推 retrieve/store，属**代理口径**，非观测到的记忆原语。 |

---

## 七、CN 版路径 / 停用

**CN 构建**：本机可能同时存在 `~/.qoder`（国际版）与 `~/.qoder-cn`（CN 版），**两者读的配置文件不同**。
第三步的合并脚本**已对两份都写**（谁装了写谁），故通常无需手动处理。
若你在 CN 版界面「设置 → 钩子」里看到 `Hook: 0`、来源显示 `~/.qoder-cn/settings.json`，
说明该文件没被写上——重跑第三步第 4 段即可（幂等）。脚本路径 `~/.qoder/hooks/mo-report.sh` 两份共用，不变。

**临时停用**：把 `~/.qoder/settings.json` 与 `~/.qoder-cn/settings.json` 里的 `hooks` 块删掉（或把 `mo-report.sh` 改名为
`mo-report.sh.disabled`），重启 Qoder。数据侧无需清理。

**排障**：hook 的 stderr 不会进 Qoder 界面；要看日志，把 `~/.qoder/settings.json` 里该条
`command` 临时改成 `... mo-report.sh >> /tmp/mo-hook.log 2>&1`（或在包装脚本里加重定向）再复现。
