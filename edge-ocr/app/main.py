"""FastAPI application factory."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import __version__
from .api.routes import router
from .api.streams import StreamManager
from .config import Settings, get_settings
from .infra.evidence import EvidenceStore
from .infra.metrics import REGISTRY
from .infra.outbox import DurableOutbox
from .infra.publisher import EventPublisher
from .pipeline.service import RecognitionService

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger(__name__)


def build_components(settings: Settings) -> dict[str, object]:
    """Create the collaborators of the service (no side effects on the app)."""
    outbox = DurableOutbox(settings.outbox_path)
    publisher = EventPublisher(settings, outbox)
    evidence = EvidenceStore(
        settings.evidence_dir,
        retention_days=settings.evidence_retention_days,
        blur_faces=settings.face_blur_enabled,
        max_bytes=settings.evidence_max_bytes,
    )
    service = RecognitionService(settings, evidence=evidence, publisher=publisher)
    streams = StreamManager(settings, service)
    return {
        "outbox": outbox,
        "publisher": publisher,
        "evidence": evidence,
        "service": service,
        "streams": streams,
    }


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the application.

    The collaborators (outbox, publisher, evidence store, models) are created
    during the startup event, so importing the module never touches the disk.
    """
    resolved = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        components = build_components(resolved)
        for name, value in components.items():
            setattr(app.state, name, value)
        app.state.settings = resolved
        publisher = components["publisher"]
        service = components["service"]
        publisher.start()  # type: ignore[union-attr]
        REGISTRY.set_gauge("edge_ocr_model_available", 1.0 if service.models_available else 0.0)  # type: ignore[union-attr]
        logger.info("edge-ocr started (models available: %s)", service.models_available)  # type: ignore[union-attr]
        try:
            yield
        finally:
            publisher.stop()  # type: ignore[union-attr]
            components["outbox"].close()  # type: ignore[union-attr]

    app = FastAPI(
        title="LogisticaPortuaria CR - Edge OCR",
        description=(
            "Reconocimiento de matriculas y codigos BIC en el borde. "
            "Publica eventos en `yard/{yardId}/gate/{gateId}/events` y los reintenta por HTTP."
        ),
        version=__version__,
        lifespan=lifespan,
    )
    app.state.settings = resolved
    app.include_router(router)
    return app


app = create_app()
