"""Temporal consensus and de-duplication tests."""

from __future__ import annotations

from app.pipeline.consensus import EventDeduplicator, Reading, TemporalConsensus


def test_single_reading_is_accepted() -> None:
    """One reading alone has full agreement."""
    consensus = TemporalConsensus(window_seconds=1.5)
    result = consensus.add("gate|PLATE", Reading(text="ABC123", confidence=0.9, timestamp=100.0))
    assert result.text == "ABC123"
    assert result.votes == 1
    assert result.agreement == 1.0
    assert result.accepted is True


def test_consensus_votes_character_by_character() -> None:
    """The winning character of each position is the one with more weight."""
    consensus = TemporalConsensus(window_seconds=10.0)
    consensus.add("k", Reading(text="ABC123", confidence=0.9, timestamp=100.0))
    result = consensus.add("k", Reading(text="ABC124", confidence=0.3, timestamp=100.1))
    assert result.text == "ABC123"
    assert result.votes == 2
    # 5 of 6 characters agree fully, the last one only with the weak reading.
    assert 0.9 < result.agreement < 1.0
    assert result.accepted is True


def test_conflicting_readings_are_rejected() -> None:
    """Two readings with nothing in common do not reach the threshold."""
    consensus = TemporalConsensus(window_seconds=10.0, min_agreement=0.6)
    consensus.add("k", Reading(text="ABC123", confidence=0.9, timestamp=100.0))
    result = consensus.add("k", Reading(text="XYZ789", confidence=0.9, timestamp=100.1))
    assert result.agreement < 0.6
    assert result.accepted is False


def test_readings_outside_the_window_are_forgotten() -> None:
    """Only the readings of the sliding window take part in the vote."""
    consensus = TemporalConsensus(window_seconds=1.0)
    consensus.add("k", Reading(text="ABC123", confidence=0.9, timestamp=100.0))
    result = consensus.add("k", Reading(text="XYZ789", confidence=0.9, timestamp=102.0))
    assert result.votes == 1
    assert result.text == "XYZ789"


def test_the_window_keeps_at_most_max_readings() -> None:
    """The bucket never grows without bound."""
    consensus = TemporalConsensus(window_seconds=1000.0, max_readings=3)
    for index in range(6):
        consensus.add("k", Reading(text="ABC123", confidence=0.5, timestamp=100.0 + index * 0.01))
    assert consensus.result("k").votes == 3


def test_flush_clears_the_key() -> None:
    """After publishing, the votes of that key are dropped."""
    consensus = TemporalConsensus(window_seconds=100.0)
    consensus.add("k", Reading(text="ABC123", confidence=0.9, timestamp=100.0))
    consensus.flush("k")
    assert consensus.result("k").votes == 0


def test_deduplicator_suppresses_repeated_events() -> None:
    """The same vehicle within the window produces a single event."""
    deduplicator = EventDeduplicator(window_seconds=30.0)
    first = deduplicator.check("gate-1", "PLATE", "ABC123", now=1000.0)
    second = deduplicator.check("gate-1", "PLATE", "ABC123", now=1005.0)
    assert first.duplicate is False
    assert second.duplicate is True
    assert second.seconds_since_previous == pytest_approx(5.0)


def test_deduplicator_accepts_the_event_after_the_window() -> None:
    """Once the window elapsed, the same vehicle is published again."""
    deduplicator = EventDeduplicator(window_seconds=30.0)
    deduplicator.check("gate-1", "PLATE", "ABC123", now=1000.0)
    later = deduplicator.check("gate-1", "PLATE", "ABC123", now=1031.0)
    assert later.duplicate is False


def test_deduplicator_separates_kinds_and_gates() -> None:
    """A plate and a container code are never the same event."""
    deduplicator = EventDeduplicator(window_seconds=30.0)
    deduplicator.check("gate-1", "PLATE", "ABC123", now=1000.0)
    assert deduplicator.check("gate-1", "CONTAINER", "ABC123", now=1001.0).duplicate is False
    assert deduplicator.check("gate-2", "PLATE", "ABC123", now=1001.0).duplicate is False


def test_deduplicator_purges_expired_keys() -> None:
    """Old keys are removed to keep the memory bounded."""
    deduplicator = EventDeduplicator(window_seconds=30.0)
    deduplicator.check("gate-1", "PLATE", "ABC123", now=1000.0)
    deduplicator.purge(now=1100.0)
    assert deduplicator.check("gate-1", "PLATE", "ABC123", now=1101.0).duplicate is False


def pytest_approx(value: float):
    """Small helper to avoid importing pytest in a module level helper."""
    import pytest

    return pytest.approx(value)
