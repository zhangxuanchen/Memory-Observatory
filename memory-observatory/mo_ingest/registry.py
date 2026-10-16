"""平台注册表（纯 stdlib）。

每个平台一条元数据：接入方案（hook/log）、写入的 source 值、默认路径 / 状态目录。
``source`` 沿用旧脚本里的原值（``qoder_hook`` / ``trae_importer`` / ``workbuddy_importer``），
避免既有数据与前端漂移；服务端把它归类到哪个家族，由 ``event_source_family`` 表决定。
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class Platform:
    id: str
    display: str
    modes: tuple            # 支持的模式，首个为默认：("hook",) / ("log",) / ("log", "hook")
    source: str             # 默认模式写入 memory.meta.source 的值
    default_root: Optional[str] = None   # log 模式的默认日志根目录
    state_dir: Optional[str] = None      # hook 模式的跨进程状态目录
    agent_default: str = ""              # hook 模式的默认 agent_id
    hook_source: Optional[str] = None    # hook 模式的 source；仅一平台两模式时才有别于 source


PLATFORMS: dict[str, Platform] = {
    "qoder": Platform(
        id="qoder",
        display="Qoder",
        modes=("hook",),
        source="qoder_hook",
        state_dir="~/.qoder/hooks/.mo_state",
        agent_default="qoder",
    ),
    "trae": Platform(
        id="trae",
        display="Trae",
        # log 在首位：默认模式不变（hook 需显式 --mode hook，否则会等 stdin 阻塞）
        modes=("log", "hook"),
        source="trae_importer",
        hook_source="trae_hook",
        default_root="~/.trae-cn/memory/projects",
        state_dir="~/.trae-cn/hooks/.mo_state",
        agent_default="trae",
    ),
    "workbuddy": Platform(
        id="workbuddy",
        display="WorkBuddy",
        modes=("log",),
        source="workbuddy_importer",
        default_root="~/.workbuddy/audit-log",
    ),
}


def get_platform(platform_id: str) -> Platform:
    """按 id 取平台元数据；不存在抛 KeyError（调用方负责给出可选清单）。"""
    key = (platform_id or "").strip().lower()
    if key not in PLATFORMS:
        raise KeyError(platform_id)
    return PLATFORMS[key]


def expand(path: Optional[str]) -> Optional[str]:
    """展开 ~ 与环境变量。"""
    return os.path.expanduser(path) if path else None
