"""
Memory Observatory SDK - Metrics Collector

Aggregates raw events and snapshots into actionable metrics, health
scores, and anomaly detections.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Optional
from collections import defaultdict, deque

from .core import MemoryEvent, ContextSnapshot, ObserverConfig


# ---------------------------------------------------------------------------
# Rolling Statistics Helper
# ---------------------------------------------------------------------------

class RollingStats:
    """Maintains a rolling window of numeric values for fast stats."""

    def __init__(self, window_size: int = 1000):
        self._window_size = window_size
        self._values: deque[float] = deque(maxlen=window_size)
        self._sum: float = 0.0
        self._count: int = 0

    def add(self, value: float) -> None:
        if len(self._values) == self._window_size:
            self._sum -= self._values[0]
        else:
            self._count += 1
        self._values.append(value)
        self._sum += value

    @property
    def avg(self) -> float:
        if not self._values:
            return 0.0
        return self._sum / len(self._values)

    @property
    def count(self) -> int:
        return len(self._values)

    @property
    def min(self) -> float:
        if not self._values:
            return 0.0
        return min(self._values)

    @property
    def max(self) -> float:
        if not self._values:
            return 0.0
        return max(self._values)

    def to_dict(self) -> dict:
        return {
            "avg": self.avg,
            "min": self.min,
            "max": self.max,
            "count": self.count,
        }


# ---------------------------------------------------------------------------
# Health Check Result
# ---------------------------------------------------------------------------

@dataclass
class HealthStatus:
    """Result of a health check evaluation."""
    metric_name: str
    value: float
    healthy: bool
    threshold: float
    direction: str  # "above" (value > threshold is healthy) or "below"
    message: str = ""


@dataclass
class AgentHealthReport:
    """Full health report for an agent's memory system."""
    timestamp: float = field(default_factory=time.time)
    overall_healthy: bool = True
    checks: list[HealthStatus] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "timestamp": self.timestamp,
            "overall_healthy": self.overall_healthy,
            "checks": [c.__dict__ for c in self.checks],
            "warnings": self.warnings,
        }


# ---------------------------------------------------------------------------
# Metrics Collector
# ---------------------------------------------------------------------------

