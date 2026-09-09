"""
Memory Observatory SDK - Core Data Models and Observer

Provides the foundational data classes and the central MemoryObserver
that orchestrates event collection, metric computation, and export triggers.
"""

from __future__ import annotations

import time
import uuid
import threading
from dataclasses import dataclass, field, asdict
from typing import Optional


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

@dataclass
class ObserverConfig:
    """Configuration for MemoryObserver.

    Attributes:
        agent_id: Unique identifier for the agent being observed.
        export_interval_seconds: How often to auto-export collected data.
            Set to 0 to disable auto-export.
        max_events_in_memory: Maximum number of events to keep in memory
            before forcing an export / rotation.
        kv_cache_healthy_threshold: KV-cache hit rate above this value
            is considered healthy (default 0.5 = 50%).
        decay_score_healthy_threshold: Minimum healthy decay score
            (default 0.7).
        drift_alert_threshold: Maximum cosine drift before alerting
            (default 0.05 = 5% drift triggers alert).
    """
    agent_id: str = "default-agent"
    export_interval_seconds: int = 60
    max_events_in_memory: int = 10_000
    kv_cache_healthy_threshold: float = 0.5
    decay_score_healthy_threshold: float = 0.7
    drift_alert_threshold: float = 0.05
    session_resume_healthy_threshold: float = 0.9
    compression_freq_target_min: float = 0.05
    compression_freq_target_max: float = 0.15
    restore_call_rate_threshold: float = 0.1


# ---------------------------------------------------------------------------
# Event Model
# ---------------------------------------------------------------------------

@dataclass
class MemoryEvent:
    """A single memory operation event.

    Represents one atomic memory operation (store, retrieve, forget,
    consolidate) that occurred at a specific memory layer.
    """
    event_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    timestamp: float = field(default_factory=time.time)
    agent_id: str = ""
    session_id: str = ""
    operation: str = ""          # store / retrieve / forget / consolidate
    layer: str = ""              # L1 / L2 / L3 / prompt / session / skill / provider
    memory_key: str = ""         # Memory identifier
    memory_summary: str = ""     # Short summary / preview of the memory
    token_count: int = 0         # Tokens involved in this operation
    latency_ms: float = 0.0      # Operation latency in milliseconds
    cost_usd: float = 0.0        # Operation cost in USD
    metadata: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return asdict(self)


# ---------------------------------------------------------------------------
# Context Snapshot
# ---------------------------------------------------------------------------

@dataclass
class ContextSnapshot:
    """A point-in-time snapshot of the agent's context window.

    Captures the five-zone token budget distribution, compression state,
    KV-cache status, and quality indicators.
    """
    timestamp: float = field(default_factory=time.time)
    agent_id: str = ""
    session_id: str = ""
    total_tokens: int = 0

    # Five-zone budget distribution
    system_tokens: int = 0          # System Prompt zone
    task_tokens: int = 0            # Task Description zone
    memory_tokens: int = 0          # Memory Retrieval zone
    tool_history_tokens: int = 0    # Tool History zone
    free_tokens: int = 0            # Free / available space

    # Compression metrics
    compression_count: int = 0
    last_compression_ratio: float = 0.0  # after / before (e.g. 0.6 means 40% reduction)

    # KV-cache
    kv_cache_hit: bool = False
    kv_cache_prefix_tokens: int = 0

    # Quality indicators
    decay_score: float = 1.0        # Memory decay score (1.0 = fresh, 0.0 = stale)
    drift_cosine: float = 0.0       # Drift cosine similarity vs baseline (lower = more drift)

    def to_dict(self) -> dict:
        return asdict(self)

    @property
    def system_pct(self) -> float:
        return self.system_tokens / self.total_tokens if self.total_tokens else 0.0

    @property
    def task_pct(self) -> float:
        return self.task_tokens / self.total_tokens if self.total_tokens else 0.0

    @property
    def memory_pct(self) -> float:
        return self.memory_tokens / self.total_tokens if self.total_tokens else 0.0

    @property
    def tool_history_pct(self) -> float:
        return self.tool_history_tokens / self.total_tokens if self.total_tokens else 0.0

    @property
    def free_pct(self) -> float:
        return self.free_tokens / self.total_tokens if self.total_tokens else 0.0


