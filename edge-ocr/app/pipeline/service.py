"""Recognition pipeline: frame -> detections -> OCR -> validation -> event."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any, Protocol

from PIL import Image

from ..config import Settings
from ..models import BoundingBox, GateEvent, ReadingKind, RecognizeResponse
from . import postprocess
from .consensus import EventDeduplicator, Reading, TemporalConsensus
from .detector import Detection, ModelUnavailableError, YoloObbDetector
from .ocr import OcrReading, PaddleOcrEngine
from .preprocess import PreprocessOptions, crop_oriented, preprocess_crop

logger = logging.getLogger(__name__)


class DetectorProtocol(Protocol):
    """Minimal detector interface used by the service."""

    @property
    def available(self) -> bool: ...

    def detect(self, image: Image.Image) -> list[Detection]: ...


class OcrProtocol(Protocol):
    """Minimal OCR interface used by the service."""

    @property
    def available(self) -> bool: ...

    def read(self, image: Image.Image) -> OcrReading: ...


@dataclass
class Candidate:
    """A reading before validation."""

    kind: str
    raw_text: str
    confidence: float
    points: list[float]
    crop: Image.Image


class RecognitionService:
    """Orchestrates detection, OCR, normalisation, validation and publication."""

    def __init__(
        self,
        settings: Settings,
        detector: DetectorProtocol | None = None,
        ocr: OcrProtocol | None = None,
        evidence: Any | None = None,
        publisher: Any | None = None,
        consensus: TemporalConsensus | None = None,
        deduplicator: EventDeduplicator | None = None,
    ) -> None:
        self.settings = settings
        self.detector = detector or YoloObbDetector(
            f"{settings.models_dir}/{settings.detector_weights}",
            confidence=settings.detector_confidence,
        )
        self.ocr = ocr or PaddleOcrEngine(
            f"{settings.models_dir}/{settings.ocr_model_dir}",
            min_confidence=settings.ocr_min_confidence,
        )
        self.evidence = evidence
        self.publisher = publisher
        self.consensus = consensus or TemporalConsensus(
            window_seconds=settings.consensus_window_seconds,
            min_agreement=settings.consensus_min_agreement,
        )
        self.deduplicator = deduplicator or EventDeduplicator(
            window_seconds=settings.dedup_window_seconds
        )
        self.options = PreprocessOptions(
            clahe_clip_limit=settings.preprocess_clahe_clip_limit,
            binarize=settings.preprocess_binarize,
            sharpen=settings.preprocess_sharpen,
        )

    # -- helpers ------------------------------------------------------------
    @property
    def model_version(self) -> str:
        """Version string reported with every event."""
        return f"obb:{self.settings.detector_weights}|ocr:{self.settings.ocr_model_dir}"

    @property
    def models_available(self) -> bool:
        """True when both the detector and the OCR runtime are usable."""
        return bool(getattr(self.detector, "available", False)) and bool(
            getattr(self.ocr, "available", False)
        )

    def _read_crop(self, crop: Image.Image) -> OcrReading:
        """Read the best variant of a crop (the rotation matters for vertical codes)."""
        best = OcrReading(text="", confidence=0.0)
        for _angle, variant in preprocess_crop(crop, self.options):
            reading = self.ocr.read(variant)
            if reading.confidence > best.confidence:
                best = reading
            if best.confidence >= 0.95:
                break
        return best

    @staticmethod
    def extract_bic(text: str) -> str:
        """Extract the BIC code from a text that may carry the size/type code."""
        return postprocess.extract_bic(text)

    def _normalise(self, kind: str, raw_text: str) -> str:
        """Normalise the raw reading according to the kind of identifier."""
        return postprocess.normalise_reading(kind, raw_text)

    def _validate(self, kind: str, normalised: str) -> tuple[bool | None, bool, str]:
        """Return (iso_valid, corrected, final_text) for a normalised reading."""
        result = postprocess.validate_reading(kind, normalised)
        return result.iso_valid, result.corrected, result.text

    # -- main entry point ---------------------------------------------------
    def recognize(
        self,
        image: Image.Image,
        gate_id: str | None = None,
        kinds: list[ReadingKind] | None = None,
    ) -> RecognizeResponse:
        """Process a frame and publish the readings that pass the filters."""
        started = time.perf_counter()
        gate = gate_id or self.settings.gate_id
        allowed = {kind.value for kind in kinds} if kinds else None

        try:
            detections = self.detector.detect(image)
        except ModelUnavailableError as error:
            logger.warning("detector unavailable: %s", error)
            return RecognizeResponse(
                events=[],
                latencyMs=int((time.perf_counter() - started) * 1000),
                modelVersion=self.model_version,
                degraded=True,
            )

        events: list[GateEvent] = []
        for detection in detections:
            if allowed and detection.kind not in allowed:
                continue
            candidate = self._build_candidate(image, detection)
            if candidate is None:
                continue
            event = self._event_from_candidate(candidate, gate, started)
            if event is not None:
                events.append(event)

        return RecognizeResponse(
            events=events,
            latencyMs=int((time.perf_counter() - started) * 1000),
            modelVersion=self.model_version,
            degraded=not self.models_available,
        )

    def _build_candidate(self, image: Image.Image, detection: Detection) -> Candidate | None:
        """Crop, preprocess and read one detection."""
        try:
            crop = crop_oriented(image, detection.points)
        except Exception:  # pragma: no cover - malformed box
            logger.warning("could not crop detection", exc_info=True)
            return None
        reading = self._read_crop(crop)
        if not reading.text.strip():
            return None
        return Candidate(
            kind=detection.kind,
            raw_text=reading.text,
            confidence=reading.confidence,
            points=detection.points,
            crop=crop,
        )

    def _event_from_candidate(
        self, candidate: Candidate, gate_id: str, started: float
    ) -> GateEvent | None:
        """Validate, vote, de-duplicate and publish one candidate."""
        normalised = self._normalise(candidate.kind, candidate.raw_text)
        if not normalised:
            return None
        if not postprocess.is_probable(candidate.kind, normalised):
            return None

        iso_valid, corrected, final_text = self._validate(candidate.kind, normalised)

        key = f"{gate_id}|{candidate.kind}"
        consensus = self.consensus.add(
            key, Reading(text=final_text, confidence=candidate.confidence, timestamp=time.time())
        )
        if not consensus.accepted:
            return None
        if consensus.text:
            consensus_valid, consensus_corrected, final_text = self._validate(
                candidate.kind, consensus.text
            )
            # The correction flag is kept: the consensus may confirm a repaired code.
            corrected = corrected or consensus_corrected
            iso_valid = consensus_valid if consensus_valid is not None else iso_valid
            normalised = final_text

        duplicate = self.deduplicator.check(gate_id, candidate.kind, final_text)
        if duplicate.duplicate:
            logger.debug("duplicate event suppressed: %s", final_text)
            return None

        if candidate.confidence < self.settings.recognize_confidence_threshold:
            # Low confidence readings are still published: the backend decides
            # whether they go to the manual review queue.
            logger.info("low confidence reading published for review: %.3f", candidate.confidence)

        event = GateEvent(
            yardId=self.settings.yard_id,
            gateId=gate_id,
            kind=ReadingKind(candidate.kind),
            rawText=candidate.raw_text,
            normalizedText=final_text,
            confidence=round(candidate.confidence, 4),
            isoValid=iso_valid,
            corrected=corrected,
            bbox=BoundingBox(points=candidate.points, score=None),
            modelVersion=self.model_version,
            latencyMs=int((time.perf_counter() - started) * 1000),
        )

        if self.evidence is not None:
            try:
                event.evidenceImagePath = self.evidence.save(candidate.crop, event.eventId)
            except Exception:  # pragma: no cover - evidence must not break the pipeline
                logger.exception("could not store the evidence image")

        if self.publisher is not None:
            self.publisher.enqueue(event.model_dump(mode="json"))

        self.consensus.flush(key)
        return event
