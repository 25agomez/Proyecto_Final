"""Durable outbox: events are persisted before they leave the edge.

The edge must never lose a reading, even if the backend and the broker are down.
Every event is written to SQLite first and only removed once both delivery
channels (MQTT and HTTP) accepted it.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

_SCHEMA = """
CREATE TABLE IF NOT EXISTS outbox (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id       TEXT NOT NULL UNIQUE,
    payload        TEXT NOT NULL,
    attempts       INTEGER NOT NULL DEFAULT 0,
    mqtt_sent      INTEGER NOT NULL DEFAULT 0,
    http_sent      INTEGER NOT NULL DEFAULT 0,
    created_at     REAL NOT NULL,
    next_attempt_at REAL NOT NULL,
    last_error     TEXT
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (mqtt_sent, http_sent, next_attempt_at);
"""


@dataclass
class OutboxItem:
    """An event waiting to be delivered."""

    id: int
    event_id: str
    payload: dict[str, Any]
    attempts: int
    mqtt_sent: bool
    http_sent: bool
    created_at: float
    next_attempt_at: float
    last_error: str | None

    @property
    def complete(self) -> bool:
        """True when both channels accepted the event."""
        return self.mqtt_sent and self.http_sent


class DurableOutbox:
    """SQLite backed queue with exponential backoff."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        if str(self.path) != ":memory:":
            self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._connection = sqlite3.connect(str(self.path), check_same_thread=False)
        self._connection.execute("PRAGMA journal_mode=WAL")
        self._connection.execute("PRAGMA synchronous=NORMAL")
        with self._lock:
            self._connection.executescript(_SCHEMA)
            self._connection.commit()

    def enqueue(self, payload: dict[str, Any], now: float | None = None) -> int:
        """Persist an event; returns the row id (idempotent by ``eventId``)."""
        moment = time.time() if now is None else now
        event_id = str(payload.get("eventId", ""))
        body = json.dumps(payload, default=str)
        with self._lock:
            cursor = self._connection.execute(
                "INSERT INTO outbox (event_id, payload, created_at, next_attempt_at) "
                "VALUES (?, ?, ?, ?) ON CONFLICT(event_id) DO NOTHING",
                (event_id, body, moment, moment),
            )
            self._connection.commit()
            if cursor.lastrowid and cursor.rowcount:
                return int(cursor.lastrowid)
            row = self._connection.execute(
                "SELECT id FROM outbox WHERE event_id = ?", (event_id,)
            ).fetchone()
            return int(row[0]) if row else 0

    @staticmethod
    def _to_item(row: Any) -> OutboxItem:
        """Build an item from a database row."""
        return OutboxItem(
            id=int(row[0]),
            event_id=str(row[1]),
            payload=json.loads(row[2]),
            attempts=int(row[3]),
            mqtt_sent=bool(row[4]),
            http_sent=bool(row[5]),
            created_at=float(row[6]),
            next_attempt_at=float(row[7]),
            last_error=row[8],
        )

    _SELECT = (
        "SELECT id, event_id, payload, attempts, mqtt_sent, http_sent, "
        "created_at, next_attempt_at, last_error FROM outbox"
    )

    def due(self, limit: int = 25, now: float | None = None) -> list[OutboxItem]:
        """Return the items that are ready to be retried."""
        moment = time.time() if now is None else now
        with self._lock:
            rows = self._connection.execute(
                f"{self._SELECT} WHERE (mqtt_sent = 0 OR http_sent = 0) AND next_attempt_at <= ? "
                "ORDER BY id LIMIT ?",
                (moment, limit),
            ).fetchall()
        return [self._to_item(row) for row in rows]

    def get(self, item_id: int) -> OutboxItem | None:
        """Return a single item by its identifier."""
        with self._lock:
            row = self._connection.execute(f"{self._SELECT} WHERE id = ?", (item_id,)).fetchone()
        return self._to_item(row) if row else None

    def mark_channel(
        self, item_id: int, channel: str, success: bool, error: str | None = None
    ) -> None:
        """Record the outcome of a delivery channel."""
        if channel not in {"mqtt", "http"}:
            raise ValueError(f"unknown channel: {channel}")
        column = "mqtt_sent" if channel == "mqtt" else "http_sent"
        with self._lock:
            self._connection.execute(
                f"UPDATE outbox SET {column} = ?, last_error = ? WHERE id = ?",
                (1 if success else 0, error, item_id),
            )
            self._connection.commit()

    def reschedule(
        self,
        item_id: int,
        next_attempt_at: float,
        error: str | None = None,
        attempts: int | None = None,
    ) -> None:
        """Move an item to a later attempt."""
        if attempts is None:
            with self._lock:
                self._connection.execute(
                    "UPDATE outbox SET next_attempt_at = ?, last_error = ? WHERE id = ?",
                    (next_attempt_at, error, item_id),
                )
                self._connection.commit()
            return
        with self._lock:
            self._connection.execute(
                "UPDATE outbox SET next_attempt_at = ?, last_error = ?, attempts = ? WHERE id = ?",
                (next_attempt_at, error, attempts, item_id),
            )
            self._connection.commit()

    def purge_complete(self, older_than: float | None = None) -> int:
        """Delete delivered items (keeps the table small)."""
        threshold = time.time() if older_than is None else older_than
        with self._lock:
            cursor = self._connection.execute(
                "DELETE FROM outbox WHERE mqtt_sent = 1 AND http_sent = 1 AND created_at < ?",
                (threshold,),
            )
            self._connection.commit()
            return cursor.rowcount or 0

    def pending_count(self) -> int:
        """Number of events that were not delivered completely."""
        with self._lock:
            row = self._connection.execute(
                "SELECT count(*) FROM outbox WHERE mqtt_sent = 0 OR http_sent = 0"
            ).fetchone()
        return int(row[0]) if row else 0

    def close(self) -> None:
        """Close the database connection."""
        with self._lock:
            self._connection.close()
