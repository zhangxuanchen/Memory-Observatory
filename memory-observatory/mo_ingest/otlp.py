"""OTLP/HTTP JSON 上报（纯 stdlib，零依赖）。

把 :class:`mo_ingest.contract.MemoryEvent` 转成 OTLP span，POST 到 mo-server 的
``/v1/traces``。属性命名对齐 ``mo_sdk/otel_exporter.py``（Java 侧 ``OtlpParser`` 逐字读），
并额外补两处：``memory.trace_id``（一个 session = 一个 trace）与 ``gen_ai.system``。

为什么不用 ``mo_sdk.OTelSpanExporter``：Hook 端要能整目录拷到任意机器跑，必须零依赖；
且 mo_sdk 用「整批一个随机 trace_id」，与 Java ``deterministicTraceId`` / 本项目
「一会话一 trace」的口径不符。此处统一按 session 派生 trace，重放结果稳定。

旁路纪律：任何异常只 WARN 不抛，绝不影响被测 Agent 主流程。
"""
from __future__ import annotations

import json
import time
from typing import Callable, Iterable, Optional
from urllib import request as urlrequest

from .contract import MemoryEvent, new_span_id, normalize_operation, trace_id_for

DEFAULT_ENDPOINT = "http://localhost:4318/v1/traces"

# 归一操作 → GenAI 语义约定值（标准覆盖 add/update，retrieve/delete 为扩展）
GENAI_OP = {
    "WRITE": "add",
    "UPDATE": "update",
    "READ": "retrieve",
    "EXPIRE": "delete",
}


def _value_to_otlp(v):
    """Python 值 → OTLP 属性值联合类型。"""
    if isinstance(v, bool):
        return {"boolValue": v}
    if isinstance(v, int):
        return {"intValue": str(v)}
    if isinstance(v, float):
        return {"doubleValue": v}
    return {"stringValue": str(v)}


def _now_nano(ts: float) -> str:
    return str(int(float(ts) * 1_000_000_000))


def event_to_span(ev: MemoryEvent) -> dict:
    """标准化事件 → OTLP span。"""
    op_norm = normalize_operation(ev.operation)
    genai_op = GENAI_OP.get(op_norm, op_norm.lower())
    trace_id = trace_id_for(ev.session_id)
    ts = ev.timestamp or time.time()

    meta = ev.metadata or {}
    attrs = {
        # 标准属性（GenAI 语义约定）
        "memory.operation": genai_op,
        "memory.size_delta": int(ev.token_count or 0),
        # 扩展属性（MO 专属）
        "memory.agent_id": ev.agent_id,
        "memory.session_id": ev.session_id,
        "memory.op_normalized": op_norm,      # Java 侧优先读此字段
        "memory.layer": ev.layer,
        "memory.key": ev.memory_key,
        "memory.summary": (ev.memory_summary or "")[:200],
        "memory.token_count": int(ev.token_count or 0),
        "memory.latency_ms": float(ev.latency_ms or 0.0),
        "memory.trace_id": trace_id,
    }
    src = meta.get("source")
    if src:
        attrs["gen_ai.system"] = src
    # metadata 展开为扁平属性，服务端去掉 memory.meta. 前缀落进 metadata jsonb
    for k, v in meta.items():
        if v is None or v == "":
            continue
        attrs[f"memory.meta.{k}"] = v

    return {
        "traceId": trace_id,
        "spanId": new_span_id(),
        "name": f"memory.{genai_op}",
        "kind": 1,  # SPAN_KIND_INTERNAL
        "startTimeUnixNano": _now_nano(ts),
        "endTimeUnixNano": _now_nano(ts),
        "attributes": [{"key": k, "value": _value_to_otlp(v)} for k, v in attrs.items()],
        "status": {"code": 1},  # STATUS_CODE_OK
    }


def _resource_span(agent_id: str, spans: list, scope_name: str) -> dict:
    return {
        "resource": {"attributes": [
            {"key": "service.name", "value": {"stringValue": agent_id}},
            {"key": "agent.id", "value": {"stringValue": agent_id}},
        ]},
        "scopeSpans": [{
            "scope": {"name": scope_name, "version": "0.1.0"},
            "spans": spans,
        }],
    }


def build_payload(events: Iterable[MemoryEvent], scope_name: str = "mo-ingest") -> dict:
    """按 agent_id 分组构造 OTLP 请求体（一个 agent 一个 resourceSpan）。"""
    by_agent: dict[str, list] = {}
    for ev in events:
        aid = ev.agent_id or "unknown"
        by_agent.setdefault(aid, []).append(event_to_span(ev))
    return {
        "resourceSpans": [
            _resource_span(aid, spans, scope_name) for aid, spans in by_agent.items()
        ]
    }


def post(
    endpoint: str,
    payload: dict,
    *,
    api_key: str = "",
    timeout: float = 3.0,
    dry_run: bool = False,
    log: Callable[[str], None] = print,
) -> Optional[int]:
    """POST 一个 OTLP payload。返回 HTTP 状态码；dry_run 或失败返回 None/0，绝不抛。"""
    if dry_run:
        log("DRY_RUN 不上报，payload=" + json.dumps(payload, ensure_ascii=False))
        return None
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = "Bearer " + api_key
    try:
        req = urlrequest.Request(endpoint, data=data, method="POST", headers=headers)
        with urlrequest.urlopen(req, timeout=timeout) as resp:
            log(f"OK status={resp.status}")
            return resp.status
    except Exception as exc:  # noqa: BLE001 - 旁路：上报失败绝不抛
        log(f"WARN 上报失败（不影响主流程）: {exc}")
        return 0


def report_events(
    events: Iterable[MemoryEvent],
    *,
    endpoint: str = DEFAULT_ENDPOINT,
    api_key: str = "",
    timeout: float = 3.0,
    dry_run: bool = False,
    scope_name: str = "mo-ingest",
    log: Callable[[str], None] = print,
) -> Optional[int]:
    """把一批标准化事件打成一次 OTLP 请求上报（按 agent 分组）。"""
    events = [e for e in events if e is not None]
    if not events:
        log("无事件可上报")
        return None
    return post(
        endpoint,
        build_payload(events, scope_name),
        api_key=api_key,
        timeout=timeout,
        dry_run=dry_run,
        log=log,
    )
