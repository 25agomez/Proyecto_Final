"""HTTP API of the edge OCR service."""

from __future__ import annotations

import io
import logging
import time

from fastapi import APIRouter, File, HTTPException, Request, Response, UploadFile, status
from PIL import Image, UnidentifiedImageError

from ..config import Settings
from ..infra.metrics import REGISTRY
from ..models import (
    GateEvent,
    HealthResponse,
    RecognizeResponse,
    StreamRequest,
    StreamStatus,
)

logger = logging.getLogger(__name__)

router = APIRouter()

START_TIME = time.time()


def _service(request: Request):
    return request.app.state.service


def _manager(request: Request):
    return request.app.state.streams


def _settings(request: Request) -> Settings:
    return request.app.state.settings


@router.get("/healthz", response_model=HealthResponse, tags=["health"])
def healthz(request: Request) -> HealthResponse:
    """Liveness and readiness of the service."""
    service = _service(request)
    settings = _settings(request)
    outbox = request.app.state.outbox
    return HealthResponse(
        status="ok",
        service=settings.service_name,
        version=request.app.version,
        modelsAvailable=service.models_available,
        detectorAvailable=bool(getattr(service.detector, "available", False)),
        ocrAvailable=bool(getattr(service.ocr, "available", False)),
        outboxPending=outbox.pending_count(),
        uptimeSeconds=time.time() - START_TIME,
    )


@router.get("/metrics", tags=["health"])
def metrics(request: Request) -> Response:
    """Prometheus metrics."""
    request.app.state.outbox and REGISTRY.set_gauge(
        "edge_ocr_outbox_pending", float(request.app.state.outbox.pending_count())
    )
    return Response(content=REGISTRY.render(), media_type="text/plain; version=0.0.4")


@router.post(
    "/v1/recognize",
    response_model=RecognizeResponse,
    status_code=status.HTTP_200_OK,
    tags=["recognition"],
)
async def recognize(request: Request, image: UploadFile = File(...)) -> RecognizeResponse:
    """Recognise the plates and container codes of an image."""
    settings = _settings(request)
    payload = await image.read()
    if len(payload) > settings.max_upload_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="the uploaded image exceeds the configured size limit",
        )
    try:
        frame = Image.open(io.BytesIO(payload))
        frame.load()
    except (UnidentifiedImageError, OSError) as error:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail=f"unreadable image: {error}"
        ) from error

    service = _service(request)
    response = service.recognize(frame)
    REGISTRY.increment("edge_ocr_frames_processed_total")
    REGISTRY.increment("edge_ocr_events_published_total", len(response.events))
    REGISTRY.set_gauge("edge_ocr_latency_ms", float(response.latencyMs))
    return response


@router.post(
    "/v1/streams",
    response_model=StreamStatus,
    status_code=status.HTTP_201_CREATED,
    tags=["streams"],
)
def create_stream(request: Request, payload: StreamRequest) -> StreamStatus:
    """Register an RTSP stream."""
    manager = _manager(request)
    stream = manager.register(
        url=payload.url,
        gate_id=payload.gateId,
        fps=payload.fps or _settings(request).stream_default_fps,
        roi=payload.roi,
    )
    return stream.to_status()


@router.get("/v1/streams", response_model=list[StreamStatus], tags=["streams"])
def list_streams(request: Request) -> list[StreamStatus]:
    """List the registered streams."""
    return _manager(request).list()


@router.delete("/v1/streams/{stream_id}", status_code=status.HTTP_204_NO_CONTENT, tags=["streams"])
def delete_stream(request: Request, stream_id: str) -> Response:
    """Stop a registered stream."""
    if not _manager(request).remove(stream_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="unknown stream")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post(
    "/v1/events",
    response_model=GateEvent,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["recognition"],
)
def ingest_event(request: Request, event: GateEvent) -> GateEvent:
    """Publish an externally produced event (used by the simulators)."""
    publisher = request.app.state.publisher
    publisher.enqueue(event.model_dump(mode="json"))
    REGISTRY.increment("edge_ocr_events_published_total")
    return event
