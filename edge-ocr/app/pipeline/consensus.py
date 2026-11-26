"""Temporal consensus and event de-duplication.

A camera sees the same vehicle for many consecutive frames. Reading every frame
independently produces a flood of near-duplicate events, so readings of the same
kind are grouped in a short window and voted character by character.
"""

from __future__ import annotations

import time
from collections import defaultdict
from dataclasses import dataclass


@dataclass(frozen=True)
class Reading:
    """A single OCR observation."""

    text: str
    confidence: float
    timestamp: float

    def __post_init__(self) -> None:
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("confidence must be within [0, 1]")


@dataclass
class ConsensusResult:
    """Outcome of voting over the readings collected in a window."""

    text: str
    confidence: float
    agreement: float
    votes: int

    @property
    def accepted(self) -> bool:
        """True when enough readings agreed to publish the result."""
        return self.votes > 0 and self.agreement >= 0.6


class TemporalConsensus:
    """Votes character by character over the readings of a sliding time window."""

    def __init__(
        self,
        window_seconds: float = 1.5,
        max_readings: int = 15,
        min_agreement: float = 0.6,
        clock: object = time,
    ) -> None:
        self.window_seconds = window_seconds
        self.max_readings = max_readings
        self.min_agreement = min_agreement
        self._clock = clock
        self._readings: dict[str, list[Reading]] = defaultdict(list)

    def add(self, key: str, reading: Reading) -> ConsensusResult:
        """Register a reading and return the current consensus for ``key``."""
        bucket = self._readings[key]
        bucket.append(reading)
        cutoff = reading.timestamp - self.window_seconds
        self._readings[key] = [item for item in bucket if item.timestamp >= cutoff]
        if len(self._readings[key]) > self.max_readings:
            self._readings[key] = self._readings[key][-self.max_readings :]
        return self.result(key)

    def result(self, key: str) -> ConsensusResult:
        """Compute the consensus of the readings currently held for ``key``."""
        bucket = self._readings.get(key, [])
        if not bucket:
            return ConsensusResult(text="", confidence=0.0, agreement=0.0, votes=0)

        positions: dict[int, dict[str, float]] = defaultdict(lambda: defaultdict(float))
        weights: dict[int, float] = defaultdict(float)
        for reading in bucket:
            weight = max(reading.confidence, 0.01)
            for index, char in enumerate(reading.text):
                positions[index][char] += weight
                weights[index] += weight

        voted: list[str] = []
        agreements: list[float] = []
        for index in sorted(positions):
            char, score = max(positions[index].items(), key=lambda item: item[1])
            voted.append(char)
            agreements.append(score / weights[index] if weights[index] else 0.0)

        agreement = sum(agreements) / len(agreements) if agreements else 0.0
        confidence = sum(reading.confidence for reading in bucket) / len(bucket)
        return ConsensusResult(
            text="".join(voted),
            confidence=confidence,
            agreement=agreement,
            votes=len(bucket),
        )

    def flush(self, key: str) -> None:
        """Forget the readings of a key (used after an event is published)."""
        self._readings.pop(key, None)


@dataclass
class DeduplicationResult:
    """Decision of the de-duplicator for a candidate event."""

    duplicate: bool
    key: str
    seconds_since_previous: float | None = None


class EventDeduplicator:
    """Suppresses repeated events for the same vehicle within a time window."""

    def __init__(self, window_seconds: float = 30.0, clock: object = time) -> None:
        self.window_seconds = window_seconds
        self._clock = clock
        self._last_seen: dict[str, float] = {}

    @staticmethod
    def build_key(gate_id: str, kind: str, normalized_text: str) -> str:
        """Key used to decide whether two readings describe the same vehicle."""
        return f"{gate_id}|{kind}|{normalized_text.strip().upper()}"

    def check(
        self, gate_id: str, kind: str, normalized_text: str, now: float | None = None
    ) -> DeduplicationResult:
        """Return whether the candidate is a duplicate of a recent event."""
        moment = self._clock.time() if now is None else now
        key = self.build_key(gate_id, kind, normalized_text)
        previous = self._last_seen.get(key)
        if previous is not None and (moment - previous) < self.window_seconds:
            return DeduplicationResult(
                duplicate=True, key=key, seconds_since_previous=moment - previous
            )
        self._last_seen[key] = moment
        return DeduplicationResult(duplicate=False, key=key, seconds_since_previous=None)

    def purge(self, now: float | None = None) -> None:
        """Drop expired keys so the dictionary does not grow without bound."""
        moment = self._clock.time() if now is None else now
        self._last_seen = {
            key: value
            for key, value in self._last_seen.items()
            if (moment - value) < self.window_seconds
        }
