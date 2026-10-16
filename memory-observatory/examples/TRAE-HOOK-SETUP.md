# Trae Hooks 接入 Memory Observatory（实时旁路观测）

> **本文只讲「怎么把 Trae 接进来」**。为什么这么做、事件怎么翻译成 turn 契约，见
> [mo_ingest/hook/trae.py](../mo_ingest/hook/trae.py) 的模块 docstring。
>
> **面向对象**：既给人看，也给**后续 Agent 一键执行**。全文命令可直接复制粘贴；
> 所有步骤**幂等**（重复执行不会重复注册、不会覆盖既有配置）。
>
> **敏感信息纪律**：本文**不含任何密钥明文**。API key 一律从仓库 `.env` 现取，
> 落到 `~/.trae-cn/hooks/.mo_env`（权限 600、不在任何 git 仓库内）。
> 执行 Agent **不得**把真实 key 回写进本文或任何被 git 跟踪的文件。
>
> **与 Qoder 接入的区别**：配置文件名与格式不同（`hooks.json`，含顶层 `version: 1`），
> 且 Trae **没有** `request_set_id` —— turn 切分方式必须不同，见第五节。

---

## 一、这套东西是什么

Trae 在关键节点把事件 JSON 从 **stdin** 喂给一个脚本（`~/.trae-cn/hooks.json` 里的
Hook `type: "command"`）。本方案用**统一接入层** [mo_ingest](../mo_ingest/) 的 hook 适配器
（[hook/trae.py](../mo_ingest/hook/trae.py)）把这些事件翻译成项目的 turn 契约 +
`memory.meta.*` OTLP 属性，POST 到 mo-server。**不改 Trae、不改被测项目**。

| Trae 事件 | 作用 | 落到的字段 |
|---|---|---|
| `SessionStart` | 会话建立 | 顺手清理超 6h 的残留状态文件 |
| `UserPromptSubmit` | 用户提交提问后 | 主事件，`turn_user`（**提问原文**，L6 判据唯一可信测量面）**并开启新 turn** |
| `PostToolUse` | 工具执行完成后 | 子事件，带 `action_idx` / `io_kind` |
| `Stop` | Agent 本轮结束 | 汇总主事件：`turn_actions` / `action_count` / `turn_outcome` |

**刻意不注册** `PreToolUse`（会与 `PostToolUse` 生成双份工具事件）与 `Notification`（异步通知，与 turn 无关）。
Trae 没有 `PostToolUseFailure`，工具失败由适配器从 `tool_response` 判定，摘要追加「（失败）」。

旁路纪律（脚本内已实现）：**一切诊断走 stderr、stdout 恒输出 `{}`、任何异常都吞掉并 exit 0**。
原因是 Trae 在 exit 0 时会解析 stdout 当 HookOutput，乱打文本会被误当决策（`{}` 是合法的空决策）。

---

## 二、前置条件（先自检）

| 前置 | 自检命令 | 期望 |
|---|---|---|
| mo-server 在跑 | `docker ps \| grep mo-server` | 有 `memory-observatory-mo-server-1` 且 `Up` |
| OTLP 可达 | `curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:4318/v1/traces -H 'Content-Type: application/json' -d '{}'` | `401`（鉴权开）或 `200`（开放模式） |
| 有 `python3` | `ls -l /usr/bin/python3` | 存在即可（脚本仅用 stdlib） |
| Trae 支持 Hook | 打开 Trae「设置」里的 Hook 页，能读配置文件 | 有即可 |

> **注意 401**：若上面的探测返回 `401`，说明 mo-server 开了鉴权，**hook 必须带 API key**，
> 否则数据一条都进不来（脚本会在 stderr 打 `WARN 上报失败`，但不影响 Trae 使用）。
> key 的位置见第三步。

---

## 三、一键执行（Agent 直接跑这段）

把 `REPO` 改成仓库根目录即可。脚本会：复制适配器 → 生成包装脚本 → 从 `.env` 取 key →
幂等合并 `~/.trae-cn/hooks.json`。

