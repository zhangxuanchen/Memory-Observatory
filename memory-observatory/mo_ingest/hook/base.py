"""Hook 适配器基类（纯 stdlib）。

模板方法：``run()`` 读 stdin → 解析 JSON → 交给子类的 ``handle()``。
旁路纪律（对齐 mo_sdk/otel_exporter.py）：

1. **绝不阻断**：任何异常都吞掉，永远 exit 0；诊断只走 stderr。
2. **stdout 恒为 ``{}``**：宿主会解析 stdout 的 JSON 当 HookOutput，乱打文本会被误当决策。
3. **零依赖**：只 stdlib，整目录拷到任意机器即可跑。

跨进程 turn 状态：Hook 每次事件一个独立进程，靠 ``state_dir`` 下的小 JSON 文件把
「用户提问 → 工具调用累计 → Stop 汇总」串成同一个 turn。并发写不做锁，最坏丢个别动作。
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import time
from pathlib import Path
from typing import Optional

from .. import otlp
from ..contract import MemoryEvent, short
from ..registry import expand


def summarize_tool(tool_name: str, tool_input) -> str:
    """把工具调用压成一行人类可读摘要，供 turn_actions / action_full 展示。

    各平台适配器共用：先按常见参数键取最短有意义的那个，取不到再退化成整段 JSON。
    """
    name = tool_name or "tool"
    if isinstance(tool_input, dict):
        for k in ("command", "file_path", "path", "pattern", "query", "url", "description"):
            v = tool_input.get(k)
            if isinstance(v, str) and v.strip():
                return f"{name}: {short(v, 120)}"
        return f"{name}: {short(json.dumps(tool_input, ensure_ascii=False), 120)}"
    if isinstance(tool_input, str) and tool_input.strip():
        return f"{name}: {short(tool_input, 120)}"
    return name


class HookAdapter:
    # —— 子类覆写 ——
    PLATFORM = ""                                  # 平台 id（日志前缀）
    SOURCE = ""                                    # 写入 memory.meta.source 的值
    HANDLED: frozenset = frozenset()               # 关心的事件名，其余静默忽略
    SCOPE = "mo-ingest-hook"
    DEFAULT_AGENT = ""
    STATE_DIR: Optional[str] = None                # 跨进程状态目录（~ 会展开）
    STATE_TTL_SECONDS = 6 * 3600

    def __init__(
        self,
        *,
        endpoint: Optional[str] = None,
        api_key: Optional[str] = None,
        agent_id: Optional[str] = None,
        dry_run: Optional[bool] = None,
        state_dir: Optional[str] = None,
    ):
        self.endpoint = endpoint or os.environ.get("MO_OTLP_ENDPOINT", otlp.DEFAULT_ENDPOINT)
        self.api_key = api_key if api_key is not None else os.environ.get("MO_API_KEY", "")
        self.agent_id = agent_id or os.environ.get("MO_AGENT_ID", self.DEFAULT_AGENT)
        self.dry_run = bool(dry_run) if dry_run is not None else os.environ.get("MO_HOOK_DRY_RUN", "") == "1"
        raw_dir = state_dir or self.STATE_DIR
        self.state_dir = Path(expand(raw_dir)) if raw_dir else None

    # ------------------------------------------------------------------
    # 模板方法
    # ------------------------------------------------------------------

    def run(self) -> None:
        """读 stdin → 分发。无论发生什么都 exit 0，stdout 只留 ``{}``。"""
        try:
            raw = sys.stdin.read()
            event = json.loads(raw) if raw.strip() else {}
            name = str(event.get("hook_event_name") or "")
            if self.dry_run:
                self.log("输入事件=" + json.dumps(event, ensure_ascii=False))
            if name in self.HANDLED:
                self.handle(event, name)
            else:
                self.log(f"忽略未处理事件: {name or '(空)'}")
        except Exception as exc:  # noqa: BLE001 - 旁路：绝不阻断
            self.log(f"WARN 适配器异常（已吞，不阻断）: {exc}")
        finally:
            # 关键：stdout 只能是合法空 HookOutput
            sys.stdout.write("{}")

    def handle(self, event: dict, name: str) -> None:
        """子类实现：按事件名分发。"""
        raise NotImplementedError

    # ------------------------------------------------------------------
    # 子类可用工具
    # ------------------------------------------------------------------

    def log(self, msg: str) -> None:
        print(f"[{self.PLATFORM}-hook] {msg}", file=sys.stderr)

    def emit(self, ev: MemoryEvent) -> None:
        """上报一个标准化事件（旁路容错）。"""
        otlp.report_events(
            [ev],
            endpoint=self.endpoint,
            api_key=self.api_key,
            timeout=3.0,
            dry_run=self.dry_run,
            scope_name=self.SCOPE,
            log=self.log,
        )

    def meta(self, turn_key: str, **extra) -> dict:
        """公共 metadata：来源 + turn 分组键；子事件各自覆盖 io_kind。"""
        m = {"source": self.SOURCE, "turn_message_id": turn_key}
        m.update(extra)
        return m

    # —— 跨进程 turn 状态 ——

    def _state_path(self, turn_key: str) -> Path:
        return self.state_dir / (hashlib.md5(turn_key.encode("utf-8")).hexdigest() + ".json")

    def load_state(self, turn_key: str) -> dict:
        try:
            p = self._state_path(turn_key)
            if p.exists():
                return json.loads(p.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001 - 旁路：读不到当没有
            self.log(f"state load 失败（忽略）: {exc}")
        return {}

    def save_state(self, turn_key: str, state: dict) -> None:
        try:
            self.state_dir.mkdir(parents=True, exist_ok=True)
            self._state_path(turn_key).write_text(
                json.dumps(state, ensure_ascii=False), encoding="utf-8")
        except Exception as exc:  # noqa: BLE001
            self.log(f"state save 失败（忽略）: {exc}")

    def drop_state(self, turn_key: str) -> None:
        try:
            self._state_path(turn_key).unlink(missing_ok=True)
        except Exception as exc:  # noqa: BLE001
            self.log(f"state drop 失败（忽略）: {exc}")

    def sweep_stale(self) -> None:
        """清理超过 TTL 的残留状态（会话崩了没收到 Stop）。"""
        try:
            if not self.state_dir or not self.state_dir.exists():
                return
            now = time.time()
            for f in self.state_dir.glob("*.json"):
                if now - f.stat().st_mtime > self.STATE_TTL_SECONDS:
                    f.unlink(missing_ok=True)
        except Exception as exc:  # noqa: BLE001
            self.log(f"state sweep 失败（忽略）: {exc}")
