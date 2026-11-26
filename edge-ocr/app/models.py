"""Pydantic models of the service (they mirror edge-ocr/schemas/gate-event.schema.json)."""

from __future__ import annotations

from datetime import UTC, datetime
from enum import Enum
from uuid import uuid4

from pydantic import BaseModel, ConfigDict, Field, field_validator


class ReadingKind(str, Enum):
    """Type of identifier that was read."""

    PLATE = "PLATE"
    CONTAINER = "CONTAINER"


class BoundingBox(BaseModel):
    """Oriented bounding box (OBB) in pixels of the original frame."""

    points: list[float] = Field(..., min_length=8, max_length=8)
    score: float | None = Field(default=None, ge=0.0, le=1.0)


class GateEvent(BaseModel):
    """Event published to the backend and to MQTT."""

    model_config = ConfigDict(populate_by_name=True)

    eventId: str = Field(default_factory=lambda: str(uuid4()))
    yardId: str | None = None
    gateId: str
    timestamp: datetime = Field(default_factory=lambda: datetime.now(UTC))
    kind: ReadingKind
    rawText: str | None = None
    normalizedText: str | None = None
    confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    isoValid: bool | None = None
    corrected: bool = False
    bbox: BoundingBox | None = None
    evidenceImagePath: str | None = None
    modelVersion: str | None = None
    latencyMs: int | None = Field(default=None, ge=0)

    @field_validator("timestamp")
    @classmethod
    def _ensure_utc(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)


class StreamRequest(BaseModel):
    """Registration of an RTSP stream."""

    streamId: str | None = None
    url: str
    gateId: str
    fps: int = Field(default=5, ge=1, le=30)
    roi: list[float] | None = Field(default=None, description="x, y, w, h in pixels")
    kinds: list[ReadingKind] = Field(
        default_factory=lambda: [ReadingKind.PLATE, ReadingKind.CONTAINER]
    )


class StreamStatus(BaseModel):
    """State of a registered stream."""

    streamId: str
    url: str
    gateId: str
    fps: int
    status: str
    framesProcessed: int = 0
    eventsPublished: int = 0
    lastError: str | None = None
    registeredAt: datetime = Field(default_factory=lambda: datetime.now(UTC))


class RecognizeResponse(BaseModel):
    """Answer of POST /v1/recognize."""

    events: list[GateEvent]
    latencyMs: int
    modelVersion: str | None = None
    degraded: bool = Field(default=False, description="True when no model was available")


class HealthResponse(BaseModel):
    """Answer of GET /healthz."""

    status: str
    service: str
    version: str
    modelsAvailable: bool
    detectorAvailable: bool
    ocrAvailable: bool
    outboxPending: int
    uptimeSeconds: float