```bash
set -euo pipefail

REPO="/Users/zxc/Documents/ai/Memory-Observatory/memory-observatory"   # ← 按需修改
HOOK_DIR="$HOME/.trae-cn/hooks"
mkdir -p "$HOOK_DIR"

# 1) 复制接入层 mo_ingest 整包（仓库内文件永不改动）
rm -rf "$HOOK_DIR/mo_ingest"
cp -R "$REPO/mo_ingest" "$HOOK_DIR/mo_ingest"

# 2) 生成包装脚本：注入连接参数后调用接入层
#    用包装脚本而非把 key 写进 hooks.json 的原因：
#      a. Trae 从 Dock 启动时不继承 shell 环境变量；
#      b. key 留在 ~/.trae-cn 下、不进任何 git 仓库。
cat > "$HOOK_DIR/mo-report.sh" <<'EOS'
#!/bin/bash
# Memory Observatory · Trae hook 包装器（自动生成，勿手改；改动走 TRAE-HOOK-SETUP.md）
HOOK_DIR="$HOME/.trae-cn/hooks"
# API key 从 .mo_env 读（若存在）。.mo_env 由安装步骤生成、权限 600、不在 git 内。
if [ -f "$HOOK_DIR/.mo_env" ]; then
  set -a            # 关键：让 .mo_env 里的赋值自动 export 给子进程（漏了会 401）
  . "$HOOK_DIR/.mo_env"
  set +a
fi
export MO_OTLP_ENDPOINT="${MO_OTLP_ENDPOINT:-http://localhost:4318/v1/traces}"
# 刻意不设 MO_AGENT_ID：让适配器按事件里的 cwd basename 推断 agent（对齐 log 导入的项目粒度）。
# 若你想固定成一个名字，取消下一行注释并改值。
# export MO_AGENT_ID="trae"
export PYTHONPATH="$HOOK_DIR"     # 让 python -m mo_ingest 能在 HOOK_DIR 下找到包
exec /usr/bin/python3 -m mo_ingest --platform trae --mode hook
EOS
chmod +x "$HOOK_DIR/mo-report.sh"

# 3) 从仓库 .env 取 MO_API_KEY → ~/.trae-cn/hooks/.mo_env（密钥落地在仓库外）
if [ -f "$REPO/.env" ]; then
  grep -E '^MO_API_KEY=' "$REPO/.env" > "$HOOK_DIR/.mo_env" || true
  chmod 600 "$HOOK_DIR/.mo_env"
fi

# 4) 幂等合并 hooks 块到 ~/.trae-cn/hooks.json（保留既有键）
#    注意：Trae 的 hooks.json 有顶层 version 字段（当前仅支持 1）。
/usr/bin/python3 - "$HOME/.trae-cn/hooks.json" <<'EOP'
import json, sys
from pathlib import Path

CMD = "~/.trae-cn/hooks/mo-report.sh"

def group(matcher=None):
    g = {"hooks": [{"type": "command", "command": CMD, "timeout": 10}]}
    if matcher is not None:
        g["matcher"] = matcher
    return g

want = {
    "SessionStart":     [group()],
    "UserPromptSubmit": [group()],
    "PostToolUse":      [group("*")],
    "Stop":             [group()],
}

p = Path(sys.argv[1])
p.parent.mkdir(parents=True, exist_ok=True)
cfg = json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
cfg.setdefault("version", 1)              # Trae 要求；当前仅支持 1
hooks = cfg.setdefault("hooks", {})
for ev, groups in want.items():
    cur = hooks.setdefault(ev, [])
    already = any(any(h.get("command") == CMD for h in g.get("hooks", [])) for g in cur)
    if not already:
        cur.extend(groups)
p.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("merged hooks into", p)
EOP

echo "✅ 安装完成。下一步：重启 Trae IDE，再按第四步验证。"
```

**为什么不设 `loop_limit`**：该字段只对 `Stop` 生效，用来限制 Hook 反复阻断停止的次数；
本脚本从不输出 `decision: block`（stdout 恒 `{}`），因此永远不会阻断，保留默认值 5 即可。

**为什么全局而不是项目级**：全局 `~/.trae-cn/hooks.json` 对本机**所有** Trae 工作区生效
（与 Qoder 侧口径一致）。若只想观测某个项目，把第 4 步的目标改写成
`"$REPO/.trae/hooks.json"`（项目级，只对该项目生效）——但那样每换项目要再配一次。

---

## 四、验证（三步）

### 1. 干跑：只解析、不上报

```bash
echo '{"session_id":"probe-s2","cwd":"/tmp/probe-proj","hook_event_name":"UserPromptSubmit","prompt":"探针：写个快排"}' \
  | MO_HOOK_DRY_RUN=1 "$HOME/.trae-cn/hooks/mo-report.sh" 2>&1 | tail -3
```

期望：stderr 出现 `输入事件=…` 与 `DRY_RUN 不上报，payload=…`，且 **stdout 为 `{}`**。
payload 里应能看到 `memory.meta.turn_message_id = "probe-s2#1"`（轮次号后缀是本适配器的切分标记）。

### 2. 真上报一条，再查库

```bash
echo '{"session_id":"probe-s2","cwd":"/tmp/probe-proj","hook_event_name":"UserPromptSubmit","prompt":"探针：写个快排"}' \
  | "$HOME/.trae-cn/hooks/mo-report.sh"

docker exec memory-observatory-postgres-1 psql -U mo -d mo -c \
  "SELECT metadata->>'turn_message_id' AS turn, metadata->>'io_kind' AS io_kind,
          left(metadata->>'turn_user', 30) AS user_text
     FROM memory_events WHERE metadata->>'source' = 'trae_hook';"
```

