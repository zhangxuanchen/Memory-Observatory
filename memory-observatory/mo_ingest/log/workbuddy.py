"""WorkBuddy 旁路日志导入器（纯 stdlib）。

读 ``~/.workbuddy/audit-log/*.jsonl``——WorkBuddy 的**安全审计流**（命令沙箱放行、文件读写
放行、网络抓取、审批）。它**不是**按 turn 汇总的对话记忆：没有用户提问原文、没有助手结果
摘要。本导入器做两件事：

1. 把审计事件按 ``requestId`` 归并成 turn（**推断**：同一 requestId 的多条审计事件同属一次用户请求）；
2. **如实报告**契约里哪些字段拿得到、哪些拿不到——拿不到就留空，不用近似值顶替。

故 ``to_events`` 产出的主事件**不写** ``turn_user`` / ``turn_outcome``（审计流里没有，宁缺勿造）。

可选 ``--with-traces``：只读扫 ``~/.workbuddy/traces``，量 ``toolCallId`` 的跨源 join 覆盖度。
"""
from __future__ import annotations

import collections
import glob
import json
import re
from pathlib import Path
from typing import Optional

from ..contract import (
    IO_KIND_LIFECYCLE,
    MemoryEvent,
    estimate_tokens,
    io_kind_of_text,
    short_key,
)
from .base import LogImporter

TRACE_ROOT = Path.home() / ".workbuddy" / "traces"
# generation span 的 toolInput 在 ~100KB 处被截断，截断后 JSON 不可解析
_TRACE_CAP = 99990

# 噪声：这些段不是「用户项目」，是 WorkBuddy 自身的运行时/工具目录
_NOISE_SEG = (".workbuddy", "WorkBuddy", ".catpet", "Applications", "Library")
_PROJ_RE = re.compile(
    r"/(?:Users|home)/[^/\s\"'`]+/(?:Documents|Downloads|Desktop|Projects|Code|Work)"
    r"/([^/\s\"'`]+(?:/[^/\s\"'`]+)?)"
)
_PATH_CUT = re.compile(r"[\"'`;,\)\s]")
_FILE_EXT = re.compile(
    r"\.(md|pdf|pages|png|jpe?g|gif|webp|html?|js|mjs|cjs|ts|tsx|vue|java|py|json|jsonl"
    r"|txt|csv|xlsx?|docx?|pptx?|zip|tar|gz|mp4|mov|mp3|wav|log|sh|bat|yml|yaml|sql|xml|css|toml)$",
    re.I,
)

# 同一工具调用的多条事件里，选「最定案」的那条 decision 作代表
_DECISION_RANK = {"info": 0, "allowed": 1, "approved": 2, "blocked": 3, "rejected": 4}

# 真用户提问：trace 请求消息里以 <user_query> 包裹
_USER_QUERY_RE = re.compile(r"<user_query>(?!\s*tag\.)(.*?)</user_query>", re.S)
_PSEUDO_QUERY = "Please continue with the conversation based on the summarized context above"


