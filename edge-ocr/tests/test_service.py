"""Recognition pipeline tests (detection -> OCR -> validation -> event)."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from app.config import Settings
from app.infra.evidence import EvidenceStore
from app.models import ReadingKind
from app.pipeline.detector import Detection, ModelUnavailableError
from app.pipeline.service import RecognitionService

from .conftest import FakeDetector, FakeOcr


class UnavailableDetector:
    """Detector that behaves like a runtime without the weights."""

    @property
    def available(self) -> bool:
        return False

    def detect(self, image):  # type: ignore[no-untyped-def]
        raise ModelUnavailableError("detector weights not found")


class RecordingPublisher:
    """Publisher that only records the events."""

    def __init__(self) -> None:
        self.events: list[dict[str, Any]] = []

    def enqueue(self, payload: dict[str, Any]) -> int:
        self.events.append(payload)
        return len(self.events)


def build_service(
    settings: Settings,
    text: str,
    kind: str = "CONTAINER",
    confidence: float = 0.95,
    publisher: RecordingPublisher | None = None,
    evidence: EvidenceStore | None = None,
) -> RecognitionService:
    """Build a service with fake models."""
    detector = FakeDetector(
        [Detection(kind=kind, points=[10.0, 10.0, 110.0, 10.0, 110.0, 40.0, 10.0, 40.0], score=0.9)]
    )
    ocr = FakeOcr(text=text, confidence=confidence)
    return RecognitionService(
        settings,
        detector=detector,
        ocr=ocr,
        publisher=publisher,
        evidence=evidence,
    )


def test_valid_code_is_published_with_the_check_digit_validated(
    settings: Settings, sample_image
) -> None:
    """A correct reading produces a valid event."""
    publisher = RecordingPublisher()
    service = build_service(settings, "CSQU3054383", publisher=publisher)

    response = service.recognize(sample_image)

    assert len(response.events) == 1
    event = response.events[0]
    assert event.kind == ReadingKind.CONTAINER
    assert event.normalizedText == "CSQU3054383"
    assert event.isoValid is True
    assert event.corrected is False
    assert event.rawText == "CSQU3054383"
    assert event.gateId == settings.gate_id
    assert event.modelVersion
    assert len(publisher.events) == 1


def test_a_single_confusion_is_corrected(settings: Settings, sample_image) -> None:
    """A serial digit misread as another digit is repaired and flagged."""
    publisher = RecordingPublisher()
    service = build_service(settings, "CSQU3054883", publisher=publisher)

    response = service.recognize(sample_image)

    assert len(response.events) == 1
    event = response.events[0]
    assert event.corrected is True
    assert event.isoValid is True
    assert event.normalizedText == "CSQU3054383"


def test_an_unrepairable_reading_is_published_as_invalid(settings: Settings, sample_image) -> None:
    """A reading that cannot be fixed keeps isoValid=False for the review queue."""
    publisher = RecordingPublisher()
    # The check digit was read as a 4: no confusable digit restores the code.
    service = build_service(settings, "CSQU3054384", publisher=publisher)

    response = service.recognize(sample_image)

    assert len(response.events) == 1
    event = response.events[0]
    assert event.isoValid is False
    assert event.corrected is False
    assert event.normalizedText == "CSQU3054384"


def test_plates_are_normalised(settings: Settings, sample_image) -> None:
    """Plates lose their separators and are uppercased."""
    publisher = RecordingPublisher()
    service = build_service(settings, "abc-123", kind="PLATE", publisher=publisher)

    response = service.recognize(sample_image)

    assert len(response.events) == 1
    event = response.events[0]
    assert event.kind == ReadingKind.PLATE
    assert event.normalizedText == "ABC123"
    assert event.isoValid is None


def test_a_reading_that_does_not_look_like_a_code_is_dropped(
    settings: Settings, sample_image
) -> None:
    """Text that cannot be a BIC code never becomes an event."""
    publisher = RecordingPublisher()
    service = build_service(settings, "??", publisher=publisher)

    response = service.recognize(sample_image)

    assert response.events == []
    assert publisher.events == []


def test_duplicated_readings_are_suppressed(settings: Settings, sample_image) -> None:
    """The same vehicle does not produce two events."""
    publisher = RecordingPublisher()
    service = build_service(settings, "CSQU3054383", publisher=publisher)

    first = service.recognize(sample_image)
    second = service.recognize(sample_image)

    assert len(first.events) == 1
    assert second.events == []
    assert len(publisher.events) == 1


def test_the_evidence_is_stored(settings: Settings, tmp_path: Path, sample_image) -> None:
    """The rectified crop is kept as evidence (ADR-004)."""
    store = EvidenceStore(tmp_path / "evidence", retention_days=1, blur_faces=False)
    service = build_service(settings, "CSQU3054383", evidence=store)

    response = service.recognize(sample_image)

    assert response.events[0].evidenceImagePath
    assert store.list_files()


def test_the_service_reports_a_degraded_state_without_models(
    settings: Settings, sample_image
) -> None:
    """Without the model runtime the service answers instead of failing."""
    service = build_service(settings, "CSQU3054383")
    service.detector = UnavailableDetector()
    service.ocr = FakeOcr(available=False)

    response = service.recognize(sample_image)

    assert response.degraded is True
    assert response.events == []


def test_the_size_type_code_next_to_the_bic_is_ignored(settings: Settings, sample_image) -> None:
    """The size/type code printed next to the BIC does not pollute the reading."""
    publisher = RecordingPublisher()
    service = build_service(settings, "CSQU3054383 45G1", publisher=publisher)

    response = service.recognize(sample_image)

    assert response.events[0].normalizedText == "CSQU3054383"