期望：出现一行 `probe-s2#1 | lifecycle | 探针：写个快排`。

> **排障（Qoder 侧实测踩过，同源问题）**：第 1 步干跑**不发送请求，验证不了鉴权**，只看第 2 步。
> - 脚本 stderr 出现 `[trae-hook] OK status=200` → 通了。
> - stderr 出现 `[trae-hook] WARN 上报失败（不影响主流程）: HTTP Error 401` → key 没送到 python。
>   八成是包装脚本 source `.mo_env` 时**漏了 `set -a`**（未 export），或 `.mo_env` 为空。
>   自查：`bash -c 'set -a; . ~/.trae-cn/hooks/.mo_env; set +a; [ -n "$MO_API_KEY" ] && echo key-ok || echo key-missing'`。
> - 查库为空但脚本报 200 → 写入有**秒级可见性延迟**，等两三秒再查。

### 3. 清理探针（别污染真库）

```bash
docker exec memory-observatory-postgres-1 psql -U mo -d mo -c \
  "DELETE FROM memory_events WHERE metadata->>'turn_message_id' LIKE 'probe-s2%';"
```

---

## 五、turn 怎么切（与 Qoder 的关键差异）

Qoder 的事件带 `request_set_id`（一次 IDE 请求一个值），Qoder 适配器直接拿它当 turn 键。
**Trae 的 stdin 没有这个字段**，直接照搬会让整个会话并成一个 turn，
`action_count` / `turn_actions` / `io_kind_mix` 全部失真。

本适配器改用 **`UserPromptSubmit` 开新 turn / `Stop` 收尾** 的配对，轮次号由状态文件里的会话计数器维护：

```
~/.trae-cn/hooks/.mo_state/<md5("cursor:{sid}")>.json   → {"turn": N}
~/.trae-cn/hooks/.mo_state/<md5("{sid}#N")>.json        → {"user":…, "actions":[…], "agent":…}
```

于是同一会话的第 1、2 轮分别落成 `{sid}#1`、`{sid}#2`——这正是「两轮不并成一坨」的保证。
会话异常中断（有 `UserPromptSubmit` 无 `Stop`）时**不作弊凑**：下一轮直接开新 turn，
旧 turn 残留状态由 `SessionStart` 的 `sweep_stale()` 在 6h TTL 后回收。

---

## 六、让真实数据正确归类

服务端把平台原始 `source`（`trae_hook`）归类到家族 `hook`，清单存在 DB 表
`event_source_family`，由
[SourceFamilyClassifier.java](../mo-server/src/main/java/io/memobservatory/server/turn/SourceFamilyClassifier.java)
以 60s TTL 惰性装载。

**若你的 mo-server 是本功能引入之前构建的，需要重建一次**（`schema.sql` 在启动时自动建表并播种
`trae_hook → hook`）：

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

## 七、已知边界（如实标注）

| 项 | 现状 |
|---|---|
| turn 切分键 | Trae 无 `request_set_id`，改用 `UserPromptSubmit`/`Stop` 配对 + 会话计数器。**同一会话内轮次正确**；若 Trae 在同一会话并发多请求，计数可能串号（未实测）。 |
| 工具失败判定 | Trae 无 `PostToolUseFailure`，适配器从 `tool_response` 的 `error`/`is_error`/`status`/`exit_code` 多形态**推断**，属**推断口径**，**待真机确认**字段实际形态。 |
| `agent_id` | hook 侧取 `cwd` basename（或 `workspace_roots[0]`）；log 导入侧取项目目录名派生的项目名。两者口径**不同**且 hook 侧属**推断**——同一项目经两条路进来可能落成两个 agent，分析时按需归并。 |
| `RunCommand` 等工具 | 不在 `contract.TOOL_IO_KIND` 表内 → 落 `tool_other`（「认不出不猜」，与既有纪律一致）。 |
| `operation` 轴 | 从真工具名推 retrieve/store，属**代理口径**，非观测到的记忆原语。 |

---

## 八、停用 / 排障

**临时停用**：把 `~/.trae-cn/hooks.json` 里的 `hooks` 块删掉（或把 `mo-report.sh` 改名为
`mo-report.sh.disabled`），重启 Trae。数据侧无需清理。
若你装的是项目级（`$REPO/.trae/hooks.json`），删该文件里的 `hooks` 块即可。

**看 hook 日志**：hook 的 stderr 不会进 Trae 界面；要看日志，把 `hooks.json` 里该条
`command` 临时改成 `... mo-report.sh >> /tmp/mo-trae-hook.log 2>&1`（或在包装脚本里加重定向）再复现。

**收窄到项目级**：见第三节「为什么全局而不是项目级」。