class WorkBuddyLogImporter(LogImporter):
    PLATFORM = "workbuddy"
    SOURCE = "workbuddy_importer"
    DEFAULT_ROOT = "~/.workbuddy/audit-log"
    SCOPE = "mo-workbuddy-importer"

    # ------------------------------------------------------------------
    # load / build_turns / to_events
    # ------------------------------------------------------------------

    def load(self, since: Optional[str], **opts) -> list:
        files = [f for f in glob.glob(str(Path(self.root) / "*.jsonl")) if "manifest" not in f]
        files.sort()
        events: list[dict] = []
        stat = {"files": 0, "lines": 0, "bad_lines": 0}
        for f in files:
            day = Path(f).stem.replace("-", "")
            if since and day < since:
                continue
            stat["files"] += 1
            for line in open(f, encoding="utf-8"):
                line = line.strip()
                if not line:
                    continue
                stat["lines"] += 1
                try:
                    obj = json.loads(line)
                except Exception:
                    stat["bad_lines"] += 1
                    continue
                obj["_file"] = Path(f).name
                events.append(obj)
        events.sort(key=lambda e: e.get("timestamp") or 0)
        self._stat = stat
        return events

    def build_turns(self, events, depth: int = 2, **opts):
        stat = {"no_session": 0, "no_request": 0}
        groups: dict[tuple, list] = collections.OrderedDict()
        for ev in events:
            sid, rid = ev.get("sessionId"), ev.get("requestId")
            if not sid:
                stat["no_session"] += 1
                continue
            if not rid:
                stat["no_request"] += 1
                continue
            groups.setdefault((sid, rid), []).append(ev)

        sids = {s for s, _ in groups}

        def agent_map(d: int):
            votes: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
            for (sid, _rid), evs in groups.items():
                for ev in evs:
                    p = project_of(ev, d)
                    if p:
                        votes[sid][p.split("/")[-1]] += 1
            amap, multi, unknown = {}, 0, 0
            for sid in sids:
                c = votes.get(sid)
                if c:
                    amap[sid] = c.most_common(1)[0][0]
                    if len(c) > 1:
                        multi += 1
                else:
                    amap[sid] = "workbuddy-" + sid[:8]
                    unknown += 1
            return amap, {"multi": multi}, unknown

        agent_of, harder, unknown = agent_map(depth)
        stat["sessions_without_project"] = unknown
        stat["sessions_multi_project"] = harder["multi"]
        sess_turns = collections.Counter(sid for sid, _ in groups)
        dist_by_depth: dict[int, collections.Counter] = {}
        for d in (1, 2):
            amap_d = agent_map(d)[0]
            dist: collections.Counter = collections.Counter()
            for sid, n in sess_turns.items():
                dist[amap_d[sid]] += n
            dist_by_depth[d] = dist
        stat["turn_dist"] = dist_by_depth

        turns = []
        raw_total = 0
        for (sid, rid), evs in groups.items():
            raw_total += len(evs)
            evs = collapse_by_toolcall(evs)
            actions = [action_text(e) for e in evs]
            decisions = collections.Counter(e.get("decision") or "?" for e in evs)
            turns.append({
                "agent_id": agent_of[sid],
                "session_id": sid,
                "request_id": rid,
                "ts": (evs[0].get("timestamp") or 0) / 1000.0,
                "events": evs,
                "actions": actions,
                "decision_mix": dict(decisions),
            })
        stat["raw_events"] = raw_total
        stat["turns"] = len(turns)
        self._gstat = {**getattr(self, "_stat", {}), **stat}
        return turns

    def to_events(self, turn: dict) -> list:
        sid, rid = turn["session_id"], turn["request_id"]
        ts, actions, evs = turn["ts"], turn["actions"], turn["events"]
        blob = " ".join(actions)
        main = MemoryEvent(
            agent_id=turn["agent_id"],
            session_id=sid,
            operation="store",
            layer="session",
            memory_key=short_key(actions[0] if actions else "", rid),
            memory_summary=(actions[0][:200] if actions else ""),
            token_count=estimate_tokens(blob),
            timestamp=ts,
            metadata={
                "source": self.SOURCE,
                "request_id": rid,
                "action_count": str(len(actions)),
                "turn_message_id": rid,
                "turn_actions": json.dumps(actions, ensure_ascii=False),
                "wb_decision_mix": json.dumps(turn["decision_mix"], ensure_ascii=False),
                "io_kind": IO_KIND_LIFECYCLE,
            },
        )
        out = [main]
        for idx, ev in enumerate(evs):
            txt = action_text(ev)
            out.append(MemoryEvent(
                agent_id=turn["agent_id"],
                session_id=sid,
                operation=classify_operation(ev),
                layer="skill",
                memory_key=short_key(txt, f"{rid}-{idx}"),
                memory_summary=txt[:200],
                token_count=estimate_tokens(txt),
                timestamp=ts + idx * 0.01,
                metadata={
                    "source": self.SOURCE,
                    "request_id": rid,
                    "action_idx": str(idx),
                    "turn_message_id": rid,
                    "action_full": txt,
                    "wb_event_type": ev.get("eventType"),
                    "wb_decision": ev.get("decision"),
                    "io_kind": classify_io_kind(ev),
                },
            ))
        return out

    # ------------------------------------------------------------------
    # 覆盖度分析（默认打印）
    # ------------------------------------------------------------------

    def report(self, events, turns, all_events, opts) -> None:
        self._analyze(events, turns, getattr(self, "_gstat", {}))
        if opts.get("with_traces"):
            if not TRACE_ROOT.is_dir():
                print(f"\ntraces 目录不存在：{TRACE_ROOT}")
            else:
                analyze_traces(scan_traces(), events, turns)

    def _analyze(self, events, turns, stat) -> None:
        print("=" * 78)
        print("WorkBuddy 适配器 · 覆盖度分析（只读，未写库）")
        print("=" * 78)
        print(f"\n【输入】{stat.get('files', 0)} 个 jsonl · {stat.get('lines', 0)} 行事件"
              f" · 坏行 {stat.get('bad_lines', 0)}")
        print(_line("无法归组（无 sessionId）", str(stat.get("no_session", 0))))
        print(_line("无法归组（无 requestId）", str(stat.get("no_request", 0))))

        et = collections.Counter(e.get("eventType") or "?" for e in events)
        print("\n【事件构成】eventType 前 8")
        for k, v in et.most_common(8):
            print(f"    {k:46} {v}")

        sizes = collections.Counter(len(t["events"]) for t in turns)
        multi = sum(v for k, v in sizes.items() if k > 1)
        collapsed_total = sum(len(t["events"]) for t in turns)
        print(f"\n【归并结果】{len(turns)} 个 turn（按 requestId 归并，属推断口径）")
        print(_line("工具调用合并", f"{stat.get('raw_events', 0)} 条审计事件 → {collapsed_total} 个动作",
                    "同一 toolCallId 的「命令沙箱 + 文件放行」并成一条，避免动作数翻倍"))
        print(_line("单事件 turn", f"{sizes.get(1, 0)}（{sizes.get(1, 0) * 100 // max(1, len(turns))}%）",
                    "一次请求只留下 1 条审计事件"))
        print(_line("多事件 turn", f"{multi}", f"最多 {max(sizes) if sizes else 0} 条"))
        print(f"    session 数 {len({t['session_id'] for t in turns})}"
              f" · agent 数 {len({t['agent_id'] for t in turns})}")
        print(_line("推不出项目的 session", str(stat.get("sessions_without_project", 0)), "用 workbuddy-<session8> 兜底"))
        print(_line("横跨多个项目的 session", str(stat.get("sessions_multi_project", 0)), "一 session 一 agent 是近似"))

        tal = collections.Counter(t["agent_id"] for t in turns)
        print("\n【agent 分布】前 10（agent_id = session 内最高频项目名，靠路径反推）")
        for k, v in tal.most_common(10):
            print(f"    {k:34} {v:5} turn")

        print("\n【io_kind 分布】动作按「性质」归一后的落点（真口径，B4 分桶就吃这个）")
        kinds = collections.Counter(classify_io_kind(e) for t in turns for e in t["events"])
        for k, v in kinds.most_common():
            print(f"    {k:16} {v:6}")

        print("\n【契约字段覆盖】")
        print("  turn_message_id      ✅ 拿得到　requestId（唯一且稳定）")
        print("  action_count         ✅ 拿得到　= 该 request 内去重后的工具调用数（字符串）")
        print("  turn_actions         ✅ 拿得到　由 commandPreview / paths+messageKey / WebFetch target 生成（代理口径）")
        print("  io_kind              ✅ 拿得到　命令文本命中真工具名/shell 动词优先，退回 messageKey")
        print("  session_id / ts      ✅ 拿得到　sessionId / timestamp(ms)")
        print("  agent_id             ✅ 拿得到　由路径反推项目名（非上游字段）")
        print("  turn_user            ❌ 拿不到　审计流不含用户提问原文")
        print("  turn_outcome         ❌ 拿不到　审计流不含助手结果摘要")
        print("  token_count          ❌ 拿不到　无真实 token 读数，只能按文本长度估算")
        print("  operation / layer    ❌ 拿不到　按 io_kind 粗映射（代理口径）")

        print("\n【样例】turn_actions 实际长什么样（取事件最多的 3 个 turn）")
        for t in sorted(turns, key=lambda x: -len(x["events"]))[:3]:
            print(f"  · turn {t['request_id'][:12]}… agent={t['agent_id']} "
                  f"事件={len(t['events'])} decision={t['decision_mix']}")
            for a in t["actions"][:4]:
                print(f"      - {a[:110]}")
            if len(t["actions"]) > 4:
                print(f"      … 另 {len(t['actions']) - 4} 条")

        print("\n【样本倾斜】")
        top = tal.most_common(1)[0]
        print(f"  最大 agent「{top[0]}」占 {top[1]} / {len(turns)} = {top[1] * 100 // max(1, len(turns))}% 的 turn，"
              "其余多为个位数——按 Agent 看板会严重向一个项目倾斜。")


