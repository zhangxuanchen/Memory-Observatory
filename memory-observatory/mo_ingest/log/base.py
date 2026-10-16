"""旁路日志导入器基类（纯 stdlib）。

模板方法：``run()`` = ``load()`` → ``build_turns()`` → ``to_events()`` → 可选 ``report()`` → ``upload()``。

两个方案的区别只在这里：
- Hook 是「一个事件一个进程、实时上报」；
- Log 是「读一批历史文件、聚合成 turn、批量上报」。

默认**只分析不上报**（要写库显式加 ``--send``）；``--dry-run`` 打印将上报的 OTLP，不发送。
"""
from __future__ import annotations

import os
from typing import Optional

from .. import otlp


class LogImporter:
    PLATFORM = ""
    SOURCE = ""
    DEFAULT_ROOT = ""            # 默认日志根目录（~ 会展开）
    SCOPE = "mo-ingest-log"

    def __init__(
        self,
        *,
        endpoint: Optional[str] = None,
        api_key: Optional[str] = None,
        agent: Optional[str] = None,
        root: Optional[str] = None,
        dry_run: bool = False,
        timeout: float = 10.0,
    ):
        self.endpoint = endpoint or os.environ.get("MO_OTLP_ENDPOINT", otlp.DEFAULT_ENDPOINT)
        self.api_key = api_key if api_key is not None else os.environ.get("MO_API_KEY", "")
        self.agent = agent
        self.root = os.path.expanduser(root) if root else self._default_root()
        self.dry_run = dry_run
        self.timeout = timeout

    def _default_root(self) -> str:
        return os.path.expanduser(self.DEFAULT_ROOT)

    # ------------------------------------------------------------------
    # 模板方法
    # ------------------------------------------------------------------

    def run(self, *, since: Optional[str] = None, send: bool = False, **opts) -> int:
        raw = self.load(since, **opts)
        turns = self.build_turns(raw, **opts)
        events = [ev for t in turns for ev in self.to_events(t)]
        self.report(raw, turns, events, opts)

        if not events:
            print("未找到可导入的事件")
            return 0
        if send and not self.dry_run:
            self.upload(events)
            return len(events)
        if self.dry_run:
            otlp.report_events(events, endpoint=self.endpoint, api_key=self.api_key,
                               dry_run=True, scope_name=self.SCOPE, log=print)
        else:
            print(f"（默认只分析，未写库；要真上报加 --send）共 {len(events)} 个事件"
                  f" / {len({e.agent_id for e in events})} 个 agent")
        return len(events)

    # —— 子类覆写 ——

    def load(self, since: Optional[str], **opts):
        raise NotImplementedError

    def build_turns(self, raw, **opts):
        raise NotImplementedError

    def to_events(self, turn) -> list:
        raise NotImplementedError

    def report(self, raw, turns, events, opts) -> None:
        """可选：打印分析报告（覆盖度、分桶等）。默认不打印。"""

    # —— 上报 ——

    def upload(self, events: list) -> Optional[int]:
        """把一批标准化事件按 agent 分组批量上报（一次 OTLP 请求）。"""
        print(f"\n待上报：{len(events)} 个事件 / {len({e.agent_id for e in events})} 个 agent")
        print(f"上报端点：{self.endpoint}")
        return otlp.report_events(
            events,
            endpoint=self.endpoint,
            api_key=self.api_key,
            timeout=self.timeout,
            scope_name=self.SCOPE,
            log=print,
        )
