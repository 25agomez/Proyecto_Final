"""Delivery of the events: MQTT + HTTP, with retries taken from the durable outbox."""

from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any

import httpx

from ..config import Settings
from .outbox import DurableOutbox, OutboxItem

logger = logging.getLogger(__name__)


class EventPublisher:
    """Publishes the events of the outbox to MQTT and to the backend."""

    def __init__(
        self,
        settings: Settings,
        outbox: DurableOutbox,
        mqtt_client: Any | None = None,
        http_client: httpx.Client | None = None,
    ) -> None:
        self.settings = settings
        self.outbox = outbox
        self._mqtt = mqtt_client
        self._mqtt_connected = False
        self._http = http_client or httpx.Client(timeout=10.0)
        self._stop = threading.Event()
        self._worker: threading.Thread | None = None
        self._warned_mqtt = False

    # -- MQTT ---------------------------------------------------------------
    @property
    def mqtt_available(self) -> bool:
        """True when a broker client is usable."""
        if not self.settings.mqtt_enabled:
            return False
        if self._mqtt is not None:
            return True
        try:
            import paho.mqtt.client as mqtt  # noqa: F401
        except ImportError:  # pragma: no cover - optional dependency
            return False
        return True

    def _ensure_mqtt(self) -> Any | None:
        """Lazily connect the MQTT client."""
        if self._mqtt is not None:
            return self._mqtt
        try:
            import paho.mqtt.client as mqtt
        except ImportError:  # pragma: no cover - optional dependency
            return None
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
        try:
            client.connect(self.settings.mqtt_host, self.settings.mqtt_port, keepalive=30)
            client.loop_start()
            self._mqtt_connected = True
        except Exception as error:  # pragma: no cover - depends on the broker
            logger.warning("MQTT connection failed: %s", error)
            return None
        self._mqtt = client
        return client

    def topic_for(self, gate_id: str) -> str:
        """MQTT topic of a gate event."""
        return self.settings.mqtt_topic_template.format(
            yard_id=self.settings.yard_id,
            gate_id=gate_id,
        )

    def _publish_mqtt(self, item: OutboxItem) -> tuple[bool, str | None]:
        """Publish an event to MQTT. Returns (success, error)."""
        if not self.settings.mqtt_enabled:
            return True, None
        client = self._ensure_mqtt()
        if client is None:
            if not self._warned_mqtt:
                logger.warning("MQTT client unavailable; events are delivered over HTTP only")
                self._warned_mqtt = True
            return True, None
        gate_id = str(item.payload.get("gateId", self.settings.gate_id))
        try:
            client.publish(self.topic_for(gate_id), json.dumps(item.payload), qos=1)
            return True, None
        except Exception as error:  # pragma: no cover - depends on the broker
            return False, str(error)

    # -- HTTP ---------------------------------------------------------------
    def _publish_http(self, item: OutboxItem) -> tuple[bool, str | None]:
        """POST an event to the backend. Returns (success, error)."""
        url = f"{self.settings.backend_base_url.rstrip('/')}/api/v1/gate-events"
        api_key = self.settings.backend_api_key or self.settings.edge_api_key
        kwargs: dict[str, Any] = {"json": item.payload}
        if api_key:
            kwargs["headers"] = {"X-Edge-Api-Key": api_key}
        try:
            response = self._http.post(url, **kwargs)
            if response.status_code < 300:
                return True, None
            return False, f"HTTP {response.status_code}"
        except Exception as error:
            return False, str(error)

    # -- Public API ---------------------------------------------------------
    def enqueue(self, payload: dict[str, Any]) -> int:
        """Persist an event and try to deliver it immediately."""
        item_id = self.outbox.enqueue(payload)
        item = self.outbox.get(item_id)
        if item is not None:
            self._attempt([item])
        return item_id

    def _attempt(self, items: list[OutboxItem]) -> None:
        """Try to deliver every due item."""
        for item in items:
            error = item.last_error
            mqtt_done = item.mqtt_sent
            http_done = item.http_sent
            if not mqtt_done:
                success, channel_error = self._publish_mqtt(item)
                self.outbox.mark_channel(item.id, "mqtt", success, channel_error)
                if success:
                    mqtt_done = True
                else:
                    error = channel_error
            if not http_done:
                success, channel_error = self._publish_http(item)
                self.outbox.mark_channel(item.id, "http", success, channel_error)
                if success:
                    http_done = True
                else:
                    error = channel_error
            if not (mqtt_done and http_done):
                attempts = item.attempts + 1
                delay = min(
                    self.settings.delivery_backoff_seconds * (2 ** min(attempts, 10)),
                    self.settings.delivery_backoff_max_seconds,
                )
                self.outbox.reschedule(item.id, time.time() + delay, error, attempts)

    def start(self) -> None:
        """Start the background delivery worker."""
        if self._worker is not None:
            return
        self._stop.clear()
        self._worker = threading.Thread(target=self._run, name="outbox-worker", daemon=True)
        self._worker.start()

    def stop(self) -> None:
        """Stop the background worker."""
        self._stop.set()
        if self._worker is not None:
            self._worker.join(timeout=5)
            self._worker = None

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                items = self.outbox.due(limit=self.settings.delivery_batch_size)
                if items:
                    self._attempt(items)
                else:
                    self.outbox.purge_complete(older_than=time.time() - 3600)
            except Exception:  # pragma: no cover - keep the worker alive
                logger.exception("outbox worker iteration failed")
            self._stop.wait(2.0)
