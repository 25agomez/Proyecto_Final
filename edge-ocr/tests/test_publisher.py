"""Publisher tests: MQTT topic, HTTP delivery and retries."""

from __future__ import annotations

import time
from typing import Any

from app.config import Settings
from app.infra.outbox import DurableOutbox
from app.infra.publisher import EventPublisher


class FakeMqttClient:
    """Records the published messages."""

    def __init__(self, fail: bool = False) -> None:
        self.fail = fail
        self.messages: list[tuple[str, str]] = []

    def publish(self, topic: str, payload: str, qos: int = 0) -> None:
        if self.fail:
            raise ConnectionError("broker unreachable")
        self.messages.append((topic, payload))

    def connect(self, *args: Any, **kwargs: Any) -> None:  # pragma: no cover - not used
        return None

    def loop_start(self) -> None:  # pragma: no cover - not used
        return None


class FakeHttpClient:
    """Minimal httpx.Client replacement."""

    def __init__(self, status_code: int = 200) -> None:
        self.status_code = status_code
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def post(
        self,
        url: str,
        json: dict[str, Any],
        headers: dict[str, str] | None = None,
        **kwargs: Any,
    ) -> Any:
        self.calls.append((url, json, headers or {}))

        class Response:
            def __init__(self, code: int) -> None:
                self.status_code = code

        return Response(self.status_code)


def make_publisher(
    tmp_path, mqtt: FakeMqttClient | None = None, http: FakeHttpClient | None = None
):
    """Build a publisher with fakes and a temporary outbox."""
    settings = Settings(
        outbox_path=str(tmp_path / "outbox.db"),
        mqtt_enabled=True,
        mqtt_host="localhost",
        mqtt_port=1883,
        backend_base_url="http://backend:8080",
        yard_id="yard-1",
    )
    outbox = DurableOutbox(settings.outbox_path)
    publisher = EventPublisher(
        settings,
        outbox,
        mqtt_client=mqtt or FakeMqttClient(),
        http_client=http or FakeHttpClient(),
    )
    return publisher, outbox


def event_payload(event_id: str = "evt-1", gate_id: str = "gate-9") -> dict[str, Any]:
    """A minimal event."""
    return {
        "eventId": event_id,
        "gateId": gate_id,
        "kind": "CONTAINER",
        "normalizedText": "CSQU3054383",
    }


def test_topic_follows_the_contract(tmp_path) -> None:
    """The MQTT topic is yard/{yardId}/gate/{gateId}/events."""
    publisher, _ = make_publisher(tmp_path)
    assert publisher.topic_for("gate-9") == "yard/yard-1/gate/gate-9/events"


def test_event_is_delivered_to_both_channels(tmp_path) -> None:
    """A published event reaches MQTT and the backend."""
    mqtt = FakeMqttClient()
    http = FakeHttpClient()
    publisher, outbox = make_publisher(tmp_path, mqtt, http)

    publisher.enqueue(event_payload())

    assert len(mqtt.messages) == 1
    topic, body = mqtt.messages[0]
    assert topic == "yard/yard-1/gate/gate-9/events"
    assert "CSQU3054383" in body
    assert http.calls[0][0] == "http://backend:8080/api/v1/gate-events"
    assert outbox.pending_count() == 0


def test_a_failing_backend_keeps_the_event_pending(tmp_path) -> None:
    """When the backend is down the event stays in the outbox for a retry."""
    mqtt = FakeMqttClient()
    http = FakeHttpClient(status_code=503)
    publisher, outbox = make_publisher(tmp_path, mqtt, http)

    publisher.enqueue(event_payload())

    assert len(mqtt.messages) == 1
    assert outbox.pending_count() == 1
    item = outbox.get(1)
    assert item is not None
    assert item.http_sent is False
    assert item.mqtt_sent is True
    assert item.last_error == "HTTP 503"


def test_a_failing_broker_keeps_the_event_pending(tmp_path) -> None:
    """When MQTT fails the event is not lost: it is retried later."""
    mqtt = FakeMqttClient(fail=True)
    http = FakeHttpClient()
    publisher, outbox = make_publisher(tmp_path, mqtt, http)

    publisher.enqueue(event_payload())

    assert outbox.pending_count() == 1
    item = outbox.get(1)
    assert item is not None
    assert item.mqtt_sent is False
    assert item.http_sent is True


def test_the_worker_retries_pending_events(tmp_path) -> None:
    """The background worker delivers the events that were left behind."""
    mqtt = FakeMqttClient()
    http = FakeHttpClient(status_code=500)
    publisher, outbox = make_publisher(tmp_path, mqtt, http)

    publisher.enqueue(event_payload())
    assert outbox.pending_count() == 1
    item = outbox.get(1)
    assert item is not None
    # The failed attempt was scheduled with an exponential backoff.
    assert item.next_attempt_at > time.time()
    assert item.attempts == 1
    assert item.last_error == "HTTP 500"

    # The backend is back: the event is delivered on the next attempt.
    http.status_code = 200
    outbox.reschedule(1, time.time() - 1, None)
    publisher._attempt(outbox.due())
    assert outbox.pending_count() == 0


def test_publisher_sends_edge_api_key_header(tmp_path) -> None:
    """When configured, X-Edge-Api-Key header is attached to the HTTP delivery."""
    mqtt = FakeMqttClient()
    http = FakeHttpClient()
    settings = Settings(
        outbox_path=str(tmp_path / "outbox.db"),
        mqtt_enabled=False,
        backend_base_url="http://backend:8080",
        backend_api_key="secret-key-123",
        yard_id="yard-1",
    )
    outbox = DurableOutbox(settings.outbox_path)
    publisher = EventPublisher(settings, outbox, mqtt, http)

    publisher.enqueue(event_payload())

    assert len(http.calls) == 1
    url, json_data, headers = http.calls[0]
    assert url == "http://backend:8080/api/v1/gate-events"
    assert headers.get("X-Edge-Api-Key") == "secret-key-123"
