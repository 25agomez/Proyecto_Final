"""Detector interface: oriented boxes for plates and container codes.

The production backend is YOLOv8-obb (Ultralytics). The dependency is imported
lazily so the service (and its unit tests) can run without the weights.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

logger = logging.getLogger(__name__)


class ModelUnavailableError(RuntimeError):
    """Raised when a model or its runtime is not available."""


@dataclass(frozen=True)
class Detection:
    """One oriented detection in the frame."""

    kind: str
    points: list[float]
    score: float


#: Default mapping from the class names of the custom model to the reading kinds.
DEFAULT_CLASS_NAMES: dict[int, str] = {0: "PLATE", 1: "CONTAINER"}


class YoloObbDetector:
    """Thin wrapper around Ultralytics YOLO with oriented bounding boxes."""

    def __init__(
        self,
        weights_path: str | Path,
        confidence: float = 0.25,
        class_names: dict[int, str] | None = None,
        device: str = "cpu",
    ) -> None:
        self.weights_path = Path(weights_path)
        self.confidence = confidence
        self.class_names = class_names or DEFAULT_CLASS_NAMES
        self.device = device
        self._model = None

    @property
    def available(self) -> bool:
        """True when the weights exist and the runtime can be imported."""
        if not self.weights_path.is_file():
            return False
        try:
            import ultralytics  # noqa: F401
        except ImportError:
            return False
        return True

    def load(self) -> None:
        """Instantiate the model (raises when the runtime or weights are missing)."""
        if self._model is not None:
            return
        if not self.weights_path.is_file():
            raise ModelUnavailableError(f"detector weights not found: {self.weights_path}")
        try:
            from ultralytics import YOLO
        except ImportError as error:  # pragma: no cover - optional dependency
            raise ModelUnavailableError(
                "ultralytics is not installed; install it in the runtime that mounts the weights"
            ) from error
        logger.info("loading detector %s", self.weights_path)
        self._model = YOLO(str(self.weights_path), task="obb")
        self._model.to(self.device)

    def detect(self, image: Image.Image) -> list[Detection]:
        """Run the detector over a frame and return the oriented detections."""
        self.load()
        assert self._model is not None
        results = self._model.predict(source=image, conf=self.confidence, verbose=False)
        if not results:
            return []
        result = results[0]
        detections: list[Detection] = []
        obb = getattr(result, "obb", None)
        if obb is None or obb.xyxy is None:
            return []
        points = obb.xyxy.cpu().numpy()
        boxes = getattr(result, "boxes", None)
        scores = boxes.conf.cpu().numpy() if boxes is not None else [1.0] * len(points)
        classes = boxes.cls.cpu().numpy().astype(int) if boxes is not None else [0] * len(points)
        for index, box in enumerate(points):
            kind = self.class_names.get(int(classes[index]), "PLATE")
            detections.append(
                Detection(
                    kind=kind,
                    points=[float(value) for value in box.reshape(-1)],
                    score=float(scores[index]),
                )
            )
        return detections