# ---------------------------------------------------------------------------
# 审计事件 → 契约字段的映射（代理口径：靠 commandPreview/messageKey 分类）
# ---------------------------------------------------------------------------

def _command_text(ev: dict) -> str:
    """取该事件里的「命令文本」——messageParams.command 优先，其次 commandPreview。"""
    mp = ev.get("messageParams") or {}
    for cand in (mp.get("command"), ev.get("commandPreview")):
        if isinstance(cand, str) and cand.strip():
            return cand
    return ""


def classify_io_kind(ev: dict) -> str:
    """审计事件 → io_kind（8 值封闭词表）。

    优先看命令文本里有没有真工具名/shell 动词（WorkBuddy 会把工具调用原样记进
    commandPreview，如 ``Grep "/x.js"``）；认不出再退回 messageKey。
    """
    by_tool = io_kind_of_text(_command_text(ev), default=None) if _command_text(ev) else None
    if by_tool:
        return by_tool
    if (ev.get("eventType") or "") == "WebFetch":
        return "tool_other"
    mk = (ev.get("messageKey") or "")
    if "readWithRule" in mk:
        return "file_read"
    if "writeWithRule" in mk:
        return "file_write"
    if "delete" in mk.lower():
        return "file_write"
    return "tool_other"


def classify_operation(ev: dict) -> str:
    """审计事件 → operation（READ/WRITE/UPDATE/EXPIRE）。粗映射，真口径在 io_kind。"""
    kind = classify_io_kind(ev)
    if "delete" in (ev.get("messageKey") or "").lower():
        return "EXPIRE"
    if kind == "file_read":
        return "READ"
    if kind == "file_write":
        return "UPDATE"
    return "WRITE"