# ---------------------------------------------------------------------------
# Memory Observer
# ---------------------------------------------------------------------------

class MemoryObserver:
    """Central observer that collects memory events and context snapshots.

    This is the main entry point for instrumentation.  It manages the
    in-memory event buffer, computes rolling metrics, and triggers
    exports via configured exporters.

    Thread-safe: all mutable state is guarded by an internal lock so the
    observer can be used from multiple threads (e.g. async event loops
    and background exporters).
    """

    def __init__(self, agent_id: str = "default-agent",
                 config: Optional[ObserverConfig] = None):
        if config is None:
            config = ObserverConfig(agent_id=agent_id)
        elif agent_id != "default-agent":
            config.agent_id = agent_id
        self.config = config
        self.agent_id = config.agent_id

        self._lock = threading.RLock()
        self._events: list[MemoryEvent] = []
        self._snapshots: list[ContextSnapshot] = []
        self._exporters: list = []  # type: ignore[name-defined]

        # Counters for derived metrics
        self._kv_cache_attempts: int = 0
        self._kv_cache_hits: int = 0
        self._total_turns: int = 0
        self._compression_events: int = 0
        self._restore_calls: int = 0
        self._session_resume_attempts: int = 0
        self._session_resume_successes: int = 0
        self._drift_alerts: int = 0

        # Auto-export timer
        self._export_timer: Optional[threading.Timer] = None
        self._schedule_auto_export()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def record_event(self, event: MemoryEvent) -> None:
        """Record a memory operation event.

        Args:
            event: A populated MemoryEvent instance.
        """
        if not event.agent_id:
            event.agent_id = self.agent_id
        with self._lock:
            self._events.append(event)
            self._update_counters_from_event(event)
            if len(self._events) >= self.config.max_events_in_memory:
                # Trigger background export and rotate buffer
                self._export_unsafe()

    def record_snapshot(self, snapshot: ContextSnapshot) -> None:
        """Record a context window snapshot.

        Args:
            snapshot: A populated ContextSnapshot instance.
        """
        if not snapshot.agent_id:
            snapshot.agent_id = self.agent_id
        with self._lock:
            self._snapshots.append(snapshot)

    def add_exporter(self, exporter) -> None:  # type: ignore[no-untyped-def]
        """Attach an exporter to this observer.

        Exporters receive the full event and snapshot buffers each time
        ``export()`` is called (manually or on the auto-export interval).
        """
        with self._lock:
            self._exporters.append(exporter)

    def get_metrics(self) -> dict:
        """Compute and return the current metric summary.

        Returns a dictionary of named metrics including:
            - kv_cache_hit_rate
            - avg_compression_frequency
            - restore_call_rate
            - avg_decay_score
            - drift_alert_rate
            - session_resume_success_rate
            - total_events
            - total_snapshots
            - five_zone_distribution (latest)
        """
        with self._lock:
            kv_rate = (self._kv_cache_hits / self._kv_cache_attempts
                       if self._kv_cache_attempts > 0 else 0.0)
            compression_freq = (self._compression_events / self._total_turns
                                if self._total_turns > 0 else 0.0)
            restore_rate = (self._restore_calls / self._total_turns
                            if self._total_turns > 0 else 0.0)
            avg_decay = self._compute_avg_decay()
            drift_rate = (self._drift_alerts / self._total_turns
                          if self._total_turns > 0 else 0.0)
            resume_rate = (self._session_resume_successes / self._session_resume_attempts
                           if self._session_resume_attempts > 0 else 0.0)
            latest_zones = self._latest_zone_distribution()

            return {
                "agent_id": self.agent_id,
                "total_events": len(self._events),
                "total_snapshots": len(self._snapshots),
                "kv_cache_hit_rate": kv_rate,
                "avg_compression_frequency": compression_freq,
                "restore_call_rate": restore_rate,
                "avg_decay_score": avg_decay,
                "drift_alert_rate": drift_rate,
                "session_resume_success_rate": resume_rate,
                "five_zone_distribution": latest_zones,
                "total_turns": self._total_turns,
                "compression_events": self._compression_events,
                "kv_cache_attempts": self._kv_cache_attempts,
                "kv_cache_hits": self._kv_cache_hits,
            }

    def export(self) -> list[str]:
        """Manually trigger an export to all attached exporters.

        Returns a list of export result descriptions (e.g. file paths,
        HTTP response statuses).
        """
        with self._lock:
            return self._export_unsafe()

    def shutdown(self) -> None:
        """Shut down the observer, flush remaining data, cancel timers."""
        if self._export_timer is not None:
            self._export_timer.cancel()
            self._export_timer = None
        # Final flush
        self.export()

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _schedule_auto_export(self) -> None:
        """Start the periodic auto-export timer if configured."""
        interval = self.config.export_interval_seconds
        if interval <= 0:
            return
        self._export_timer = threading.Timer(interval, self._auto_export_tick)
        self._export_timer.daemon = True
        self._export_timer.start()

    def _auto_export_tick(self) -> None:
        try:
            self.export()
        finally:
            self._schedule_auto_export()

    def _export_unsafe(self) -> list[str]:
        """Export current buffers using all exporters.

        Must be called while holding ``self._lock``.
        """
        if not self._exporters:
            return []

        events_payload = [e.to_dict() for e in self._events]
        snapshots_payload = [s.to_dict() for s in self._snapshots]
        metrics = self.get_metrics()

        results: list[str] = []
        for exporter in self._exporters:
            try:
                result = exporter.export(
                    agent_id=self.agent_id,
                    events=events_payload,
                    snapshots=snapshots_payload,
                    metrics=metrics,
                )
                results.append(str(result))
            except Exception as exc:  # pragma: no cover - defensive
                results.append(f"exporter_error:{type(exc).__name__}:{exc}")

        # Rotate buffers after successful export
        self._events = []
        self._snapshots = []
        return results

    def _update_counters_from_event(self, event: MemoryEvent) -> None:
        """Update running counters from a new event.

        Must be called while holding ``self._lock``.
        """
        # KV-cache tracking via metadata
        if event.metadata.get("kv_cache_check"):
            self._kv_cache_attempts += 1
            if event.metadata.get("kv_cache_hit", False):
                self._kv_cache_hits += 1

        # Compression events
        if event.operation == "consolidate" or event.metadata.get("compression"):
            self._compression_events += 1

        # Restore calls (retrieves that re-load previously evicted memory)
        if event.metadata.get("is_restore"):
            self._restore_calls += 1

        # Session resume tracking
        if event.metadata.get("session_resume_attempt"):
            self._session_resume_attempts += 1
            if event.metadata.get("session_resume_success", False):
                self._session_resume_successes += 1

        # Drift alerts
        if event.metadata.get("drift_alert"):
            self._drift_alerts += 1

        # Turn counting
        if event.metadata.get("turn_end"):
            self._total_turns += 1

    def _compute_avg_decay(self) -> float:
        """Average decay score across recent snapshots."""
        if not self._snapshots:
            return 1.0
        recent = self._snapshots[-100:]  # last 100 snapshots
        return sum(s.decay_score for s in recent) / len(recent)

    def _latest_zone_distribution(self) -> dict:
        """Five-zone distribution from the latest snapshot."""
        if not self._snapshots:
            return {
                "system_pct": 0, "task_pct": 0, "memory_pct": 0,
                "tool_history_pct": 0, "free_pct": 0,
            }
        snap = self._snapshots[-1]
        return {
            "system_pct": round(snap.system_pct * 100, 2),
            "task_pct": round(snap.task_pct * 100, 2),
            "memory_pct": round(snap.memory_pct * 100, 2),
            "tool_history_pct": round(snap.tool_history_pct * 100, 2),
            "free_pct": round(snap.free_pct * 100, 2),
            "total_tokens": snap.total_tokens,
        }
