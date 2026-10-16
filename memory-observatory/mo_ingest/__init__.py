"""Memory Observatory · 统一接入层（``mo_ingest``）。

一个接入层，两种接入方案：

- **hook**（实时旁路）：宿主在关键节点把事件 JSON 从 stdin 喂进来，逐事件上报。
- **log**（旁路日志批量）：读历史日志文件，聚合成 turn 后批量上报。

两种方案都把各自的原始数据归一成 :class:`mo_ingest.contract.MemoryEvent` 这一套**标准化
字段**，再由 :mod:`mo_ingest.otlp` 统一上报——「从接入层出去的都是标准化的可使用数据」。

接入时按平台可选（见 :data:`mo_ingest.registry.PLATFORMS`）。整层刻意**零依赖**（纯 stdlib）：
Hook 端要能整目录拷到任意机器跑。

命令行：``python -m mo_ingest --help`` / ``python -m mo_ingest list``。
"""
from __future__ import annotations

import importlib
from typing import Optional

from .contract import MemoryEvent
from .registry import PLATFORMS, Platform, get_platform

__all__ = ["PLATFORMS", "Platform", "get_platform", "get_adapter", "MemoryEvent"]

# 平台 → (模块, 类名)。懒加载，避免 import 时拉起全部适配器。
_HOOK_ADAPTERS = {
    "qoder": ("mo_ingest.hook.qoder", "QoderHookAdapter"),
    "trae": ("mo_ingest.hook.trae", "TraeHookAdapter"),
}
_LOG_ADAPTERS = {
    "trae": ("mo_ingest.log.trae", "TraeLogImporter"),
    "workbuddy": ("mo_ingest.log.workbuddy", "WorkBuddyLogImporter"),
}


def get_adapter(platform: str, mode: Optional[str] = None):
    """按平台（+可选模式）取适配器类。模式缺省取平台的首个支持模式。"""
    p = get_platform(platform)
    m = mode or p.modes[0]
    if m not in p.modes:
        raise ValueError(f"平台 {p.id} 不支持模式 {m}（支持：{'/'.join(p.modes)}）")
    table = _HOOK_ADAPTERS if m == "hook" else _LOG_ADAPTERS
    if p.id not in table:
        raise ValueError(f"平台 {p.id} 未注册 {m} 适配器")
    mod_name, cls_name = table[p.id]
    return getattr(importlib.import_module(mod_name), cls_name)