def action_text(ev: dict) -> str:
    """审计事件 → 人可读的动作短句（进 turn_actions，供前端抽屉显示）。"""
    cmd = _command_text(ev)
    if cmd:
        return "运行命令：" + _first_line(cmd)
    mp = ev.get("messageParams") or {}
    et = ev.get("eventType") or "?"
    if isinstance(mp.get("target"), str) and mp["target"].strip():
        return "抓取网页：" + mp["target"][:160]
    paths = mp.get("paths")
    if isinstance(paths, str) and paths.strip():
        first = _PATH_CUT.split(paths.strip(), 1)[0]
        n = paths.count(",") + 1
        verb = {"file_read": "读文件", "file_write": "改文件"}.get(classify_io_kind(ev), "操作文件")
        if "delete" in et or "delete" in (ev.get("messageKey") or "").lower():
            verb = "删除文件"
        return f"{verb}：{first}" + (f" 等 {n} 个" if n > 1 else "")
    if mp.get("contentPreview") is not None:
        return f"敏感信息防护（{mp.get('direction') or '?'} · {mp.get('itemCount') or 1} 项）"
    if mp.get("count") is not None:
        return f"批量删除（{mp.get('count')} 个）"
    return et


def collapse_by_toolcall(evs: list) -> list:
    """一次工具调用会落下**多条**审计事件（命令沙箱一条 + 文件放行一条），合并成一条。

    不合并就会把「一次 Grep」数成两条动作，``action_count`` 直接翻倍。合并口径：

    - 底子取「带命令文本」的那条——信息最多；都没有就取第一条；
    - 从兄弟事件借 ``paths``；
    - ``decision`` 取最定案的那条。
    """
    groups: dict[str, list] = collections.OrderedDict()
    for e in evs:
        key = e.get("toolCallId") or e.get("id") or f"?{len(groups)}"
        groups.setdefault(key, []).append(e)
    out = []
    for g in groups.values():
        base = dict(next((e for e in g if _command_text(e)), g[0]))
        base["_facet_count"] = len(g)
        merged = dict(base.get("messageParams") or {})
        for sib in g:
            sp = sib.get("messageParams") or {}
            if sp.get("paths") and not merged.get("paths"):
                merged["paths"] = sp["paths"]
            if _DECISION_RANK.get(sib.get("decision"), 0) > _DECISION_RANK.get(base.get("decision"), 0):
                base["decision"] = sib["decision"]
        if merged:
            base["messageParams"] = merged
        out.append(base)
    return out


