"""RTSP stream registry and capture loop."""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field
from uuid import uuid4

from PIL import Image

from ..config import Settings
from ..models import ReadingKind, StreamStatus

logger = logging.getLogger(__name__)

try:  # pragma: no cover - depends on the installed runtime
    import cv2

    HAS_CV2 = True
except ImportError:  # pragma: no cover
    cv2 = None  # type: ignore[assignment]
    HAS_CV2 = False


@dataclass
class Stream:
    """A registered RTSP stream."""

    stream_id: str
    url: str
    gate_id: str
    fps: int
    roi: list[float] | None = None
    kinds: list[ReadingKind] = field(default_factory=list)
    status: str = "REGISTERED"
    frames_processed: int = 0
    events_published: int = 0
    last_error: str | None = None
    registered_at: float = field(default_factory=time.time)
    _stop: threading.Event = field(default_factory=threading.Event)
    _thread: threading.Thread | None = None

    def to_status(self) -> StreamStatus:
        """Public representation of the stream."""
        return StreamStatus(
            streamId=self.stream_id,
            url=self.url,
            gateId=self.gate_id,
            fps=self.fps,
            status=self.status,
            framesProcessed=self.frames_processed,
            eventsPublished=self.events_published,
            lastError=self.last_error,
        )


class StreamManager:
    """Keeps the registered streams and runs their capture loop."""

    def __init__(self, settings: Settings, service: object | None = None) -> None:
        self.settings = settings
        self.service = service
        self._streams: dict[str, Stream] = {}
        self._lock = threading.Lock()

    def register(self, url: str, gate_id: str, fps: int, roi: list[float] | None = None) -> Stream:
        """Register a stream and start capturing it."""
        fps = max(1, min(fps, self.settings.stream_max_fps))
        stream = Stream(
            stream_id=str(uuid4()),
            url=url,
            gate_id=gate_id,
            fps=fps,
            roi=roi,
        )
        with self._lock:
            self._streams[stream.stream_id] = stream
        if HAS_CV2:
            stream._thread = threading.Thread(
                target=self._capture_loop,
                args=(stream,),
                name=f"stream-{stream.stream_id[:8]}",
                daemon=True,
            )
            stream._thread.start()
        else:
            stream.status = "PENDING_CAPTURE"
            stream.last_error = "OpenCV is not installed: the stream is registered but not captured"
            logger.warning(stream.last_error)
        return stream

    def remove(self, stream_id: str) -> bool:
        """Stop and forget a stream."""
        with self._lock:
            stream = self._streams.pop(stream_id, None)
        if stream is None:
            return False
        stream._stop.set()
        if stream._thread is not None:
            stream._thread.join(timeout=3)
        return True

    def list(self) -> list[StreamStatus]:
        """Status of every registered stream."""
        with self._lock:
            return [stream.to_status() for stream in self._streams.values()]

    def _capture_loop(self, stream: Stream) -> None:
        """Read the RTSP stream and feed every frame to the recognition service."""
        assert cv2 is not None
        capture = cv2.VideoCapture(stream.url)
        if not capture.isOpened():
            stream.status = "ERROR"
            stream.last_error = f"cannot open the stream {stream.url}"
            logger.error(stream.last_error)
            return
        stream.status = "CAPTURING"
        interval = 1.0 / stream.fps
        try:
            while not stream._stop.is_set():
                started = time.time()
                ok, frame = capture.read()
                if not ok:
                    stream.last_error = "frame read failed"
                    continue
                image = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
                if stream.roi:
                    x, y, width, height = (int(value) for value in stream.roi)
                    image = image.crop((x, y, x + width, y + height))
                if self.service is not None:
                    try:
                        response = self.service.recognize(image, stream.gate_id, stream.kinds)
                        stream.events_published += len(response.events)
                    except Exception:  # pragma: no cover - keep capturing
                        logger.exception("recognition failed on a streamed frame")
                stream.frames_processed += 1
                elapsed = time.time() - started
                if elapsed < interval:
                    stream._stop.wait(interval - elapsed)
        finally:
            capture.release()
            stream.status = "STOPPED"
