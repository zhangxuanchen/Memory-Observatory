"""TraeCode 实时记忆上报 CLI.

让 TraeCode 在工具调用前后调用本脚本，把这次记忆操作上报到
MemoryObservatory Dashboard。零依赖（仅用 urllib，不依赖 mo_sdk）。

用法（一行式）：
    python3 examples/trae_report_event.py \\
        --operation WRITE --layer prompt \\
        --memory-key MEMORY.md --summary "读取项目记忆文件" \\
        --token-count 1200 --session-id my-session

或在 Python 内直接 import 调用：
    from trae_report_event import report_event
    report_event(operation="READ", layer="session", memory_key="...", ...)

环境变量：
    MO_OTLP_ENDPOINT  默认 http://localhost:4318/v1/traces
    MO_AGENT_ID       默认 trae-code
    MO_SESSION_ID     默认 trae-session-{今天日期}
    MO_API_KEY        服务端启用鉴权（MO_API_KEY）时必填，作为 Authorization: Bearer 发送
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone
from urllib import request as urlrequest
from urllib.error import URLError

DEFAULT_ENDPOINT = os.environ.get("MO_OTLP_ENDPOINT", "http://localhost:4318/v1/traces")
DEFAULT_AGENT = os.environ.get("MO_AGENT_ID", "trae-code")
DEFAULT_SESSION = os.environ.get("MO_SESSION_ID", f"trae-session-{datetime.now().strftime('%Y%m%d')}")

# 归一化映射
_OP_NORM = {
    "READ": "READ", "WRITE": "WRITE", "UPDATE": "UPDATE", "EXPIRE": "EXPIRE",
    "RETRIEVE": "READ", "STORE": "WRITE", "FORGET": "EXPIRE", "CONSOLIDATE": "UPDATE",
    "read": "READ", "write": "WRITE", "update": "UPDATE", "expire": "EXPIRE",
    "retrieve": "READ", "store": "WRITE", "forget": "EXPIRE", "consolidate": "UPDATE",
}
_VALID_LAYERS = {"prompt", "session", "skill", "provider"}


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _default_trace_id(session_id: str) -> str:
    """一个 session = 一个 trace（MD5 32 hex，与 Java deterministicTraceId 对齐）。"""
    return hashlib.md5(session_id.encode("utf-8")).hexdigest()


def _build_otlp_payload(*, operation: str, layer: str, memory_key: str,
                       memory_summary: str, token_count: int, latency_ms: float,
                       agent_id: str, session_id: str,
                       trace_id: str | None = None,
                       parent_span_id: str | None = None,
                       metadata: dict | None = None,
                       span_id: str | None = None) -> dict:
    """构造 OTLP/HTTP JSON traces 请求体（一个 span 内嵌一组 memory.* 属性）。

    Trace 契约（input-contracts §6）：
      - trace_id = 32 hex chars；不传时用 session_id 派生（一个 session = 一个 trace）
      - span_id  = 16 hex chars；= event_id（不变式 1）
      - parent_span_id = 父 span_id；Turn Root / 孤立 span 不传
    """
    if trace_id is None or len(trace_id.strip()) == 0:
        trace_id = _default_trace_id(session_id)
    if span_id is None or len(span_id.strip()) == 0:
        span_id = uuid.uuid4().hex[:16]
    else:
        span_id = str(span_id).strip()[:16]  # 对齐 VARCHAR(16)
    trace_id = str(trace_id).strip()[:32]    # 对齐 VARCHAR(32)

    start_ns = int(time.time() * 1e9)
    end_ns = start_ns + int(max(0.0, latency_ms) * 1e6)

    # OTel GenAI 语义约定：标准属性用 gen_ai.*，扩展属性用 memory.* 命名空间
    attrs = {
        "memory.operation": operation,
        "memory.layer": layer,
        "memory.key": memory_key,
        "memory.summary": memory_summary,
        "memory.token_count": int(token_count),
        "memory.latency_ms": float(latency_ms),
        "memory.agent_id": agent_id,
        "memory.session_id": session_id,
        # 冗余属性：非 OTLP 场景直接读 memory.* 也能拿到 trace 标识（见 OtlpParser §6）
        "memory.trace_id": trace_id,
        "gen_ai.system": "trae-code",
    }
    if parent_span_id:
        attrs["memory.parent_span_id"] = str(parent_span_id).strip()[:16]
    # 自定义 metadata（memory.meta.xxx 前缀，OtlpParser.collectMeta 会收）
    if metadata:
        for k, v in metadata.items():
            if k is None:
                continue
            attrs[f"memory.meta.{k}"] = v

    span_obj = {
        "traceId": trace_id,
        "spanId": span_id,
        "name": f"memory.{operation.lower()}",
        "kind": "SPAN_KIND_INTERNAL",
        "startTimeUnixNano": str(start_ns),
        "endTimeUnixNano": str(end_ns),
        "attributes": [
            {"key": k, "value": _value_to_otlp(v)} for k, v in attrs.items()
        ],
    }
    if parent_span_id:
        span_obj["parentSpanId"] = str(parent_span_id).strip()[:16]
    return {
        "resourceSpans": [{
            "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": agent_id}}]},
            "scopeSpans": [{
                "scope": {"name": "mo-trae-reporter"},
                "spans": [span_obj],
            }],
        }]
    }, span_id, trace_id


def _value_to_otlp(v):
    if isinstance(v, bool):
        return {"boolValue": v}
    if isinstance(v, int):
        return {"intValue": str(v)}
    if isinstance(v, float):
        return {"doubleValue": v}
    return {"stringValue": str(v)}


def report_event(*, operation: str, layer: str, memory_key: str,
                 memory_summary: str = "", token_count: int = 0, latency_ms: float = 0.0,
                 agent_id: str = DEFAULT_AGENT, session_id: str = DEFAULT_SESSION,
                 endpoint: str = DEFAULT_ENDPOINT,
                 trace_id: str | None = None,
                 parent_span_id: str | None = None,
                 span_id: str | None = None,
                 metadata: dict | None = None) -> dict:
    """上报一个记忆事件到 MemoryObservatory。失败只打 WARN 不抛。

    返回 dict 含 status/ok，同时含 span_id/trace_id，便于链式调用（把生成的 span_id
    作为下一条事件的 parent_span_id）。
    """
    op = _OP_NORM.get(operation, operation.upper())
    if op not in {"READ", "WRITE", "UPDATE", "EXPIRE"}:
        op = "WRITE"
    layer = layer.lower()
    if layer not in _VALID_LAYERS:
        layer = "session"
    payload, gen_span_id, gen_trace_id = _build_otlp_payload(
        operation=op, layer=layer, memory_key=memory_key,
        memory_summary=memory_summary, token_count=token_count,
        latency_ms=latency_ms, agent_id=agent_id, session_id=session_id,
        trace_id=trace_id, parent_span_id=parent_span_id,
        span_id=span_id, metadata=metadata,
    )
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = "Bearer " + api_key
    req = urlrequest.Request(endpoint, data=data, method="POST", headers=headers)
    # 自签名证书（经 nginx HTTPS 上报）时可用 insecure=True / MO_INSECURE=1 跳过校验
    ctx = ssl.create_default_context()
    if insecure:
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    try:
        with urlrequest.urlopen(req, timeout=2, context=ctx) as resp:
            status = resp.status
            body = resp.read().decode("utf-8", errors="replace")[:200]
            print(f"[mo-report] OK status={status} op={op} layer={layer} "
                  f"trace={gen_trace_id[:8]}… span={gen_span_id[:8]}… "
                  f"key={memory_key[:40]}", file=sys.stderr)
            return {"status": status, "ok": True, "body": body,
                    "spanId": gen_span_id, "traceId": gen_trace_id}
    except URLError as e:
        print(f"[mo-report] WARN 上报失败（不影响主流程）: {e}", file=sys.stderr)
        return {"status": 0, "ok": False, "error": str(e),
                "spanId": gen_span_id, "traceId": gen_trace_id}
    except Exception as e:
        print(f"[mo-report] WARN 异常（不影响主流程）: {e}", file=sys.stderr)
        return {"status": 0, "ok": False, "error": str(e),
                "spanId": gen_span_id, "traceId": gen_trace_id}


def main() -> None:
    p = argparse.ArgumentParser(description="TraeCode 实时记忆上报到 MemoryObservatory")
    p.add_argument("--operation", required=True, help="READ/WRITE/UPDATE/EXPIRE 或 retrieve/store/forget/consolidate")
    p.add_argument("--layer", required=True, help="prompt/session/skill/provider")
    p.add_argument("--memory-key", required=True, help="记忆键（文件路径/工具名/会话 ID）")
    p.add_argument("--summary", default="", help="事件摘要")
    p.add_argument("--token-count", type=int, default=0, help="Token 数")
    p.add_argument("--latency-ms", type=float, default=0.0, help="延迟毫秒")
    p.add_argument("--agent-id", default=DEFAULT_AGENT, help=f"Agent ID（默认 {DEFAULT_AGENT}）")
    p.add_argument("--session-id", default=DEFAULT_SESSION, help=f"Session ID（默认 {DEFAULT_SESSION}）")
    p.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help=f"OTLP 端点（默认 {DEFAULT_ENDPOINT}）")
    # Trace 契约扩展参数
    p.add_argument("--trace-id", default=None,
                   help="Trace ID（32 hex）。默认按 session_id 派生（一个 session = 一个 trace）")
    p.add_argument("--parent-span-id", default=None,
                   help="父 Span ID（16 hex）。不传 = Turn Root / 孤立 span")
    p.add_argument("--span-id", default=None,
                   help="自定义 Span ID（16 hex）。不传自动生成（= 写入 DB 的 event_id）")
    p.add_argument("--meta", action="append", default=[],
                   help="自定义 metadata，格式 k=v。可多次传入，例：--meta turn_message_id=u_123 --meta items_returned=8")
    args = p.parse_args()

    # --meta k=v 解析为 dict
    meta: dict = {}
    for kv in args.meta:
        if "=" in kv:
            k, v = kv.split("=", 1)
            meta[k.strip()] = v.strip()

    result = report_event(
        operation=args.operation, layer=args.layer, memory_key=args.memory_key,
        memory_summary=args.summary, token_count=args.token_count,
        latency_ms=args.latency_ms, agent_id=args.agent_id,
        session_id=args.session_id, endpoint=args.endpoint,
        trace_id=args.trace_id, parent_span_id=args.parent_span_id,
        span_id=args.span_id, metadata=meta or None,
        api_key=args.api_key, insecure=args.insecure,
    )
    # 输出 JSON 给调用方（含 spanId / traceId，便于后续作为 parent）
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