def project_of(ev: dict, depth: int) -> Optional[str]:
    """从审计事件的文本里提「用户项目」路径，取前 ``depth`` 段（已砍掉文件名尾巴）。**这是猜的**。"""
    mp = ev.get("messageParams") or {}
    parts = [str(v) for v in mp.values() if isinstance(v, str)]
    cp = ev.get("commandPreview")
    if isinstance(cp, str):
        parts.append(cp)
    for blob in parts:
        for m in _PROJ_RE.finditer(blob):
            segs = m.group(1).split("/")
            if segs[0] in _NOISE_SEG or (len(segs) > 1 and segs[1] in _NOISE_SEG):
                continue
            if len(segs) > 1 and _FILE_EXT.search(segs[-1]):
                segs = segs[:-1]
            return "/".join(segs[:depth])
    return None


# ---------------------------------------------------------------------------
# 文本工具
# ---------------------------------------------------------------------------

def _first_line(text: str) -> str:
    line = (text or "").strip().splitlines()[0] if (text or "").strip() else ""
    return line[:160]


def _line(label: str, got: str, note: str = "") -> str:
    return f"  {label:22} {got}{('　—— ' + note) if note else ''}"


# ---------------------------------------------------------------------------
# trace 侧扫描（--with-traces）：为审计流量 toolCallId 的跨源 join 覆盖度
# ---------------------------------------------------------------------------

