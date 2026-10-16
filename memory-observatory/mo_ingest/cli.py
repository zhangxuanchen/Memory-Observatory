"""``mo_ingest`` 命令行入口。

用法：

    python -m mo_ingest list
    echo '{...}' | MO_HOOK_DRY_RUN=1 python -m mo_ingest --platform qoder --mode hook
    python -m mo_ingest --platform trae --mode log --since 20260801 --send
    python -m mo_ingest --platform workbuddy --mode log            # 默认只分析

``--platform`` 必填、``--mode`` 可省（按平台默认）；接入时按平台可选。
"""
from __future__ import annotations

import argparse
import os
import sys
from typing import Optional

from . import get_adapter
from .registry import PLATFORMS, get_platform


def _cmd_list() -> int:
    print("可用平台：")
    for pid, p in PLATFORMS.items():
        extra = []
        if p.default_root:
            extra.append(f"默认根目录 {p.default_root}")
        if p.state_dir:
            extra.append(f"状态目录 {p.state_dir}")
        suffix = ("  " + "；".join(extra)) if extra else ""
        # 一平台两模式时 source 也有两个，避免只显示一个造成误解
        src = p.source
        if p.hook_source and "hook" in p.modes:
            src = f"{p.source}(log)|{p.hook_source}(hook)"
        print(f"  {pid:10} {p.display:10} 模式={'/'.join(p.modes):9} source={src}{suffix}")
    print("\n用法示例：")
    print("  echo '{...}' | MO_HOOK_DRY_RUN=1 python -m mo_ingest --platform qoder --mode hook")
    print("  echo '{...}' | MO_HOOK_DRY_RUN=1 python -m mo_ingest --platform trae --mode hook")
    print("  python -m mo_ingest --platform trae --mode log --since 20260801 --send")
    print("  python -m mo_ingest --platform workbuddy --mode log")
    return 0


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="python -m mo_ingest",
        description="Memory Observatory 统一接入层（hook 实时 / log 旁路批量）",
    )
    p.add_argument("--platform", help=f"平台 id：{', '.join(PLATFORMS)}（用 `list` 查看）")
    p.add_argument("--mode", default=None, help="接入方案 hook|log（缺省取平台默认）")
    p.add_argument("--path", "--root", dest="root", default=None,
                   help="日志根目录（log 模式）或状态目录（hook 模式）；缺省用平台默认")
    p.add_argument("--since", default=None, help="只导入此日期之后（YYYYMMDD，log 模式）")
    p.add_argument("--agent", default=None, help="Agent ID（缺省按平台/项目自动生成）")
    p.add_argument("--project", default=None, help="项目名子串；默认当前项目；'all' 全部（trae）")
    p.add_argument("--depth", type=int, default=2, choices=(1, 2),
                   help="agent_id 取路径前几段（workbuddy，默认 2）")
    p.add_argument("--with-traces", action="store_true",
                   help="追加扫 traces 量跨源 join 覆盖度（workbuddy，只读）")
    p.add_argument("--endpoint", default=None,
                   help="OTLP 端点（缺省 http://localhost:4318/v1/traces）")
    p.add_argument("--api-key", default=None,
                   help="接口鉴权密钥；缺省取环境变量 MO_API_KEY")
    p.add_argument("--dry-run", action="store_true", help="只打印将上报的 OTLP，不发送")
    p.add_argument("--send", action="store_true", help="真上报（log 模式默认只分析）")
    return p


def main(argv: Optional[list] = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "list":
        return _cmd_list()

    args = _build_parser().parse_args(argv)
    if not args.platform:
        _build_parser().print_help()
        return 2
    try:
        platform = get_platform(args.platform)
    except KeyError:
        print(f"未知平台：{args.platform}；可选：{', '.join(PLATFORMS)}")
        return 2
    mode = args.mode or platform.modes[0]
    try:
        adapter_cls = get_adapter(platform.id, mode)
    except ValueError as exc:
        print(str(exc))
        return 2

    api_key = args.api_key if args.api_key is not None else os.environ.get("MO_API_KEY", "")

    if mode == "hook":
        # dry_run 未显式开启时交回环境变量（MO_HOOK_DRY_RUN）判断
        adapter = adapter_cls(
            endpoint=args.endpoint, api_key=api_key, agent_id=args.agent,
            dry_run=True if args.dry_run else None, state_dir=args.root,
        )
        adapter.run()
        return 0

    adapter = adapter_cls(
        endpoint=args.endpoint, api_key=api_key, agent=args.agent,
        root=args.root, dry_run=args.dry_run,
    )
    opts = {"depth": args.depth}
    if args.project is not None:
        opts["project"] = args.project
    if args.with_traces:
        opts["with_traces"] = True
    adapter.run(since=args.since, send=args.send, **opts)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
