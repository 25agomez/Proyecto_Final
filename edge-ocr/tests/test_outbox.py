"""Durable outbox tests: no event is lost and retries are scheduled."""

from __future__ import annotations

import time

from app.infra.outbox import DurableOutbox


def payload(event_id: str = "evt-1") -> dict[str, object]:
    """A minimal event payload."""
    return {"eventId": event_id, "gateId": "gate-1", "kind": "PLATE", "normalizedText": "ABC123"}


def test_enqueue_makes_the_item_due(tmp_path) -> None:
    """A new event is immediately available for delivery."""
    outbox = DurableOutbox(tmp_path / "outbox.db")
    item_id = outbox.enqueue(payload())
    items = outbox.due()
    assert len(items) == 1
    assert items[0].id == item_id
    assert items[0].payload["normalizedText"] == "ABC123"
    assert outbox.pending_count() == 1


def test_enqueue_is_idempotent_by_event_id(tmp_path) -> None:
    """The same event is never stored twice."""
    outbox = DurableOutbox(tmp_path / "outbox.db")
    first = outbox.enqueue(payload())
    second = outbox.enqueue(payload())
    assert first == second
    assert len(outbox.due()) == 1


def test_an_event_survives_a_restart(tmp_path) -> None:
    """The queue is persisted in SQLite: nothing is lost when the process dies."""
    path = tmp_path / "outbox.db"
    outbox = DurableOutbox(path)
    outbox.enqueue(payload("evt-persist"))
    outbox.close()

    reopened = DurableOutbox(path)
    items = reopened.due()
    assert len(items) == 1
    assert items[0].event_id == "evt-persist"


def test_channels_are_tracked_independently(tmp_path) -> None:
    """MQTT and HTTP are confirmed separately."""
    outbox = DurableOutbox(tmp_path / "outbox.db")
    item_id = outbox.enqueue(payload())
    outbox.mark_channel(item_id, "mqtt", True)
    item = outbox.get(item_id)
    assert item is not None
    assert item.mqtt_sent is True
    assert item.http_sent is False
    assert outbox.pending_count() == 1

    outbox.mark_channel(item_id, "http", True)
    assert outbox.pending_count() == 0
    assert outbox.due() == []


def test_failed_delivery_is_rescheduled(tmp_path) -> None:
    """A failed attempt moves the event to a later attempt."""
    outbox = DurableOutbox(tmp_path / "outbox.db")
    item_id = outbox.enqueue(payload())
    outbox.mark_channel(item_id, "http", False, "connection refused")
    outbox.reschedule(item_id, time.time() + 60, "connection refused", attempts=1)
    assert outbox.due() == []
    item = outbox.get(item_id)
    assert item is not None
    assert item.last_error == "connection refused"
    assert item.attempts == 1


def test_purge_removes_only_delivered_items(tmp_path) -> None:
    """Delivered events are dropped, pending ones are kept."""
    outbox = DurableOutbox(tmp_path / "outbox.db")
    delivered = outbox.enqueue(payload("evt-done"))
    pending = outbox.enqueue(payload("evt-pending"))
    outbox.mark_channel(delivered, "mqtt", True)
    outbox.mark_channel(delivered, "http", True)
    removed = outbox.purge_complete(older_than=time.time() + 1)
    assert removed == 1
    assert outbox.pending_count() == 1
    assert outbox.get(pending) is not None