def _iter_toolcalls(obj):
    if isinstance(obj, dict):
        tc = obj.get("tool_calls")
        if isinstance(tc, list):
            for c in tc:
                if isinstance(c, dict) and isinstance(c.get("id"), str) and c["id"]:
                    fn = (c.get("function") or {}).get("name")
                    yield c["id"], (fn if isinstance(fn, str) else None)
        for v in obj.values():
            yield from _iter_toolcalls(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from _iter_toolcalls(v)


def _parse_blob(blob):
    if not blob:
        return None
    try:
        return json.loads(blob)
    except Exception:
        return None


def _last_user_query(ti: str) -> Optional[str]:
    qs = [q.strip() for q in _USER_QUERY_RE.findall(ti)]
    qs = [q for q in qs if q and _PSEUDO_QUERY not in q]
    return qs[-1] if qs else None


def scan_traces() -> dict:
    """只读扫 ~/.workbuddy/traces/**/trace_*.json（当前活跃会话的轨迹）。"""
    files = sorted(glob.glob(str(TRACE_ROOT / "*" / "trace_*.json")))
    ids: set[str] = set()
    names: set[str] = set()
    sessions: set[str] = set()
    meta: dict[str, dict] = {}
    st = {"files": 0, "bad_files": 0, "gen_spans": 0, "out_ok": 0,
          "in_ok": 0, "in_truncated": 0, "assistant_text": 0, "with_query": 0}
    for f in files:
        st["files"] += 1
        try:
            with open(f, encoding="utf-8") as fh:
                d = json.load(fh)
        except Exception:
            st["bad_files"] += 1
            continue
        sid = (d.get("trace") or {}).get("sessionId")
        if sid:
            sessions.add(sid)
        for s in d.get("spans") or []:
            if s.get("type") == "function" and isinstance(s.get("toolName"), str):
                names.add(s["toolName"])
            if s.get("type") != "generation":
                continue
            st["gen_spans"] += 1
            ti, to = s.get("toolInput"), s.get("toolOutput")
            if isinstance(ti, str) and len(ti) > _TRACE_CAP:
                st["in_truncated"] += 1
            tout = _parse_blob(to)
            if tout is not None:
                st["out_ok"] += 1
                for ch in tout if isinstance(tout, list) else []:
                    msg = ((ch.get("choices") or [{}])[0].get("message") or {})
                    if msg.get("content"):
                        st["assistant_text"] += 1
            tin = _parse_blob(ti)
            if tin is not None:
                st["in_ok"] += 1
                for cid, nm in _iter_toolcalls(tin):
                    ids.add(cid)
                    if nm:
                        names.add(nm)
            q = _last_user_query(ti) if isinstance(ti, str) else None
            if q:
                st["with_query"] += 1
            if tout is not None:
                for cid, nm in _iter_toolcalls(tout):
                    ids.add(cid)
                    if nm:
                        names.add(nm)
                    meta[cid] = {"session": sid, "user": q, "tool": nm}
    return {"ids": ids, "names": names, "sessions": sessions, "meta": meta, **st}


def analyze_traces(tr: dict, events: list, turns: list) -> None:
    audit_ids = {e.get("toolCallId") for e in events if e.get("toolCallId")}
    joined = audit_ids & tr["ids"]
    audit_sess = {e.get("sessionId") for e in events if e.get("sessionId")}
    sess_hit = audit_sess & tr["sessions"]

    def pct(a: int, b: int) -> str:
        return f"{a * 100 // max(1, b)}%"

    ev_hit = sum(1 for e in events if e.get("toolCallId") in tr["ids"])
    t_any = sum(1 for t in turns if any(e.get("toolCallId") in tr["ids"] for e in t["events"]))
    t_all = sum(1 for t in turns if t["events"]
                and all(e.get("toolCallId") in tr["ids"] for e in t["events"]))
    t_user = 0
    for t in turns:
        for e in t["events"]:
            m = tr["meta"].get(e.get("toolCallId") or "")
            if m and m.get("user"):
                t_user += 1
                break

    print("\n" + "=" * 78)
    print("trace 侧扫描（--with-traces）：只读 ~/.workbuddy/traces")
    print("=" * 78)
    print(_line("扫描文件", f"{tr['files']}（坏文件 {tr['bad_files']}）", "trace_*.json = 一个会话段"))
    print(_line("trace session 数", str(len(tr["sessions"])), "只有**当前活跃**会话在盘上"))
    print(_line("generation span", str(tr["gen_spans"]),
                f"toolOutput 可解析 {tr['out_ok']} · toolInput 可解析 {tr['in_ok']} · 截断 {tr['in_truncated']}"))
    print(_line("真工具名种类", str(len(tr["names"])), "function span.toolName / tool_calls.function.name（真名，非猜）"))
    print(_line("抽到用户提问的 span", str(tr["with_query"]), "gen span toolInput 里最后一个 <user_query> 块"))
    print(_line("助手正文条数", str(tr["assistant_text"]), "toolOutput.choices[].message.content"))

    print("\n【跨源 join：键 = toolCallId】")
    print(_line("审计 distinct id", str(len(audit_ids))))
    print(_line("trace distinct id", str(len(tr["ids"]))))
    print(_line("交集", f"{len(joined)}（{pct(len(joined), len(audit_ids))} of 审计）"))
    print(_line("会话对齐", f"{len(sess_hit)} / {len(audit_sess)}",
                f"审计共 {len(audit_sess)} 个 session，trace 只覆盖 {len(tr['sessions'])} 个"))
    print(_line("事件级 join", f"{ev_hit} / {len(events)}（{pct(ev_hit, len(events))}）"))
    print(_line("turn 级 · 至少一条", f"{t_any} / {len(turns)}（{pct(t_any, len(turns))}）"))
    print(_line("turn 级 · 全部命中", f"{t_all} / {len(turns)}（{pct(t_all, len(turns))}）"))
    print(_line("turn 可补 turn_user", f"{t_user} / {len(turns)}（{pct(t_user, len(turns))}）",
                "join 上的 id 在 meta 里带非空 user 的 turn"))
    print("\n【结论】trace 是**另一份来源**，与审计流靠 toolCallId 精确对齐（非模糊匹配），"
          f"\n  但只覆盖当前活跃的 {len(tr['sessions'])} 个 session、约 {pct(len(joined), len(audit_ids))} 的审计工具调用——"
          "\n  故 turn_user / 真工具名只能**部分回填**，不能当整批数据的替代（见 B25.1 / B25.4 修订）。")