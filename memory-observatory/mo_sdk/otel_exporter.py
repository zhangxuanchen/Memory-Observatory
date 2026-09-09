"""
Memory Observatory SDK - OpenTelemetry Exporter
================================================

将 MemoryEvent 转换为 OTLP/HTTP JSON 上报到 Java 服务端。
属性命名对齐 OpenTelemetry GenAI 语义约定（OTEP 4959）：
标准已定义的属性用标准名，标准未覆盖的扩展属性用 memory.* 命名空间。

设计要点（见 MVP 设计文档 §3 与 §9 修订 1）：
- 操作归一化：store/retrieve/forget/consolidate → READ/WRITE/UPDATE/EXPIRE
- 旁路容错：任何异常只记 WARN 不抛出，不影响 Agent 主流程
- 零外部依赖：优先 httpx，回退 urllib
"""

from __future__ import annotations

import json
import time
import uuid
import logging
from typing import Optional

from .exporter import BaseExporter

logger = logging.getLogger(__name__)


# 操作归一化映射：Python 原始操作 → P0 四种语义
_OP_MAP = {
    "store": "WRITE",
    "retrieve": "READ",
    "forget": "EXPIRE",
    "consolidate": "UPDATE",
}


def _normalize_op(event_dict: dict) -> str:
    """根据 operation 和 metadata.is_update 归一化为 READ/WRITE/UPDATE/EXPIRE。

    store 且 metadata.is_update=True → UPDATE；store 否则 → WRITE；
    其余按 _OP_MAP 映射。
    """
    raw = (event_dict.get("operation") or "").lower()
    if raw == "store":
        meta = event_dict.get("metadata") or {}
        if meta.get("is_update"):
            return "UPDATE"
        return "WRITE"
    return _OP_MAP.get(raw, raw.upper())


def _trace_id() -> str:
    """OTLP traceId：16 字节 hex（32 字符）。"""
    return uuid.uuid4().hex


def _span_id() -> str:
    """OTLP spanId：8 字节 hex（16 字符）。"""
    return uuid.uuid4().hex[:16]


def _now_nano(ts: float) -> str:
    """Unix 时间戳 → 纳秒字符串（OTLP 数字用字符串避免精度丢失）。"""
    return str(int(float(ts) * 1_000_000_000))


# —— OTLP JSON 属性值构造辅助 ——
def _attr_str(key: str, val) -> dict:
    return {"key": key, "value": {"stringValue": str(val)}}

def _attr_int(key: str, val) -> dict:
    return {"key": key, "value": {"intValue": str(int(val))}}

def _attr_float(key: str, val) -> dict:
    return {"key": key, "value": {"doubleValue": str(float(val))}}

def _attr_bool(key: str, val) -> dict:
    return {"key": key, "value": {"boolValue": bool(val)}}


# GenAI 语义约定 memory.operation 标准值映射
# 标准覆盖 add/update/compact；retrieve/delete 为扩展值
_GENAI_OP = {
    "WRITE": "add",
    "UPDATE": "update",
    "READ": "retrieve",     # 扩展（GenAI 约定未覆盖检索）
    "EXPIRE": "delete",     # 扩展（GenAI 约定未覆盖删除）
}


