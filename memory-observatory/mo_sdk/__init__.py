"""
Memory Observatory SDK (mo-sdk)
===============================

Python SDK for the Memory Observatory platform – providing memory layer
observability for AI agents.

Quick Start
-----------

    from mo_sdk import MemoryObserver, JSONFileExporter

    observer = MemoryObserver(agent_id="my-agent")
    observer.add_exporter(JSONFileExporter("./output"))

    # Record memory events
    observer.record_event(MemoryEvent(
        operation="store",
        layer="prompt",
        memory_key="MEMORY.md",
        token_count=1200,
    ))

    # Get computed metrics
    metrics = observer.get_metrics()

Modules
-------
core        – Data models (MemoryEvent, ContextSnapshot) and MemoryObserver
collector   – Metrics aggregation and health checks
exporter    – JSON file and HTTP exporters
interceptors – Hermes-agent specific interception helpers
"""

from __future__ import annotations

# Version
__version__ = "0.1.0"
__all__ = [
    # Core
    "MemoryObserver",
    "MemoryEvent",
    "ContextSnapshot",
    "ObserverConfig",
    # Collector
    "MetricsCollector",
    "HealthStatus",
    "AgentHealthReport",
    "RollingStats",
    # Exporter
    "JSONFileExporter",
    "HTTPExporter",
    "CompositeExporter",
    "BaseExporter",
    "OTelSpanExporter",
    # Interceptors
    "HermesMemoryInterceptor",
]

# Core module imports
from .core import (
    MemoryObserver,
    MemoryEvent,
    ContextSnapshot,
    ObserverConfig,
)

# Collector module imports
from .collector import (
    MetricsCollector,
    HealthStatus,
    AgentHealthReport,
    RollingStats,
)

# Exporter module imports
from .exporter import (
    JSONFileExporter,
    HTTPExporter,
    CompositeExporter,
    BaseExporter,
)
from .otel_exporter import OTelSpanExporter

# Interceptors module imports
from .interceptors import (
    HermesMemoryInterceptor,
)
