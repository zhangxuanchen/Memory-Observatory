"""
Memory Observatory SDK - Data Exporters

Exporters receive the full event/snapshot buffer from a MemoryObserver
and persist or transmit the data.  Two modes are provided out of the
box:

* ``JSONFileExporter``  – writes each batch to a local JSON file.
* ``HTTPExporter``      – POSTs JSON payloads to an Observatory server.
"""

from __future__ import annotations

import json
import os
import time
import logging
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Base Exporter
# ---------------------------------------------------------------------------

class BaseExporter:
    """Abstract base class for all exporters.

    Subclasses must implement ``export()``.
    """

    def export(self, *, agent_id: str, events: list[dict],
               snapshots: list[dict], metrics: dict) -> str:
        """Export a batch of observability data.

        Args:
            agent_id: The agent that produced the data.
            events: List of MemoryEvent dicts.
            snapshots: List of ContextSnapshot dicts.
            metrics: Computed metric summary dict.

        Returns:
            A short human-readable description of the export result.
        """
        raise NotImplementedError


# ---------------------------------------------------------------------------
# JSON File Exporter
# ---------------------------------------------------------------------------

class JSONFileExporter(BaseExporter):
    """Writes observability data to local JSON files.

    Each export creates a new JSON file with a timestamped filename in
    the configured output directory.  The file contains events,
    snapshots, and computed metrics.

    Attributes:
        output_dir: Directory to write JSON files to (created if missing).
        pretty_print: If True, write indented JSON (easier to read).
        include_events: Whether to include the full event list.
        include_snapshots: Whether to include the full snapshot list.
    """

    def __init__(
        self,
        output_dir: str = "./mo_output",
        pretty_print: bool = True,
        include_events: bool = True,
        include_snapshots: bool = True,
    ):
        self.output_dir = Path(output_dir)
        self.pretty_print = pretty_print
        self.include_events = include_events
        self.include_snapshots = include_snapshots
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def export(self, *, agent_id: str, events: list[dict],
               snapshots: list[dict], metrics: dict) -> str:
        timestamp = int(time.time())
        safe_agent = agent_id.replace("/", "_").replace(" ", "_")
        filename = f"mo_{safe_agent}_{timestamp}.json"
        filepath = self.output_dir / filename

        payload: dict = {
            "agent_id": agent_id,
            "export_timestamp": timestamp,
            "metrics": metrics,
        }
        if self.include_events:
            payload["events"] = events
            payload["event_count"] = len(events)
        if self.include_snapshots:
            payload["snapshots"] = snapshots
            payload["snapshot_count"] = len(snapshots)

        indent = 2 if self.pretty_print else None
        with open(filepath, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=indent, ensure_ascii=False)

        return f"json_file:{filepath}"


# ---------------------------------------------------------------------------
# HTTP Exporter
# ---------------------------------------------------------------------------

class HTTPExporter(BaseExporter):
    """POSTs observability data to an HTTP endpoint.

    The payload is sent as JSON in the request body.  Uses ``httpx`` if
    available, otherwise falls back to ``urllib`` from the standard
    library so there are zero required external dependencies.

    Attributes:
        endpoint: Full URL of the observability server endpoint.
        api_key: Optional bearer token for authentication.
        timeout: Request timeout in seconds.
        batch_size: Maximum events per request (splits into multiple
            requests if exceeded).  0 = no limit.
    """

    def __init__(
        self,
        endpoint: str,
        api_key: Optional[str] = None,
        timeout: float = 10.0,
        batch_size: int = 0,
    ):
        self.endpoint = endpoint
        self.api_key = api_key
        self.timeout = timeout
        self.batch_size = batch_size

        # Detect httpx availability
        try:
            import httpx  # noqa: F401
            self._has_httpx = True
        except ImportError:
            self._has_httpx = False

    def export(self, *, agent_id: str, events: list[dict],
               snapshots: list[dict], metrics: dict) -> str:
        payload = {
            "agent_id": agent_id,
            "export_timestamp": time.time(),
            "metrics": metrics,
            "events": events,
            "snapshots": snapshots,
            "event_count": len(events),
            "snapshot_count": len(snapshots),
        }

        if self.batch_size > 0 and len(events) > self.batch_size:
            return self._export_batched(agent_id, events, snapshots, metrics)

        status_code = self._post_json(payload)
        return f"http:{self.endpoint}:status={status_code}"

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _export_batched(self, agent_id: str, events: list[dict],
                        snapshots: list[dict], metrics: dict) -> str:
        """Split large event lists into multiple HTTP requests."""
        statuses: list[int] = []
        for i in range(0, len(events), self.batch_size):
            batch = events[i:i + self.batch_size]
            payload = {
                "agent_id": agent_id,
                "export_timestamp": time.time(),
                "metrics": metrics,
                "events": batch,
                "snapshots": snapshots if i == 0 else [],
                "event_count": len(batch),
                "snapshot_count": len(snapshots) if i == 0 else 0,
                "batch_index": i // self.batch_size,
            }
            statuses.append(self._post_json(payload))
        return (f"http_batched:{self.endpoint}:batches={len(statuses)}"
                f":statuses={','.join(str(s) for s in statuses)}")

    def _post_json(self, payload: dict) -> int:
        """Send a single JSON POST request.  Returns HTTP status code."""
        if self._has_httpx:
            return self._post_with_httpx(payload)
        return self._post_with_urllib(payload)

    def _post_with_httpx(self, payload: dict) -> int:
        import httpx
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        try:
            response = httpx.post(
                self.endpoint,
                json=payload,
                headers=headers,
                timeout=self.timeout,
            )
            return response.status_code
        except Exception as exc:
            logger.error("HTTPExporter httpx error: %s", exc)
            return 0

    def _post_with_urllib(self, payload: dict) -> int:
        import urllib.request
        import urllib.error

        data = json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        req = urllib.request.Request(
            self.endpoint, data=data, headers=headers, method="POST"
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return resp.status
        except urllib.error.HTTPError as exc:
            logger.error("HTTPExporter urllib HTTP error: %s", exc)
            return exc.code
        except Exception as exc:
            logger.error("HTTPExporter urllib error: %s", exc)
            return 0


# ---------------------------------------------------------------------------
# Composite Exporter
# ---------------------------------------------------------------------------

class CompositeExporter(BaseExporter):
    """Combines multiple exporters, forwarding to each in sequence.

    Useful for writing to both a local file and an HTTP endpoint
    simultaneously.
    """

    def __init__(self, exporters: Optional[list[BaseExporter]] = None):
        self._exporters: list[BaseExporter] = exporters or []

    def add(self, exporter: BaseExporter) -> None:
        self._exporters.append(exporter)

    def export(self, *, agent_id: str, events: list[dict],
               snapshots: list[dict], metrics: dict) -> str:
        results: list[str] = []
        for exporter in self._exporters:
            try:
                results.append(exporter.export(
                    agent_id=agent_id,
                    events=events,
                    snapshots=snapshots,
                    metrics=metrics,
                ))
            except Exception as exc:
                logger.error("CompositeExporter sub-exporter error: %s", exc)
                results.append(f"error:{type(exc).__name__}")
        return ";".join(results)
