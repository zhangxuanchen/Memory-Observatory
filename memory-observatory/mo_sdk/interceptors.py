"""
Memory Observatory SDK - Hermes Agent Interceptors

Dedicated interceptors for the hermes-agent framework
(https://github.com/NousResearch/hermes-agent).

These interceptors provide convenient methods for injecting observability
into Hermes' memory subsystems without modifying framework source code.
Typical usage is via monkey-patching key methods on ``AIAgent`` and
related classes (demonstrated in ``hermes_instrumentation.py``).

Interception points covered:
  - Prompt memory writes (MEMORY.md / USER.md)
  - Session archive searches (session_search tool)
  - Context compression (context_compressor)
  - Prompt cache checks (prompt_caching)
  - External memory provider operations (prefetch / sync / extract)
  - Per-chat-turn snapshots (AIAgent.chat main loop)
"""

from __future__ import annotations

import time
import hashlib
import logging
from typing import Optional, Any, Callable

from .core import MemoryObserver, MemoryEvent, ContextSnapshot

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Helper: token estimation
# ---------------------------------------------------------------------------

def _estimate_tokens(text: str) -> int:
    """Rough token estimate (1 token ≈ 4 chars for English text).

    This is used when the framework does not provide an exact token
    count.  For production use, prefer the real tokenizer count when
    available.
    """
    if not text:
        return 0
    # Conservative approximation: ~4 chars per token for English
    return max(1, len(text) // 4)


# ---------------------------------------------------------------------------
# Hermes Memory Interceptor
# ---------------------------------------------------------------------------

class HermesMemoryInterceptor:
    """Intercepts memory-related operations in a Hermes agent.

    Each ``intercept_*`` method records a ``MemoryEvent`` to the
    attached ``MemoryObserver``.  Methods are designed to be wrapped
    around existing Hermes functions using monkey-patching or decorators.

    Attributes:
        observer: The MemoryObserver to record events into.
        session_id: Current session identifier.
        turn_counter: Number of chat turns observed (auto-incremented by
            ``intercept_chat_turn``).
    """

    def __init__(self, observer: MemoryObserver, session_id: str = ""):
        self.observer = observer
        self.session_id = session_id
        self.turn_counter: int = 0
        self._total_compression_count: int = 0
        self._last_compression_ratio: float = 0.0

    # ------------------------------------------------------------------
    # 1. Prompt Memory (MEMORY.md / USER.md)
    # ------------------------------------------------------------------

    def intercept_memory_write(
        self,
        key: str,
        value: str,
        old_value: Optional[str] = None,
    ) -> None:
        """Intercept a write to prompt memory files.

        Called when MEMORY.md or USER.md is modified.

        Args:
            key: The memory file identifier (e.g. "MEMORY.md", "USER.md").
            value: The new content being written.
            old_value: Previous content, if known (used for diff size).
        """
        tokens = _estimate_tokens(value)
        old_tokens = _estimate_tokens(old_value) if old_value else 0
        delta_tokens = tokens - old_tokens

        event = MemoryEvent(
            agent_id=self.observer.agent_id,
            session_id=self.session_id,
            operation="store",
            layer="prompt",
            memory_key=key,
            memory_summary=value[:200] if value else "",
            token_count=tokens,
            latency_ms=0.0,  # Local file write, near-instant
            cost_usd=0.0,
            metadata={
                "old_tokens": old_tokens,
                "delta_tokens": delta_tokens,
                "is_update": old_value is not None,
                "source": "prompt_memory",
            },
        )
        self.observer.record_event(event)
        logger.debug("Prompt memory write: %s (%d tokens)", key, tokens)

    def intercept_memory_read(self, key: str, value: str) -> None:
        """Intercept a read from prompt memory.

        Args:
            key: The memory file identifier.
            value: The content that was read.
        """
        tokens = _estimate_tokens(value)
        event = MemoryEvent(
            agent_id=self.observer.agent_id,
            session_id=self.session_id,
            operation="retrieve",
            layer="prompt",
            memory_key=key,
            memory_summary=value[:200] if value else "",
            token_count=tokens,
            latency_ms=0.0,
            cost_usd=0.0,
            metadata={"source": "prompt_memory"},
        )
        self.observer.record_event(event)

    # ------------------------------------------------------------------
    # 2. Session Archive Search (session_search tool)
    # ------------------------------------------------------------------

    def intercept_session_search(
        self,
        query: str,
        results: list[dict],
        latency_ms: float,
    ) -> None:
        """Intercept a session archive search operation.

        Args:
            query: The search query string.
            results: List of result entries (each should have at least
                a "content" or "text" field for token estimation).
            latency_ms: Time taken for the search in milliseconds.
        """
        results_count = len(results)
        total_tokens = 0
        result_summaries = []
        for r in results[:5]:  # Summarize first 5 results
            content = r.get("content", r.get("text", "")) if isinstance(r, dict) else str(r)
            total_tokens += _estimate_tokens(content)
            result_summaries.append(content[:100])

        event = MemoryEvent(
            agent_id=self.observer.agent_id,
            session_id=self.session_id,
            operation="retrieve",
            layer="session",
            memory_key=f"search:{hashlib.md5(query.encode()).hexdigest()[:8]}",
            memory_summary=f"Query: {query[:100]} | Results: {results_count}",
            token_count=total_tokens,
            latency_ms=latency_ms,
            cost_usd=0.0,  # Local SQLite search has no direct cost
            metadata={
                "query": query,
                "results_count": results_count,
                "result_previews": result_summaries,
                "source": "session_archive",
            },
        )
        self.observer.record_event(event)
        logger.debug("Session search: %d results in %.1fms",
                     results_count, latency_ms)

    # ------------------------------------------------------------------
    # 3. Context Compression
    # ------------------------------------------------------------------

    def intercept_compression(
        self,
        before_tokens: int,
        after_tokens: int,
        method: str = "default",
    ) -> None:
        """Intercept a context compression operation.

        Args:
            before_tokens: Token count before compression.
            after_tokens: Token count after compression.
            method: Compression method / algorithm name.
        """
        self._total_compression_count += 1
        ratio = after_tokens / before_tokens if before_tokens > 0 else 0.0
        self._last_compression_ratio = ratio

        event = MemoryEvent(
            agent_id=self.observer.agent_id,
            session_id=self.session_id,
            operation="consolidate",
            layer="L1",  # Compression typically operates on L1 working memory
            memory_key="context_compression",
            memory_summary=(f"Compressed {before_tokens} -> {after_tokens} "
                            f"tokens ({ratio:.1%} ratio) via {method}"),
            token_count=before_tokens - after_tokens,
            latency_ms=0.0,
            cost_usd=0.0,
            metadata={
                "compression": True,
                "before_tokens": before_tokens,
                "after_tokens": after_tokens,
                "compression_ratio": ratio,
                "method": method,
                "source": "context_compressor",
            },
        )
        self.observer.record_event(event)
        logger.debug("Context compression: %d -> %d tokens (%.1f%%)",
                     before_tokens, after_tokens, ratio * 100)

    # ------------------------------------------------------------------
    # 4. Prompt Caching (KV-cache / Anthropic prompt caching)
    # ------------------------------------------------------------------

    def intercept_cache_check(
        self,
        prefix_hash: str,
        hit: bool,
        tokens: int,
    ) -> None:
        """Intercept a prompt cache check.

        Args:
            prefix_hash: Hash or identifier of the cached prefix.
            hit: Whether the cache hit (True) or missed (False).
            tokens: Number of tokens in the cached prefix.
        """
        event = MemoryEvent(
            agent_id=self.observer.agent_id,
            session_id=self.session_id,
            operation="retrieve" if hit else "store",
            layer="prompt",
            memory_key=f"cache:{prefix_hash}",
            memory_summary=f"Cache {'hit' if hit else 'miss'} for {tokens} tokens",
            token_count=tokens,
            latency_ms=0.0,
            cost_usd=0.0,
            metadata={
                "kv_cache_check": True,
                "kv_cache_hit": hit,
                "kv_cache_prefix_tokens": tokens,
                "prefix_hash": prefix_hash,
                "source": "prompt_caching",
            },
        )
        self.observer.record_event(event)

    # ------------------------------------------------------------------
    # 5. External Memory Provider Operations
    # ------------------------------------------------------------------

    def intercept_provider_operation(
        self,
        provider: str,
        operation: str,
        latency_ms: float,
        cost: float = 0.0,
        key: str = "",
        content_summary: str = "",
        token_count: int = 0,
    ) -> None:
        """Intercept an external memory provider operation.

        Args:
            provider: Provider name (e.g. "honcho", "mem0", "hindsight").
            operation: Operation type ("prefetch", "sync", "extract",
                "store", "retrieve", "forget").
            latency_ms: Operation latency in milliseconds.
            cost: Estimated cost in USD (if applicable).
            key: Memory key / identifier.
            content_summary: Short summary of memory content.
            token_count: Number of tokens involved.
        """
        # Map provider operations to standard operation names
        op_map = {
            "prefetch": "retrieve",
            "sync": "store",
            "extract": "retrieve",
            "store": "store",
            "retrieve": "retrieve",
            "forget": "forget",
            "delete": "forget",
            "update": "store",
            "add": "store",
            "search": "retrieve",
        }
        standard_op = op_map.get(operation.lower(), operation.lower())

        event = MemoryEvent(
            agent_id=self.observer.agent_id,
            session_id=self.session_id,
            operation=standard_op,
            layer="provider",
            memory_key=f"{provider}:{key}" if key else provider,
            memory_summary=content_summary[:200] if content_summary else f"{provider}.{operation}",
            token_count=token_count,
            latency_ms=latency_ms,
            cost_usd=cost,
            metadata={
                "provider": provider,
                "provider_operation": operation,
                "source": "external_provider",
            },
        )
        self.observer.record_event(event)
        logger.debug("Provider op: %s.%s (%.1fms, $%.6f)",
                     provider, operation, latency_ms, cost)

    # ------------------------------------------------------------------
    # 6. Skill Memory Operations
    # ------------------------------------------------------------------

    def intercept_skill_operation(
        self,
        skill_name: str,
        operation: str,
        token_count: int = 0,
    ) -> None:
        """Intercept a skill memory operation.

        Args:
            skill_name: Name of the skill.
            operation: "store" (new skill) or "retrieve" (skill loaded).
            token_count: Estimated tokens in the skill definition.
        """
        event = MemoryEvent(
            agent_id=self.observer.agent_id,
            session_id=self.session_id,
            operation=operation,
            layer="skill",
            memory_key=skill_name,
            memory_summary=f"Skill {operation}: {skill_name}",
            token_count=token_count,
            latency_ms=0.0,
            cost_usd=0.0,
            metadata={"skill_name": skill_name, "source": "skills"},
        )
        self.observer.record_event(event)

    # ------------------------------------------------------------------
    # 7. Chat Turn (full context snapshot)
    # ------------------------------------------------------------------

    def intercept_chat_turn(
        self,
        turn_number: Optional[int] = None,
        context_snapshot: Optional[dict] = None,
    ) -> ContextSnapshot:
        """Intercept the start/end of a chat turn.

        Records a full ContextSnapshot capturing the five-zone token
        budget and quality indicators.

        Args:
            turn_number: Explicit turn number (auto-increments if None).
            context_snapshot: Optional dict with token budget breakdown
                keys: system_tokens, task_tokens, memory_tokens,
                tool_history_tokens, free_tokens, total_tokens,
                kv_cache_hit, kv_cache_prefix_tokens, decay_score,
                drift_cosine.

        Returns:
            The ContextSnapshot that was recorded.
        """
        if turn_number is not None:
            self.turn_counter = turn_number
        else:
            self.turn_counter += 1
            turn_number = self.turn_counter

        snap_data = context_snapshot or {}
        total = snap_data.get("total_tokens", 0)
        if total == 0:
            total = sum([
                snap_data.get("system_tokens", 0),
                snap_data.get("task_tokens", 0),
                snap_data.get("memory_tokens", 0),
                snap_data.get("tool_history_tokens", 0),
                snap_data.get("free_tokens", 0),
            ])

        snapshot = ContextSnapshot(
            agent_id=self.observer.agent_id,
            session_id=self.session_id,
            total_tokens=total,
            system_tokens=snap_data.get("system_tokens", 0),
            task_tokens=snap_data.get("task_tokens", 0),
            memory_tokens=snap_data.get("memory_tokens", 0),
            tool_history_tokens=snap_data.get("tool_history_tokens", 0),
            free_tokens=snap_data.get("free_tokens", 0),
            compression_count=self._total_compression_count,
            last_compression_ratio=self._last_compression_ratio,
            kv_cache_hit=snap_data.get("kv_cache_hit", False),
            kv_cache_prefix_tokens=snap_data.get("kv_cache_prefix_tokens", 0),
            decay_score=snap_data.get("decay_score", 1.0),
            drift_cosine=snap_data.get("drift_cosine", 0.0),
        )
        self.observer.record_snapshot(snapshot)

        # Also record a turn-end event for metric computation
        event = MemoryEvent(
            agent_id=self.observer.agent_id,
            session_id=self.session_id,
            operation="store",
            layer="L1",
            memory_key=f"turn_{turn_number}",
            memory_summary=f"End of turn {turn_number}",
            token_count=total,
            latency_ms=0.0,
            cost_usd=0.0,
            metadata={
                "turn_end": True,
                "turn_number": turn_number,
                "total_tokens": total,
            },
        )
        self.observer.record_event(event)

        logger.debug("Turn %d snapshot: %d tokens (system=%d, task=%d, "
                     "memory=%d, tool=%d, free=%d)",
                     turn_number, total,
                     snapshot.system_tokens, snapshot.task_tokens,
                     snapshot.memory_tokens, snapshot.tool_history_tokens,
                     snapshot.free_tokens)
        return snapshot

    # ------------------------------------------------------------------
    # 8. Session Resume Tracking
    # ------------------------------------------------------------------

    def intercept_session_resume(
        self,
        session_id: str,
        success: bool,
        memory_restored_tokens: int = 0,
    ) -> None:
        """Intercept a session resume / restore operation.

        Args:
            session_id: The session being resumed.
            success: Whether the resume succeeded.
            memory_restored_tokens: Tokens of memory restored.
        """
        self.session_id = session_id
        event = MemoryEvent(
            agent_id=self.observer.agent_id,
            session_id=session_id,
            operation="retrieve" if success else "forget",
            layer="session",
            memory_key=f"resume:{session_id}",
            memory_summary=(f"Session resume {'succeeded' if success else 'failed'}"
                            f" ({memory_restored_tokens} tokens restored)"),
            token_count=memory_restored_tokens,
            latency_ms=0.0,
            cost_usd=0.0,
            metadata={
                "session_resume_attempt": True,
                "session_resume_success": success,
                "memory_restored_tokens": memory_restored_tokens,
                "source": "session_resume",
            },
        )
        self.observer.record_event(event)

    # ------------------------------------------------------------------
    # Utility: wrap a function with interception
    # ------------------------------------------------------------------

    @staticmethod
    def wrap_function(
        original_func: Callable,
        before_hook: Optional[Callable] = None,
        after_hook: Optional[Callable] = None,
    ) -> Callable:
        """Utility: wrap an existing function with before/after hooks.

        This is a helper for monkey-patching.  The before hook receives
        the same args/kwargs, and the after hook receives the return
        value plus args/kwargs.

        Args:
            original_func: The function to wrap.
            before_hook: Called with (*args, **kwargs) before the
                original function executes.
            after_hook: Called with (result, *args, **kwargs) after the
                original function returns.

        Returns:
            A wrapped version of the function.
        """
        import functools

        @functools.wraps(original_func)
        def wrapper(*args, **kwargs):
            if before_hook:
                try:
                    before_hook(*args, **kwargs)
                except Exception as exc:
                    logger.error("before_hook error: %s", exc)

            result = original_func(*args, **kwargs)

            if after_hook:
                try:
                    after_hook(result, *args, **kwargs)
                except Exception as exc:
                    logger.error("after_hook error: %s", exc)

            return result

        return wrapper
