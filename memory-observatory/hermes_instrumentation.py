#!/usr/bin/env python3
"""
Hermes Agent + Memory Observatory 插桩示例
==========================================

展示如何在 hermes-agent（https://github.com/NousResearch/hermes-agent）
中集成 mo-sdk，实现对 4 层记忆系统的完整可观测性。

本文件包含两部分：

1. ``install_hermes_instrumentation()``  – 真实 monkey-patch 插桩函数
   在导入 hermes 相关模块后调用即可，无需修改 hermes 源码。

2. ``run_demo()``                        – 无 hermes 依赖的模拟演示
   直接运行本文件即可看到插桩效果、指标计算和导出结果。

使用方式（真实环境）：
    pip install hermes-agent
    # 在你的启动脚本中：
    from hermes_instrumentation import install_hermes_instrumentation
    install_hermes_instrumentation()
    # 然后正常使用 AIAgent
"""

from __future__ import annotations

import sys
import os
import time
import uuid
import functools
import logging
from pathlib import Path
from typing import Optional, Any, Callable

# Ensure the SDK is importable
_SDK_DIR = Path(__file__).parent
if str(_SDK_DIR) not in sys.path:
    sys.path.insert(0, str(_SDK_DIR))

from mo_sdk import (  # noqa: E402
    MemoryObserver,
    MemoryEvent,
    ContextSnapshot,
    ObserverConfig,
    JSONFileExporter,
    HTTPExporter,
    CompositeExporter,
    HermesMemoryInterceptor,
    MetricsCollector,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Global observer / interceptor instances
# ---------------------------------------------------------------------------

_observer: Optional[MemoryObserver] = None
_interceptor: Optional[HermesMemoryInterceptor] = None
_collector: Optional[MetricsCollector] = None


def get_observer() -> MemoryObserver:
    """Get or create the global MemoryObserver singleton."""
    global _observer
    if _observer is None:
        config = ObserverConfig(
            agent_id="hermes-agent",
            export_interval_seconds=30,
            max_events_in_memory=5000,
        )
        _observer = MemoryObserver(config=config)
    return _observer


def get_interceptor(session_id: str = "") -> HermesMemoryInterceptor:
    """Get or create the global Hermes interceptor singleton."""
    global _interceptor
    if _interceptor is None:
        obs = get_observer()
        _interceptor = HermesMemoryInterceptor(obs, session_id=session_id)
    elif session_id and _interceptor.session_id != session_id:
        _interceptor.session_id = session_id
    return _interceptor


def get_collector() -> MetricsCollector:
    """Get or create the global MetricsCollector singleton."""
    global _collector
    if _collector is None:
        obs = get_observer()
        _collector = MetricsCollector(config=obs.config)
    return _collector


# ===================================================================
# Part 1: Real Hermes Instrumentation (monkey-patching)
# ===================================================================

def install_hermes_instrumentation(
    agent_id: str = "hermes-agent",
    output_dir: str = "./mo_output",
    http_endpoint: Optional[str] = None,
    http_api_key: Optional[str] = None,
) -> tuple[MemoryObserver, HermesMemoryInterceptor]:
    """Install Memory Observatory instrumentation into a running Hermes agent.

    This function monkey-patches key methods on hermes-agent classes so
    that all memory operations are automatically observed.  No source
    code changes to hermes are required.

    Args:
        agent_id: Identifier for this agent instance.
        output_dir: Directory for JSON export files.
        http_endpoint: Optional HTTP endpoint to send data to.
        http_api_key: Optional API key for the HTTP endpoint.

    Returns:
        A tuple of (observer, interceptor).
    """
    global _observer, _interceptor

    # Initialize observer with config
    config = ObserverConfig(
        agent_id=agent_id,
        export_interval_seconds=60,
        max_events_in_memory=10_000,
    )
    _observer = MemoryObserver(config=config)

    # Setup exporters
    composite = CompositeExporter()
    composite.add(JSONFileExporter(output_dir=output_dir))
    if http_endpoint:
        composite.add(HTTPExporter(
            endpoint=http_endpoint,
            api_key=http_api_key,
        ))
    _observer.add_exporter(composite)

    # Create interceptor
    session_id = str(uuid.uuid4())
    _interceptor = HermesMemoryInterceptor(_observer, session_id=session_id)

    # Apply monkey-patches
    _patch_prompt_memory()
    _patch_session_search()
    _patch_context_compressor()
    _patch_prompt_caching()
    _patch_memory_providers()
    _patch_ai_agent_chat()

    logger.info("Memory Observatory instrumentation installed for agent '%s'",
                agent_id)
    return _observer, _interceptor


def _patch_prompt_memory() -> None:
    """Patch prompt memory (MEMORY.md / USER.md) write operations."""
    try:
        # Hermes manages prompt memory through the prompt builder
        # We patch the system prompt assembly to detect memory writes
        from hermes.agent import prompt_builder  # type: ignore

        original_build = prompt_builder.build_system_prompt

        @functools.wraps(original_build)
        def patched_build(*args, **kwargs):
            result = original_build(*args, **kwargs)
            # After building, record memory reads from prompt files
            interceptor = get_interceptor()
            # MEMORY.md and USER.md are read during system prompt build
            memory_content = kwargs.get("memory_content", "")
            if memory_content:
                interceptor.intercept_memory_read("MEMORY.md", memory_content)
            user_content = kwargs.get("user_content", "")
            if user_content:
                interceptor.intercept_memory_read("USER.md", user_content)
            return result

        prompt_builder.build_system_prompt = patched_build
        logger.debug("Patched prompt_builder.build_system_prompt")

    except ImportError:
        logger.debug("prompt_builder not found, skipping prompt memory patch")

    try:
        # Also patch memory file writes (memory persistence)
        from hermes.tools import memory_tool  # type: ignore

        if hasattr(memory_tool, "append_to_memory"):
            original_append = memory_tool.append_to_memory

            @functools.wraps(original_append)
            def patched_append(content: str, *args, **kwargs):
                result = original_append(content, *args, **kwargs)
                interceptor = get_interceptor()
                interceptor.intercept_memory_write(
                    key="MEMORY.md",
                    value=content,
                    old_value=None,
                )
                return result

            memory_tool.append_to_memory = patched_append
            logger.debug("Patched memory_tool.append_to_memory")

    except ImportError:
        logger.debug("memory_tool not found, skipping memory write patch")


def _patch_session_search() -> None:
    """Patch the session_search tool."""
    try:
        from hermes.tools import session_search  # type: ignore

        original_search = None
        if hasattr(session_search, "search_sessions"):
            original_search = session_search.search_sessions
        elif hasattr(session_search, "search"):
            original_search = session_search.search

        if original_search:
            @functools.wraps(original_search)
            def patched_search(query: str, *args, **kwargs):
                start = time.time()
                results = original_search(query, *args, **kwargs)
                latency_ms = (time.time() - start) * 1000

                interceptor = get_interceptor()
                # Normalize results to list of dicts
                result_list = results if isinstance(results, list) else []
                interceptor.intercept_session_search(
                    query=query,
                    results=result_list,
                    latency_ms=latency_ms,
                )
                return results

            # Apply patch
            if hasattr(session_search, "search_sessions"):
                session_search.search_sessions = patched_search
            else:
                session_search.search = patched_search
            logger.debug("Patched session_search")

    except ImportError:
        logger.debug("session_search not found, skipping session archive patch")


def _patch_context_compressor() -> None:
    """Patch the context compressor."""
    try:
        from hermes.agent import context_compressor  # type: ignore

        original_compress = None
        if hasattr(context_compressor, "compress_context"):
            original_compress = context_compressor.compress_context
        elif hasattr(context_compressor, "compress"):
            original_compress = context_compressor.compress

        if original_compress:
            @functools.wraps(original_compress)
            def patched_compress(messages, *args, **kwargs):
                # Estimate tokens before
                before_tokens = sum(
                    len(str(m.get("content", ""))) // 4
                    for m in messages if isinstance(m, dict)
                )

                result = original_compress(messages, *args, **kwargs)

                # Estimate tokens after
                after_tokens = sum(
                    len(str(m.get("content", ""))) // 4
                    for m in result if isinstance(m, dict)
                )

                interceptor = get_interceptor()
                method = kwargs.get("method", "default")
                interceptor.intercept_compression(
                    before_tokens=before_tokens,
                    after_tokens=after_tokens,
                    method=method,
                )
                return result

            if hasattr(context_compressor, "compress_context"):
                context_compressor.compress_context = patched_compress
            else:
                context_compressor.compress = patched_compress
            logger.debug("Patched context_compressor")

    except ImportError:
        logger.debug("context_compressor not found, skipping compression patch")


def _patch_prompt_caching() -> None:
    """Patch Anthropic prompt caching."""
    try:
        from hermes.agent import prompt_caching  # type: ignore

        if hasattr(prompt_caching, "check_cache"):
            original_check = prompt_caching.check_cache

            @functools.wraps(original_check)
            def patched_check(messages, *args, **kwargs):
                result = original_check(messages, *args, **kwargs)
                interceptor = get_interceptor()

                # result typically indicates hit/miss and prefix tokens
                hit = False
                tokens = 0
                prefix_hash = ""
                if isinstance(result, dict):
                    hit = result.get("hit", False)
                    tokens = result.get("cached_tokens", 0)
                    prefix_hash = result.get("prefix_hash", "")
                elif isinstance(result, tuple):
                    hit, tokens = result[0], result[1] if len(result) > 1 else 0

                interceptor.intercept_cache_check(
                    prefix_hash=prefix_hash or "unknown",
                    hit=hit,
                    tokens=tokens,
                )
                return result

            prompt_caching.check_cache = patched_check
            logger.debug("Patched prompt_caching.check_cache")

    except ImportError:
        logger.debug("prompt_caching not found, skipping cache patch")


def _patch_memory_providers() -> None:
    """Patch external memory provider operations."""
    try:
        from hermes.memory import base_provider  # type: ignore

        # Patch the base provider class methods
        if hasattr(base_provider, "BaseMemoryProvider"):
            provider_cls = base_provider.BaseMemoryProvider

            # Patch prefetch
            if hasattr(provider_cls, "prefetch"):
                original_prefetch = provider_cls.prefetch

                @functools.wraps(original_prefetch)
                def patched_prefetch(self, *args, **kwargs):
                    start = time.time()
                    result = original_prefetch(self, *args, **kwargs)
                    latency_ms = (time.time() - start) * 1000

                    interceptor = get_interceptor()
                    provider_name = getattr(self, "name", self.__class__.__name__)
                    interceptor.intercept_provider_operation(
                        provider=provider_name,
                        operation="prefetch",
                        latency_ms=latency_ms,
                        key=str(args[0]) if args else "",
                    )
                    return result

                provider_cls.prefetch = patched_prefetch

            # Patch sync
            if hasattr(provider_cls, "sync"):
                original_sync = provider_cls.sync

                @functools.wraps(original_sync)
                def patched_sync(self, *args, **kwargs):
                    start = time.time()
                    result = original_sync(self, *args, **kwargs)
                    latency_ms = (time.time() - start) * 1000

                    interceptor = get_interceptor()
                    provider_name = getattr(self, "name", self.__class__.__name__)
                    interceptor.intercept_provider_operation(
                        provider=provider_name,
                        operation="sync",
                        latency_ms=latency_ms,
                    )
                    return result

                provider_cls.sync = patched_sync

            # Patch extract
            if hasattr(provider_cls, "extract"):
                original_extract = provider_cls.extract

                @functools.wraps(original_extract)
                def patched_extract(self, *args, **kwargs):
                    start = time.time()
                    result = original_extract(self, *args, **kwargs)
                    latency_ms = (time.time() - start) * 1000

                    interceptor = get_interceptor()
                    provider_name = getattr(self, "name", self.__class__.__name__)
                    interceptor.intercept_provider_operation(
                        provider=provider_name,
                        operation="extract",
                        latency_ms=latency_ms,
                        content_summary=str(result)[:200] if result else "",
                    )
                    return result

                provider_cls.extract = patched_extract

            logger.debug("Patched BaseMemoryProvider methods")

    except ImportError:
        logger.debug("base_provider not found, skipping provider patches")


def _patch_ai_agent_chat() -> None:
    """Patch AIAgent.chat() to record per-turn context snapshots."""
    try:
        from hermes.run_agent import AIAgent  # type: ignore

        original_chat = AIAgent.chat

        @functools.wraps(original_chat)
        def patched_chat(self, user_message: str, *args, **kwargs):
            interceptor = get_interceptor()
            turn_num = interceptor.turn_counter + 1

            # Record before-turn context (if we can inspect it)
            messages = getattr(self, "messages", [])
            system_prompt = getattr(self, "system_prompt", "")

            # Estimate five-zone token budget
            total_est, zones = _estimate_context_zones(
                messages, system_prompt
            )

            # Record snapshot at turn start
            interceptor.intercept_chat_turn(
                turn_number=turn_num,
                context_snapshot={
                    **zones,
                    "total_tokens": total_est,
                    "kv_cache_hit": False,
                    "kv_cache_prefix_tokens": 0,
                    "decay_score": 1.0,
                    "drift_cosine": 0.0,
                },
            )

            # Execute the actual chat turn
            result = original_chat(self, user_message, *args, **kwargs)

            return result

        AIAgent.chat = patched_chat
        logger.debug("Patched AIAgent.chat")

    except ImportError:
        logger.debug("AIAgent not found, skipping chat patch")


def _estimate_context_zones(messages: list, system_prompt: str) -> tuple[int, dict]:
    """Estimate five-zone token distribution from message list.

    This is a heuristic – in production you'd use the actual tokenizer
    counts from the model API.

    Returns:
        (total_tokens, zones_dict)
    """
    system_tokens = len(system_prompt) // 4 if system_prompt else 0

    task_tokens = 0
    memory_tokens = 0
    tool_history_tokens = 0
    free_tokens = 0  # Would be (max_tokens - total_used)

    for msg in messages:
        if not isinstance(msg, dict):
            continue
        role = msg.get("role", "")
        content = msg.get("content", "")
        tokens = len(str(content)) // 4

        if role == "system":
            system_tokens += tokens
        elif role == "tool" or msg.get("tool_calls") or msg.get("name"):
            tool_history_tokens += tokens
        elif role == "user":
            # Split user messages: first message is task, rest adds to free
            task_tokens += tokens // 2
            free_tokens += tokens // 2
        elif role == "assistant":
            free_tokens += tokens

    total = system_tokens + task_tokens + memory_tokens + tool_history_tokens + free_tokens
    zones = {
        "system_tokens": system_tokens,
        "task_tokens": task_tokens,
        "memory_tokens": memory_tokens,
        "tool_history_tokens": tool_history_tokens,
        "free_tokens": free_tokens,
    }
    return total, zones


# ===================================================================
# Part 2: Demo / Simulation (runs without hermes-agent)
# ===================================================================

def run_demo() -> None:
    """Run a self-contained demo of the Memory Observatory SDK.

    Simulates a hermes-agent session with various memory operations to
    demonstrate the full instrumentation pipeline:
    event recording → metric computation → health check → export.
    """
    print("=" * 70)
    print("  Memory Observatory SDK - Hermes Instrumentation Demo")
    print("=" * 70)
    print()

    # ------------------------------------------------------------------
    # Setup
    # ------------------------------------------------------------------
    print("[Setup] Initializing MemoryObserver...")
    output_dir = _SDK_DIR / "mo_output"
    observer = MemoryObserver(
        agent_id="hermes-demo-agent",
        config=ObserverConfig(
            agent_id="hermes-demo-agent",
            export_interval_seconds=0,  # No auto-export for demo
            max_events_in_memory=1000,
        ),
    )

    exporter = JSONFileExporter(output_dir=str(output_dir))
    observer.add_exporter(exporter)

    interceptor = HermesMemoryInterceptor(
        observer, session_id="demo-session-001"
    )
    collector = MetricsCollector(config=observer.config)

    print(f"  Agent ID   : {observer.agent_id}")
    print(f"  Session ID : {interceptor.session_id}")
    print(f"  Output dir : {output_dir}")
    print()

    # ------------------------------------------------------------------
    # Simulate a conversation with memory operations
    # ------------------------------------------------------------------
    print("[Simulation] Simulating 5 chat turns with memory operations...")
    print()

    # --- Turn 1: Initial setup ---
    print("  Turn 1: Session initialization")
    # Session resume
    interceptor.intercept_session_resume(
        session_id="demo-session-001",
        success=True,
        memory_restored_tokens=1300,
    )
    # Prompt memory loaded
    memory_md_content = (
        "# Long-term Memory\n"
        "- User is a software engineer working on AI agent observability\n"
        "- Prefers concise, technical answers\n"
        "- Working on a project called Memory Observatory\n"
        "- Has experience with Python, Go, and React\n"
    )
    interceptor.intercept_memory_read("MEMORY.md", memory_md_content)
    interceptor.intercept_memory_read(
        "USER.md", "# User Profile\nName: Alex\nRole: Senior Engineer\n"
    )

    # Simulate KV-cache miss (first turn)
    interceptor.intercept_cache_check(
        prefix_hash="sys_prompt_v1", hit=False, tokens=1200
    )

    interceptor.intercept_chat_turn(context_snapshot={
        "system_tokens": 1300,
        "task_tokens": 500,
        "memory_tokens": 800,
        "tool_history_tokens": 100,
        "free_tokens": 6000,
        "total_tokens": 8700,
        "kv_cache_hit": False,
        "kv_cache_prefix_tokens": 1200,
        "decay_score": 1.0,
        "drift_cosine": 0.0,
    })

    # Provider prefetch
    interceptor.intercept_provider_operation(
        provider="mem0",
        operation="prefetch",
        latency_ms=150,
        cost=0.0,
        key="user:alex",
        token_count=200,
        content_summary="User preferences and recent tasks",
    )
    time.sleep(0.05)

    # --- Turn 2: Tool use adds to history ---
    print("  Turn 2: Search and tool use")
    interceptor.intercept_cache_check(
        prefix_hash="sys_prompt_v1", hit=True, tokens=1200
    )

    # Session search
    interceptor.intercept_session_search(
        query="memory observatory project details",
        results=[
            {"content": "Memory Observatory is a platform for observing AI agent memory layers."},
            {"content": "Core metrics: KV-cache hit rate, compression frequency, decay score."},
            {"content": "Five-zone budget: System 10%, Task 5%, Memory 15%, Tool 40%, Free 30%."},
        ],
        latency_ms=45.2,
    )

    interceptor.intercept_chat_turn(context_snapshot={
        "system_tokens": 1300,
        "task_tokens": 500,
        "memory_tokens": 1000,
        "tool_history_tokens": 800,
        "free_tokens": 5100,
        "total_tokens": 8700,
        "kv_cache_hit": True,
        "kv_cache_prefix_tokens": 1200,
        "decay_score": 0.95,
        "drift_cosine": 0.02,
    })

    # Skill memory load
    interceptor.intercept_skill_operation(
        skill_name="memory-observatory-research",
        operation="retrieve",
        token_count=500,
    )

    time.sleep(0.05)

    # --- Turn 3: More tool use, growing context ---
    print("  Turn 3: Extended tool history")
    interceptor.intercept_cache_check(
        prefix_hash="sys_prompt_v1", hit=True, tokens=1200
    )

    interceptor.intercept_session_search(
        query="hermes agent memory architecture",
        results=[
            {"content": "Hermes has 4-layer memory: prompt, session archive, skills, external providers."},
            {"content": "Session archive uses SQLite FTS5 for full-text search."},
        ],
        latency_ms=38.7,
    )

    interceptor.intercept_provider_operation(
        provider="honcho",
        operation="sync",
        latency_ms=200,
        cost=0.0001,
        key="session:demo-session-001",
        token_count=300,
    )

    interceptor.intercept_chat_turn(context_snapshot={
        "system_tokens": 1300,
        "task_tokens": 500,
        "memory_tokens": 1200,
        "tool_history_tokens": 2000,
        "free_tokens": 3700,
        "total_tokens": 8700,
        "kv_cache_hit": True,
        "kv_cache_prefix_tokens": 1200,
        "decay_score": 0.88,
        "drift_cosine": 0.04,
    })
    time.sleep(0.05)

    # --- Turn 4: Context compression triggered ---
    print("  Turn 4: Context compression triggered")
    interceptor.intercept_cache_check(
        prefix_hash="sys_prompt_v1", hit=True, tokens=1200
    )

    # Compression reduces tool history
    interceptor.intercept_compression(
        before_tokens=4500,
        after_tokens=2800,
        method="hierarchical_summarization",
    )

    interceptor.intercept_chat_turn(context_snapshot={
        "system_tokens": 1300,
        "task_tokens": 500,
        "memory_tokens": 1200,
        "tool_history_tokens": 2800,
        "free_tokens": 2900,
        "total_tokens": 8700,
        "kv_cache_hit": True,
        "kv_cache_prefix_tokens": 1200,
        "decay_score": 0.82,
        "drift_cosine": 0.06,  # Slight drift after compression
    })
    time.sleep(0.05)

    # --- Turn 5: Memory write + drift alert ---
    print("  Turn 5: Memory consolidation")
    interceptor.intercept_cache_check(
        prefix_hash="sys_prompt_v1", hit=True, tokens=1200
    )

    # Write new memory
    interceptor.intercept_memory_write(
        key="MEMORY.md",
        value=memory_md_content + "- Learned about five-zone token budget model\n",
        old_value=memory_md_content,
    )

    # External provider store
    interceptor.intercept_provider_operation(
        provider="mem0",
        operation="store",
        latency_ms=120,
        cost=0.0002,
        key="fact:five_zone_budget",
        token_count=50,
        content_summary="Five-zone token budget allocation model",
    )

    interceptor.intercept_chat_turn(context_snapshot={
        "system_tokens": 1350,
        "task_tokens": 500,
        "memory_tokens": 1300,
        "tool_history_tokens": 2900,
        "free_tokens": 2650,
        "total_tokens": 8700,
        "kv_cache_hit": True,
        "kv_cache_prefix_tokens": 1200,
        "decay_score": 0.75,
        "drift_cosine": 0.08,  # Increased drift
    })
    time.sleep(0.05)

    # ------------------------------------------------------------------
    # Show metrics
    # ------------------------------------------------------------------
    print()
    print("[Metrics] Computing memory observability metrics...")
    print()

    metrics = observer.get_metrics()
    _print_metrics(metrics)

    # ------------------------------------------------------------------
    # Health check
    # ------------------------------------------------------------------
    print()
    print("[Health Check] Running memory system health assessment...")
    print()

    # Feed events into collector for health check
    for event in observer._events:
        collector.ingest_event(event)
    for snapshot in observer._snapshots:
        collector.ingest_snapshot(snapshot)

    health_report = collector.run_health_check(metrics)
    _print_health_report(health_report)

    # ------------------------------------------------------------------
    # Export
    # ------------------------------------------------------------------
    print()
    print("[Export] Flushing data to JSON file...")
    results = observer.export()
    for result in results:
        print(f"  → {result}")

    # Also show the collector summary
    print()
    print("[Collector Summary]")
    summary = collector.get_summary()
    print(f"  Total events      : {summary['total_events']}")
    print(f"  Tokens stored     : {summary['total_tokens_stored']}")
    print(f"  Tokens retrieved  : {summary['total_tokens_retrieved']}")
    print(f"  Total cost (USD)  : ${summary['total_cost_usd']:.6f}")
    print(f"  Layers observed   : {list(summary['layer_summary'].keys())}")

    print()
    print("=" * 70)
    print("  Demo complete! Check mo_output/ for exported JSON data.")
    print("=" * 70)


def _print_metrics(metrics: dict) -> None:
    """Pretty-print the metrics dict."""
    print(f"  Total events           : {metrics['total_events']}")
    print(f"  Total snapshots        : {metrics['total_snapshots']}")
    print(f"  Total turns            : {metrics['total_turns']}")
    print()
    print(f"  KV-cache hit rate      : {metrics['kv_cache_hit_rate']:.1%}  "
          f"(healthy: >50%)")
    print(f"  Compression frequency  : {metrics['avg_compression_frequency']:.3f}  "
          f"(target: 0.05-0.15)")
    print(f"  Restore call rate      : {metrics['restore_call_rate']:.3f}  "
          f"(healthy: <0.1)")
    print(f"  Avg decay score        : {metrics['avg_decay_score']:.3f}  "
          f"(healthy: >0.7)")
    print(f"  Drift alert rate       : {metrics['drift_alert_rate']:.3f}  "
          f"(healthy: <0.05)")
    print(f"  Session resume rate    : {metrics['session_resume_success_rate']:.1%}  "
          f"(healthy: >90%)")
    print()
    zones = metrics["five_zone_distribution"]
    print(f"  Five-zone distribution (latest snapshot):")
    print(f"    System        : {zones['system_pct']:.1f}%  "
          f"(target: 10%)")
    print(f"    Task          : {zones['task_pct']:.1f}%  "
          f"(target: 5%)")
    print(f"    Memory        : {zones['memory_pct']:.1f}%  "
          f"(target: 15%)")
    print(f"    Tool History  : {zones['tool_history_pct']:.1f}%  "
          f"(target: 40%)")
    print(f"    Free          : {zones['free_pct']:.1f}%  "
          f"(target: 30%)")


def _print_health_report(report) -> None:
    """Pretty-print the health check report."""
    status_icon = "✅" if report.overall_healthy else "⚠️"
    print(f"  Overall status  : {status_icon} "
          f"{'HEALTHY' if report.overall_healthy else 'WARNINGS DETECTED'}")
    print()

    for check in report.checks:
        icon = "✅" if check.healthy else "⚠️"
        print(f"  {icon} {check.metric_name:30s} = {check.value:.4f}  "
              f"({check.message})")

    if report.warnings:
        print()
        print("  Warnings:")
        for w in report.warnings:
            print(f"    ⚠ {w}")


# ===================================================================
# Main entry point
# ===================================================================

if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(levelname)s: %(message)s",
    )
    run_demo()
