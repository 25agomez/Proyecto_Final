"""Evidence storage: minimum data, TTL and optional face blurring (ADR-004)."""

from __future__ import annotations

import logging
import time
import uuid
from pathlib import Path

from PIL import Image

logger = logging.getLogger(__name__)

try:  # pragma: no cover - depends on the installed runtime
    import cv2
    import numpy as np

    HAS_CV2 = True
except ImportError:  # pragma: no cover
    cv2 = None  # type: ignore[assignment]
    np = None  # type: ignore[assignment]
    HAS_CV2 = False


class EvidenceStore:
    """Stores the rectified crop of every reading (never the full frame)."""

    def __init__(
        self,
        directory: str | Path,
        retention_days: int = 7,
        blur_faces: bool = True,
        max_bytes: int = 5 * 1024 * 1024,
    ) -> None:
        self.directory = Path(directory)
        self.retention_days = retention_days
        self.blur_faces = blur_faces
        self.max_bytes = max_bytes
        self.directory.mkdir(parents=True, exist_ok=True)

    def save(self, image: Image.Image, event_id: str | None = None) -> str:
        """Persist the crop and return its path relative to the evidence root."""
        identifier = event_id or str(uuid.uuid4())
        day = time.strftime("%Y%m%d", time.localtime())
        target_dir = self.directory / day
        target_dir.mkdir(parents=True, exist_ok=True)
        path = target_dir / f"{identifier}.jpg"

        payload = self._prepare(image)
        if len(payload) > self.max_bytes:
            raise ValueError("evidence image exceeds the configured size limit")
        path.write_bytes(payload)
        return str(path.relative_to(self.directory))

    def _prepare(self, image: Image.Image) -> bytes:
        """Apply the face blur (when enabled) and encode the crop as JPEG."""
        working = image.convert("RGB")
        if self.blur_faces:
            working = self.blur_faces_in(working)
        from io import BytesIO

        buffer = BytesIO()
        working.save(buffer, format="JPEG", quality=85, optimize=True)
        return buffer.getvalue()

    def blur_faces_in(self, image: Image.Image) -> Image.Image:
        """Blur the detected faces of an image (no-op when OpenCV is missing)."""
        if not HAS_CV2:
            return image
        array = np.array(image.convert("RGB"))
        grey = cv2.cvtColor(array, cv2.COLOR_RGB2GRAY)
        cascade_path = Path(cv2.data.haarcascades) / "haarcascade_frontalface_default.xml"
        if not cascade_path.is_file():
            return image
        cascade = cv2.CascadeClassifier(str(cascade_path))
        faces = cascade.detectMultiScale(grey, scaleFactor=1.1, minNeighbors=5, minSize=(24, 24))
        for x, y, width, height in faces:
            region = array[y : y + height, x : x + width]
            blurred = cv2.GaussianBlur(region, (0, 0), sigmaX=max(width, height) / 12)
            array[y : y + height, x : x + width] = blurred
        return Image.fromarray(array)

    def purge_expired(self, now: float | None = None) -> int:
        """Delete the evidence older than the retention period. Returns the count."""
        moment = time.time() if now is None else now
        deadline = moment - (self.retention_days * 86400)
        removed = 0
        for path in self.directory.glob("*/*.jpg"):
            try:
                if path.stat().st_mtime < deadline:
                    path.unlink()
                    removed += 1
            except OSError:  # pragma: no cover - concurrent deletion
                continue
        return removed

    def list_files(self) -> list[Path]:
        """Every stored evidence file."""
        return sorted(self.directory.glob("*/*.jpg"))