class OTelSpanExporter(BaseExporter):
    """把 MemoryEvent 转 OTel Span，以 OTLP/HTTP JSON 上报。

    属性策略（修订 1）：
      标准属性（GenAI 语义约定 OTEP 4959）：
        - memory.operation（值：add/update/compact 等）
        - memory.size_delta（Token 增量）
      扩展属性（MO 专属，memory.* 命名空间）：
        - memory.agent_id / memory.session_id
        - memory.op_normalized（READ/WRITE/UPDATE/EXPIRE，Java 侧优先读此字段）
        - memory.layer / memory.key / memory.summary
        - memory.token_count / memory.latency_ms

    继承 BaseExporter，沿用 export(agent_id, events, snapshots, metrics) 签名。
    """

    def __init__(
        self,
        endpoint: str = "http://localhost:4318/v1/traces",
        service_name: str = "mo-agent",
        timeout: float = 10.0,
        api_key: Optional[str] = None,
    ):
        self.endpoint = endpoint
        self.service_name = service_name
        self.timeout = timeout
        self.api_key = api_key
        # 检测 httpx 可用性
        try:
            import httpx  # noqa: F401
            self._has_httpx = True
        except ImportError:
            self._has_httpx = False

    def export(self, *, agent_id: str, events: list, snapshots: list, metrics: dict) -> str:
        """把 events/snapshots 转 OTLP JSON 上报。旁路容错，不抛异常。"""
        if not events and not snapshots:
            return "otel:empty"
        try:
            payload = self._build_otlp(agent_id, events, snapshots)
            status = self._post_json(payload)
            return f"otel:status={status}:events={len(events)}:snaps={len(snapshots)}"
        except Exception as exc:
            # 旁路容错（修订 2）：只记 WARN，不影响 Agent 主流程
            logger.warning("OTelSpanExporter 上报失败: %s: %s", type(exc).__name__, exc)
            return f"otel:error:{type(exc).__name__}"

    # ------------------------------------------------------------------
    # OTLP JSON 构造
    # ------------------------------------------------------------------

    def _build_otlp(self, agent_id: str, events: list, snapshots: list) -> dict:
        """构造 OTLP/HTTP JSON traces 请求体。"""
        trace_id = _trace_id()
        spans = [self._event_to_span(trace_id, ev) for ev in events]
        # 快照作为独立 span 上报，每个快照一个 span
        for s in snapshots:
            spans.append(self._snapshot_to_span(trace_id, s))

        return {
            "resourceSpans": [{
                "resource": {
                    "attributes": [
                        _attr_str("service.name", self.service_name),
                        _attr_str("agent.id", agent_id),
                    ]
                },
                "scopeSpans": [{
                    "scope": {"name": "memory-observatory", "version": "0.1.0"},
                    "spans": spans,
                }],
            }],
        }

    def _event_to_span(self, trace_id: str, ev: dict) -> dict:
        """MemoryEvent → OTLP span。属性对齐 GenAI 语义约定。"""
        op_normalized = _normalize_op(ev)
        genai_op = _GENAI_OP.get(op_normalized, op_normalized.lower())
        ts = ev.get("timestamp") or time.time()

        attrs = [
            # —— 标准属性（GenAI 语义约定 OTEP 4959）——
            _attr_str("memory.operation", genai_op),
            _attr_int("memory.size_delta", ev.get("token_count", 0)),
            # —— 扩展属性（MO 专属）——
            _attr_str("memory.agent_id", ev.get("agent_id", "")),
            _attr_str("memory.session_id", ev.get("session_id", "")),
            _attr_str("memory.op_normalized", op_normalized),  # Java 侧优先读
            _attr_str("memory.layer", ev.get("layer", "")),
            _attr_str("memory.key", ev.get("memory_key", "")),
            _attr_str("memory.summary", (ev.get("memory_summary") or "")[:200]),
            _attr_int("memory.token_count", ev.get("token_count", 0)),
            _attr_float("memory.latency_ms", ev.get("latency_ms", 0.0)),
        ]
        # 元数据展开为扁平属性（白名单字段保留类型，其他全部转字符串）
        meta = ev.get("metadata") or {}
        for k, v in meta.items():
            if v is None or v == "":
                continue
            if isinstance(v, bool):
                attrs.append(_attr_bool(f"memory.meta.{k}", v))
            elif isinstance(v, int):
                attrs.append(_attr_int(f"memory.meta.{k}", v))
            elif isinstance(v, float):
                attrs.append(_attr_float(f"memory.meta.{k}", v))
            else:
                attrs.append(_attr_str(f"memory.meta.{k}", str(v)))

        return {
            "traceId": trace_id,
            "spanId": _span_id(),
            "name": f"memory.{genai_op}",
            "kind": 1,  # SPAN_KIND_INTERNAL
            "startTimeUnixNano": _now_nano(ts),
            "endTimeUnixNano": _now_nano(ts),
            "attributes": attrs,
            "status": {"code": 1},  # STATUS_CODE_OK
        }

    def _snapshot_to_span(self, trace_id: str, s: dict) -> dict:
        """ContextSnapshot → OTLP span（五区 Token 预算）。"""
        ts = s.get("timestamp") or time.time()
        attrs = [
            _attr_str("memory.snapshot", "true"),
            _attr_str("memory.agent_id", s.get("agent_id", "")),
            _attr_str("memory.session_id", s.get("session_id", "")),
            _attr_int("memory.total_tokens", s.get("total_tokens", 0)),
            _attr_int("memory.system_tokens", s.get("system_tokens", 0)),
            _attr_int("memory.task_tokens", s.get("task_tokens", 0)),
            _attr_int("memory.memory_tokens", s.get("memory_tokens", 0)),
            _attr_int("memory.tool_history_tokens", s.get("tool_history_tokens", 0)),
            _attr_int("memory.free_tokens", s.get("free_tokens", 0)),
            _attr_int("memory.compression_count", s.get("compression_count", 0)),
            _attr_float("memory.last_compression_ratio", s.get("last_compression_ratio", 0.0)),
        ]
        return {
            "traceId": trace_id,
            "spanId": _span_id(),
            "name": "memory.snapshot",
            "kind": 1,
            "startTimeUnixNano": _now_nano(ts),
            "endTimeUnixNano": _now_nano(ts),
            "attributes": attrs,
            "status": {"code": 1},
        }

    # ------------------------------------------------------------------
    # HTTP 上报
    # ------------------------------------------------------------------

    def _post_json(self, payload: dict) -> int:
        if self._has_httpx:
            return self._post_httpx(payload)
        return self._post_urllib(payload)

    def _post_httpx(self, payload: dict) -> int:
        import httpx
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        try:
            resp = httpx.post(self.endpoint, json=payload, headers=headers, timeout=self.timeout)
            return resp.status_code
        except Exception as exc:
            logger.warning("OTelSpanExporter httpx 错误: %s", exc)
            return 0

    def _post_urllib(self, payload: dict) -> int:
        import urllib.request
        import urllib.error
        data = json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        req = urllib.request.Request(self.endpoint, data=data, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.status
        except urllib.error.HTTPError as exc:
            logger.warning("OTelSpanExporter urllib HTTP 错误: %s", exc)
            return exc.code
        except Exception as exc:
            logger.warning("OTelSpanExporter urllib 错误: %s", exc)
            return 0
