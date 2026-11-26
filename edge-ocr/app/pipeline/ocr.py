"""OCR interface: reads text on the rectified crops.

The production backend is PaddleOCR (PP-OCR). The dependency is imported lazily
so the service can boot and answer /healthz without the model runtime.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

from .detector import ModelUnavailableError

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class OcrReading:
    """Text recognised on a crop."""

    text: str
    confidence: float


class PaddleOcrEngine:
    """Thin wrapper around PaddleOCR with a stable, testable interface."""

    def __init__(
        self, model_dir: str | Path, lang: str = "en", min_confidence: float = 0.5
    ) -> None:
        self.model_dir = Path(model_dir)
        self.lang = lang
        self.min_confidence = min_confidence
        self._ocr = None

    @property
    def available(self) -> bool:
        """True when the runtime can be imported (the model dir may be empty)."""
        try:
            import paddleocr  # noqa: F401
        except ImportError:
            return False
        return True

    def load(self) -> None:
        """Instantiate the recogniser (raises when the runtime is missing)."""
        if self._ocr is not None:
            return
        try:
            from paddleocr import PaddleOCR
        except ImportError as error:  # pragma: no cover - optional dependency
            raise ModelUnavailableError(
                "paddleocr is not installed; install it in the runtime that mounts the weights"
            ) from error
        logger.info("loading OCR model from %s", self.model_dir)
        self._ocr = PaddleOCR(use_angle_cls=True, lang=self.lang, show_log=False)

    def read(self, image: Image.Image) -> OcrReading:
        """Read the text of a crop; returns the best scoring line."""
        self.load()
        assert self._ocr is not None
        import numpy as np

        raw = self._ocr.ocr(np.array(image.convert("RGB")), cls=True)
        best = OcrReading(text="", confidence=0.0)
        for page in raw or []:
            for line in page or []:
                payload = line[1] if isinstance(line, (list, tuple)) and len(line) > 1 else None
                if not payload:
                    continue
                text, score = payload[0], float(payload[1])
                if score >= self.min_confidence and score > best.confidence:
                    best = OcrReading(text=text, confidence=score)
        return best
