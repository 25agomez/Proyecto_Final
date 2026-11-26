"""Image preprocessing for the OCR stage.

OpenCV is used when it is installed; every step has a NumPy/Pillow fallback so
the pipeline stays testable on a machine without the full vision stack.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageFilter, ImageOps

try:  # pragma: no cover - depends on the installed runtime
    import cv2

    HAS_CV2 = True
except ImportError:  # pragma: no cover
    cv2 = None  # type: ignore[assignment]
    HAS_CV2 = False


@dataclass(frozen=True)
class PreprocessOptions:
    """Toggles of the preprocessing chain."""

    clahe_clip_limit: float = 2.0
    binarize: bool = True
    sharpen: bool = True
    try_rotations: tuple[int, ...] = (0, 90, 180, 270)


def to_grayscale(image: Image.Image) -> np.ndarray:
    """Return the image as a 2D uint8 array."""
    return np.array(image.convert("L"))


def apply_clahe(gray: np.ndarray, clip_limit: float = 2.0) -> np.ndarray:
    """Contrast Limited Adaptive Histogram Equalization (OpenCV) or global fallback."""
    if HAS_CV2:
        clahe = cv2.createCLAHE(clipLimit=clip_limit, tileGridSize=(8, 8))
        return clahe.apply(gray)
    equalised = np.array(Image.fromarray(gray).point(lambda value: value))
    return np.array(ImageOps.equalize(Image.fromarray(equalised)))


def adaptive_binarize(gray: np.ndarray) -> np.ndarray:
    """Adaptive thresholding; falls back to Otsu implemented with NumPy."""
    if HAS_CV2:
        return cv2.adaptiveThreshold(
            gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 25, 12
        )
    # Otsu: maximise the between-class variance.
    histogram = np.bincount(gray.ravel(), minlength=256).astype(np.float64)
    total = gray.size
    levels = np.arange(256, dtype=np.float64)
    weight_bg = np.cumsum(histogram)
    weight_fg = total - weight_bg
    sum_total = float((histogram * levels).sum())
    sum_bg = np.cumsum(histogram * levels)
    with np.errstate(invalid="ignore", divide="ignore"):
        mean_bg = sum_bg / weight_bg
        mean_fg = (sum_total - sum_bg) / weight_fg
        variance = weight_bg * weight_fg * (mean_bg - mean_fg) ** 2
    variance[~np.isfinite(variance)] = -1.0
    threshold = int(np.argmax(variance))
    return np.where(gray > threshold, 255, 0).astype(np.uint8)


def sharpen(gray: np.ndarray) -> np.ndarray:
    """Unsharp mask (OpenCV) or Pillow's sharpening filter."""
    if HAS_CV2:
        blur = cv2.GaussianBlur(gray, (3, 3), 0)
        return cv2.addWeighted(gray, 1.5, blur, -0.5, 0)
    return np.array(Image.fromarray(gray).filter(ImageFilter.SHARPEN))


def rotate(gray: np.ndarray, degrees: int) -> np.ndarray:
    """Rotate the array by a multiple of 90 degrees."""
    if degrees == 0:
        return gray
    if degrees == 90:
        return np.rot90(gray, k=-1).copy()
    if degrees == 180:
        return np.rot90(gray, k=2).copy()
    if degrees == 270:
        return np.rot90(gray, k=1).copy()
    raise ValueError(f"unsupported rotation: {degrees}")


def order_points(points: list[float]) -> np.ndarray:
    """Order four (x, y) pairs into top-left, top-right, bottom-right, bottom-left."""
    array = np.array(points, dtype=np.float32).reshape(4, 2)
    ordered = np.zeros((4, 2), dtype=np.float32)
    summed = array.sum(axis=1)
    diff = np.diff(array, axis=1).ravel()
    ordered[0] = array[np.argmin(summed)]
    ordered[2] = array[np.argmax(summed)]
    ordered[1] = array[np.argmin(diff)]
    ordered[3] = array[np.argmax(diff)]
    return ordered


def crop_oriented(image: Image.Image, points: list[float], padding: float = 0.04) -> Image.Image:
    """Extract the oriented region of an OBB and rectify it to an upright crop."""
    ordered = order_points(points)
    width = int(
        max(
            np.linalg.norm(ordered[0] - ordered[1]),
            np.linalg.norm(ordered[2] - ordered[3]),
        )
    )
    height = int(
        max(
            np.linalg.norm(ordered[0] - ordered[3]),
            np.linalg.norm(ordered[1] - ordered[2]),
        )
    )
    width = max(width, 1)
    height = max(height, 1)
    pad_x = int(width * padding)
    pad_y = int(height * padding)
    width += 2 * pad_x
    height += 2 * pad_y

    if HAS_CV2:
        source = np.array(image.convert("RGB"))[:, :, ::-1].copy()
        destination = np.array(
            [
                [0, 0],
                [width - 1, 0],
                [width - 1, height - 1],
                [0, height - 1],
            ],
            dtype="float32",
        )
        matrix = cv2.getPerspectiveTransform(ordered, destination)
        rectified = cv2.warpPerspective(source, matrix, (width, height))
        return Image.fromarray(rectified[:, :, ::-1])

    # Fallback: axis aligned crop of the bounding rectangle.
    min_x = int(max(ordered[:, 0].min() - pad_x, 0))
    max_x = int(min(ordered[:, 0].max() + pad_x, image.width))
    min_y = int(max(ordered[:, 1].min() - pad_y, 0))
    max_y = int(min(ordered[:, 1].max() + pad_y, image.height))
    return image.crop((min_x, min_y, max_x, max_y)).resize((width, height))


def preprocess_crop(crop: Image.Image, options: PreprocessOptions) -> list[tuple[int, Image.Image]]:
    """Return the candidate variants of a crop: (rotation, grayscale image)."""
    gray = to_grayscale(crop)
    gray = apply_clahe(gray, options.clahe_clip_limit)
    if options.sharpen:
        gray = sharpen(gray)
    if options.binarize:
        gray = adaptive_binarize(gray)
    variants: list[tuple[int, Image.Image]] = []
    for angle in options.try_rotations:
        rotated = rotate(gray, angle)
        variants.append((angle, Image.fromarray(rotated)))
    return variants


def rotation_to_upright(width: int, height: int) -> int:
    """Rotation that puts a tall crop back into a landscape orientation."""
    return 90 if height > width else 0


def estimate_text_height(gray: np.ndarray) -> int:
    """Estimate the stroke height of the text (used to scale the preprocessing)."""
    if gray.size == 0:
        return 1
    ink = gray < 128
    rows = ink.sum(axis=1)
    active = rows[rows > 0]
    if active.size == 0:
        return 1
    return max(int(active.mean()), 1)


def scale_to_min_height(gray: np.ndarray, min_height: int = 48) -> np.ndarray:
    """Upscale the crop so the characters are big enough for the recogniser."""
    height = estimate_text_height(gray)
    if height >= min_height:
        return gray
    factor = min(min_height / height, 6.0)
    new_height = max(int(gray.shape[0] * factor), 1)
    new_width = max(int(gray.shape[1] * factor), 1)
    if HAS_CV2:
        return cv2.resize(gray, (new_width, new_height), interpolation=cv2.INTER_CUBIC)
    return np.array(
        Image.fromarray(gray).resize((new_width, new_height), Image.BICUBIC),
    )


def round_to_int(value: float) -> int:
    """Small helper used by the pipeline to keep the types clean."""
    return int(math.floor(value))
