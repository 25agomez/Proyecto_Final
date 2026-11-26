"""Shared fixtures for the edge OCR tests."""

from __future__ import annotations

import io
import sys
from pathlib import Path

import pytest
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402
from app.pipeline.detector import Detection  # noqa: E402
from app.pipeline.ocr import OcrReading  # noqa: E402


class FakeDetector:
    """Detector that returns a fixed oriented box."""

    def __init__(self, detections: list[Detection] | None = None, available: bool = True) -> None:
        self.detections = (
            detections
            if detections is not None
            else [
                Detection(
                    kind="CONTAINER",
                    points=[10.0, 10.0, 110.0, 10.0, 110.0, 40.0, 10.0, 40.0],
                    score=0.9,
                )
            ]
        )
        self._available = available

    @property
    def available(self) -> bool:
        return self._available

    def detect(self, image: Image.Image) -> list[Detection]:
        return self.detections


class FakeOcr:
    """OCR that always answers the same text."""

    def __init__(
        self, text: str = "CSQU3054383", confidence: float = 0.95, available: bool = True
    ) -> None:
        self.text = text
        self.confidence = confidence
        self._available = available
        self.calls = 0

    @property
    def available(self) -> bool:
        return self._available

    def read(self, image: Image.Image) -> OcrReading:
        self.calls += 1
        return OcrReading(text=self.text, confidence=self.confidence)


@pytest.fixture()
def settings(tmp_path: Path) -> Settings:
    """Settings pointing to a temporary directory, with MQTT disabled."""
    return Settings(
        outbox_path=str(tmp_path / "outbox.db"),
        evidence_dir=str(tmp_path / "evidence"),
        mqtt_enabled=False,
        backend_base_url="http://localhost:9/does-not-exist",
        models_dir=str(tmp_path / "models"),
    )


@pytest.fixture()
def fake_detector() -> FakeDetector:
    return FakeDetector()


@pytest.fixture()
def fake_ocr() -> FakeOcr:
    return FakeOcr()


@pytest.fixture()
def app(settings: Settings):
    """FastAPI application (the collaborators are built during startup)."""
    return create_app(settings)


@pytest.fixture()
def client(app, fake_detector: FakeDetector, fake_ocr: FakeOcr):
    """Test client of the application with the models replaced by fakes."""
    from fastapi.testclient import TestClient

    with TestClient(app) as test_client:
        app.state.service.detector = fake_detector
        app.state.service.ocr = fake_ocr
        yield test_client


@pytest.fixture()
def sample_image() -> Image.Image:
    """A synthetic frame with a bright rectangle (the "plate" area)."""
    image = Image.new("RGB", (320, 240), (30, 30, 30))
    for x in range(20, 220):
        for y in range(40, 90):
            image.putpixel((x, y), (235, 235, 235))
    return image


@pytest.fixture()
def sample_image_bytes(sample_image: Image.Image) -> bytes:
    """The same frame encoded as PNG."""
    buffer = io.BytesIO()
    sample_image.save(buffer, format="PNG")
    return buffer.getvalue()
