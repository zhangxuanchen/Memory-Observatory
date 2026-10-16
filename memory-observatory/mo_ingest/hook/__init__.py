"""Hook 接入方案：宿主在关键节点把事件 JSON 从 stdin 喂进来，实时旁路上报。"""
from .base import HookAdapter
from .qoder import QoderHookAdapter

__all__ = ["HookAdapter", "QoderHookAdapter"]
