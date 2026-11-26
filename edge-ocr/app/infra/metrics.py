"""Minimal Prometheus exposition (no extra dependency)."""

from __future__ import annotations

import threading
from collections import defaultdict


class MetricsRegistry:
    """Counters and gauges rendered in the Prometheus text format."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counters: dict[str, float] = defaultdict(float)
        self._gauges: dict[str, float] = defaultdict(float)
        self._help: dict[str, str] = {}

    def describe(self, name: str, help_text: str) -> None:
        """Attach a HELP description to a metric."""
        with self._lock:
            self._help[name] = help_text

    def increment(self, name: str, value: float = 1.0) -> None:
        """Increase a counter."""
        with self._lock:
            self._counters[name] += value

    def set_gauge(self, name: str, value: float) -> None:
        """Set the value of a gauge."""
        with self._lock:
            self._gauges[name] = value

    def snapshot(self) -> dict[str, float]:
        """Current values of every metric."""
        with self._lock:
            return {**self._counters, **self._gauges}

    def render(self) -> str:
        """Render the metrics in the Prometheus text exposition format."""
        lines: list[str] = []
        with self._lock:
            counters = dict(self._counters)
            gauges = dict(self._gauges)
            helps = dict(self._help)
        for name, value in sorted(counters.items()):
            if name in helps:
                lines.append(f"# HELP {name} {helps[name]}")
            lines.append(f"# TYPE {name} counter")
            lines.append(f"{name} {value}")
        for name, value in sorted(gauges.items()):
            if name in helps:
                lines.append(f"# HELP {name} {helps[name]}")
            lines.append(f"# TYPE {name} gauge")
            lines.append(f"{name} {value}")
        return "\n".join(lines) + "\n"


REGISTRY = MetricsRegistry()
REGISTRY.describe("edge_ocr_frames_processed_total", "Frames processed by the edge service")
REGISTRY.describe(
    "edge_ocr_events_published_total", "Reading events published to the backend/broker"
)
REGISTRY.describe("edge_ocr_events_suppressed_total", "Readings suppressed as duplicates")
REGISTRY.describe("edge_ocr_outbox_pending", "Events waiting in the durable outbox")
REGISTRY.describe("edge_ocr_latency_ms", "Latency of the last recognition call")
REGISTRY.describe("edge_ocr_model_available", "1 when the detection and OCR runtimes are usable")