class MetricsCollector:
    """Aggregates events and snapshots into high-level metrics.

    The collector maintains per-layer, per-operation rolling statistics
    and can generate health reports, anomaly alerts, and trend data.
    """

    def __init__(self, config: Optional[ObserverConfig] = None):
        self.config = config or ObserverConfig()

        # Per-operation latency stats, keyed by (layer, operation)
        self._latency_stats: dict[tuple[str, str], RollingStats] = defaultdict(
            lambda: RollingStats(1000)
        )
        # Per-operation token stats
        self._token_stats: dict[tuple[str, str], RollingStats] = defaultdict(
            lambda: RollingStats(1000)
        )
        # Per-operation cost stats
        self._cost_stats: dict[tuple[str, str], RollingStats] = defaultdict(
            lambda: RollingStats(1000)
        )

        # Layer-specific counters
        self._operation_counts: dict[tuple[str, str], int] = defaultdict(int)
        self._layer_memory_keys: dict[str, set[str]] = defaultdict(set)

        # Snapshot history (rolling)
        self._snapshots: deque[ContextSnapshot] = deque(maxlen=1000)

        # Totals
        self.total_events: int = 0
        self.total_tokens_stored: int = 0
        self.total_tokens_retrieved: int = 0
        self.total_cost_usd: float = 0.0

    # ------------------------------------------------------------------
    # Ingestion
    # ------------------------------------------------------------------

    def ingest_event(self, event: MemoryEvent) -> None:
        """Process a single memory event into running statistics."""
        key = (event.layer, event.operation)
        self._latency_stats[key].add(event.latency_ms)
        self._token_stats[key].add(event.token_count)
        self._cost_stats[key].add(event.cost_usd)
        self._operation_counts[key] += 1
        self._layer_memory_keys[event.layer].add(event.memory_key)

        self.total_events += 1
        self.total_cost_usd += event.cost_usd

        if event.operation == "store":
            self.total_tokens_stored += event.token_count
        elif event.operation == "retrieve":
            self.total_tokens_retrieved += event.token_count

    def ingest_snapshot(self, snapshot: ContextSnapshot) -> None:
        """Process a context snapshot."""
        self._snapshots.append(snapshot)

    # ------------------------------------------------------------------
    # Query API
    # ------------------------------------------------------------------

    def get_layer_summary(self) -> dict:
        """Return per-layer summary statistics."""
        layers: dict[str, dict] = {}
        for (layer, operation), count in self._operation_counts.items():
            if layer not in layers:
                layers[layer] = {"operations": {}, "total_keys": 0}
            layers[layer]["operations"][operation] = {
                "count": count,
                "avg_latency_ms": self._latency_stats[(layer, operation)].avg,
                "avg_tokens": self._token_stats[(layer, operation)].avg,
                "avg_cost_usd": self._cost_stats[(layer, operation)].avg,
            }
            layers[layer]["total_keys"] = len(self._layer_memory_keys.get(layer, set()))
        return layers

    def get_latency_breakdown(self) -> dict:
        """Latency distribution per layer and operation."""
        result: dict[str, dict] = {}
        for (layer, operation), stats in self._latency_stats.items():
            if layer not in result:
                result[layer] = {}
            result[layer][operation] = stats.to_dict()
        return result

    def get_token_budget_trend(self, last_n: int = 20) -> list[dict]:
        """Recent five-zone token budget history."""
        if not self._snapshots:
            return []
        recent = list(self._snapshots)[-last_n:]
        return [
            {
                "timestamp": s.timestamp,
                "total_tokens": s.total_tokens,
                "system_pct": round(s.system_pct * 100, 2),
                "task_pct": round(s.task_pct * 100, 2),
                "memory_pct": round(s.memory_pct * 100, 2),
                "tool_history_pct": round(s.tool_history_pct * 100, 2),
                "free_pct": round(s.free_pct * 100, 2),
            }
            for s in recent
        ]

    def run_health_check(self, metrics: dict) -> AgentHealthReport:
        """Evaluate memory system health against configured thresholds.

        Args:
            metrics: Metrics dict from MemoryObserver.get_metrics().

        Returns:
            An AgentHealthReport with per-metric pass/fail status.
        """
        report = AgentHealthReport()
        cfg = self.config

        # 1. KV-cache hit rate (higher is better)
        kv_rate = metrics.get("kv_cache_hit_rate", 0.0)
        kv_healthy = kv_rate >= cfg.kv_cache_healthy_threshold
        report.checks.append(HealthStatus(
            metric_name="kv_cache_hit_rate",
            value=kv_rate,
            healthy=kv_healthy,
            threshold=cfg.kv_cache_healthy_threshold,
            direction="above",
            message=f"KV-cache hit rate: {kv_rate:.1%} "
                    f"(threshold: {cfg.kv_cache_healthy_threshold:.0%})",
        ))
        if not kv_healthy:
            report.warnings.append(
                f"KV-cache hit rate ({kv_rate:.1%}) is below healthy "
                f"threshold ({cfg.kv_cache_healthy_threshold:.0%}). "
                f"Consider optimizing prompt structure or enabling "
                f"longer system prefix caching."
            )

        # 2. Compression frequency (should be in range)
        comp_freq = metrics.get("avg_compression_frequency", 0.0)
        comp_healthy = (cfg.compression_freq_target_min
                        <= comp_freq
                        <= cfg.compression_freq_target_max)
        report.checks.append(HealthStatus(
            metric_name="avg_compression_frequency",
            value=comp_freq,
            healthy=comp_healthy,
            threshold=(cfg.compression_freq_target_min + cfg.compression_freq_target_max) / 2,
            direction="range",
            message=f"Compression frequency: {comp_freq:.3f} "
                    f"(target: {cfg.compression_freq_target_min}-"
                    f"{cfg.compression_freq_target_max})",
        ))
        if not comp_healthy:
            if comp_freq > cfg.compression_freq_target_max:
                report.warnings.append(
                    f"Compression is too frequent ({comp_freq:.3f} per turn). "
                    f"This may degrade memory fidelity. Consider increasing "
                    f"the context window or adjusting compression triggers."
                )
            else:
                report.warnings.append(
                    f"Compression is too rare ({comp_freq:.3f} per turn). "
                    f"Context may be accumulating stale information. "
                    f"Consider lowering the compression trigger threshold."
                )

        # 3. Restore call rate (lower is better)
        restore_rate = metrics.get("restore_call_rate", 0.0)
        restore_healthy = restore_rate < cfg.restore_call_rate_threshold
        report.checks.append(HealthStatus(
            metric_name="restore_call_rate",
            value=restore_rate,
            healthy=restore_healthy,
            threshold=cfg.restore_call_rate_threshold,
            direction="below",
            message=f"Restore call rate: {restore_rate:.3f} "
                    f"(threshold: <{cfg.restore_call_rate_threshold})",
        ))
        if not restore_healthy:
            report.warnings.append(
                f"High restore call rate ({restore_rate:.3f}) indicates "
                f"frequent re-loading of previously evicted memory. "
                f"Consider increasing L2/L3 capacity or improving the "
                f"eviction policy."
            )

        # 4. Decay score (higher is better, memory freshness)
        decay = metrics.get("avg_decay_score", 1.0)
        decay_healthy = decay >= cfg.decay_score_healthy_threshold
        report.checks.append(HealthStatus(
            metric_name="avg_decay_score",
            value=decay,
            healthy=decay_healthy,
            threshold=cfg.decay_score_healthy_threshold,
            direction="above",
            message=f"Decay score: {decay:.3f} "
                    f"(threshold: >={cfg.decay_score_healthy_threshold})",
        ))
        if not decay_healthy:
            report.warnings.append(
                f"Memory decay score ({decay:.3f}) is low. Memories are "
                f"becoming stale. Consider triggering a consolidate "
                f"operation to refresh and prune memory."
            )

        # 5. Drift alert rate (lower is better)
        drift_rate = metrics.get("drift_alert_rate", 0.0)
        drift_healthy = drift_rate <= cfg.drift_alert_threshold
        report.checks.append(HealthStatus(
            metric_name="drift_alert_rate",
            value=drift_rate,
            healthy=drift_healthy,
            threshold=cfg.drift_alert_threshold,
            direction="below",
            message=f"Drift alert rate: {drift_rate:.3f} "
                    f"(threshold: <={cfg.drift_alert_threshold})",
        ))
        if not drift_healthy:
            report.warnings.append(
                f"High drift alert rate ({drift_rate:.3f}). Memory content "
                f"is drifting significantly from the original context. "
                f"Review compression and consolidation strategies."
            )

        # 6. Session resume success rate (higher is better)
        resume_rate = metrics.get("session_resume_success_rate", 0.0)
        resume_attempts = metrics.get("session_resume_attempts", 0)
        if resume_attempts > 0:
            resume_healthy = resume_rate >= cfg.session_resume_healthy_threshold
            report.checks.append(HealthStatus(
                metric_name="session_resume_success_rate",
                value=resume_rate,
                healthy=resume_healthy,
                threshold=cfg.session_resume_healthy_threshold,
                direction="above",
                message=f"Session resume success: {resume_rate:.1%} "
                        f"(threshold: >={cfg.session_resume_healthy_threshold:.0%})",
            ))
            if not resume_healthy:
                report.warnings.append(
                    f"Session resume success rate ({resume_rate:.1%}) is "
                    f"below threshold. Check session archive integrity "
                    f"and memory provider sync status."
                )

        # Overall health
        report.overall_healthy = all(c.healthy for c in report.checks)
        return report

    def get_summary(self) -> dict:
        """Return a flat summary of all collected statistics."""
        return {
            "total_events": self.total_events,
            "total_tokens_stored": self.total_tokens_stored,
            "total_tokens_retrieved": self.total_tokens_retrieved,
            "total_cost_usd": round(self.total_cost_usd, 6),
            "layer_summary": self.get_layer_summary(),
            "latency_breakdown": self.get_latency_breakdown(),
            "snapshot_count": len(self._snapshots),
        }
