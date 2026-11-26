"""Synthetic dataset of container codes and vehicle plates.

The script renders deterministic images (fixed seed) with the degradations that
a real gate camera produces: perspective, rotation, brightness changes, sensor
noise, defocus and JPEG compression. The ground truth is written to a CSV file
so the evaluation tool can compare the readings against it.

Usage::

    python tools/make_synthetic_dataset.py --output data/synthetic --count 240

Fonts: DejaVu (Bitstream Vera / Arev licence, free software). No font file is
copied into the repository: the script looks for a system font and falls back to
the bitmap font of Pillow when none is installed.
"""

from __future__ import annotations

import argparse
import csv
import random
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.pipeline.iso6346 import check_digit  # noqa: E402

#: Candidate system fonts (free software) used to render the text.
FONT_CANDIDATES: tuple[str, ...] = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationMono-Bold.ttf",
    "/usr/share/fonts/truetype/ubuntu/Ubuntu-Bold.ttf",
    "C:/Windows/Fonts/consolab.ttf",
)

OWNER_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
CATEGORY_LETTERS = "UJZ"

#: Costa Rica style plates: one or three letters plus four to six digits.
PLATE_PATTERNS: tuple[tuple[str, str], ...] = (
    ("P", "{digits}{digits}{digits}{digits}{digits}{digits}"),
    ("ABC", "{digits}{digits}{digits}{digits}"),
    ("AB", "{digits}{digits}{digits}{digits}{digits}"),
)

SIZE_TYPE_CODES: tuple[str, ...] = ("22G1", "42G1", "45G1", "22R1", "42U1")


@dataclass(frozen=True)
class Sample:
    """One generated image and its ground truth."""

    id: str
    file: str
    kind: str
    text: str
    orientation: str
    size_type_code: str
    width: int
    height: int
    rotation_deg: float
    brightness: float
    noise_sigma: float
    blur_radius: float
    jpeg_quality: int


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    """Load the first available font, or the Pillow default as a fallback."""
    for candidate in FONT_CANDIDATES:
        if Path(candidate).is_file():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default(size=size)


def random_bic(rng: random.Random) -> str:
    """Build a valid BIC code (owner + category + serial + check digit)."""
    owner = "".join(rng.choice(OWNER_LETTERS) for _ in range(3))
    category = rng.choice(CATEGORY_LETTERS)
    serial = "".join(rng.choice("0123456789") for _ in range(6))
    prefix = f"{owner}{category}{serial}"
    return prefix + str(check_digit(prefix))


def random_plate(rng: random.Random) -> str:
    """Build a Costa Rica style plate."""
    letters, digits_template = rng.choice(PLATE_PATTERNS)
    digits = "".join(rng.choice("0123456789") for _ in range(len(digits_template) // 8))
    return letters + digits


def render_text(
    text: str,
    font: ImageFont.FreeTypeFont | ImageFont.ImageFont,
    foreground: tuple[int, int, int],
    background: tuple[int, int, int],
    padding: int = 12,
) -> Image.Image:
    """Render a text on a rectangular plate and return the image."""
    draw = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    box = draw.textbbox((0, 0), text, font=font)
    width = box[2] - box[0] + 2 * padding
    height = box[3] - box[1] + 2 * padding
    image = Image.new("RGB", (width, height), background)
    draw = ImageDraw.Draw(image)
    draw.text((padding - box[0], padding - box[1]), text, font=font, fill=foreground)
    return image


def synthetic_background(rng: random.Random, width: int, height: int) -> Image.Image:
    """Simulate the surface where the code is painted (steel, container, road)."""
    base = rng.randint(70, 190)
    tone = np.array(
        [
            max(base + rng.randint(-25, 25), 10),
            max(base + rng.randint(-25, 25), 10),
            max(base + rng.randint(-25, 25), 10),
        ],
        dtype=np.float32,
    )
    gradient = np.linspace(-18.0, 18.0, height, dtype=np.float32)[:, None]
    array = tone[None, None, :] + gradient[:, :, None] * np.array([1.0, 0.98, 0.95])
    array = np.repeat(array, width, axis=1)
    return Image.fromarray(np.clip(array, 0, 255).astype(np.uint8), mode="RGB")


def degrade(
    image: Image.Image,
    rng: random.Random,
    brightness: float,
    noise_sigma: float,
    blur_radius: float,
    jpeg_quality: int,
) -> Image.Image:
    """Apply the camera degradations to a rendered image."""
    array = np.array(image.convert("RGB"), dtype=np.float32)
    array = array * brightness
    if noise_sigma > 0:
        array = array + np.random.default_rng(rng.randint(0, 2**31 - 1)).normal(
            0.0, noise_sigma, array.shape
        )
    array = np.clip(array, 0, 255).astype(np.uint8)
    result = Image.fromarray(array, mode="RGB")
    if blur_radius > 0:
        result = result.filter(ImageFilter.GaussianBlur(radius=blur_radius))
    return result


def place_on_canvas(
    canvas: Image.Image,
    patch: Image.Image,
    rng: random.Random,
    rotation_deg: float,
) -> Image.Image:
    """Paste a patch on the canvas with a small rotation."""
    rotated = patch.rotate(
        rotation_deg, expand=True, resample=Image.BICUBIC, fillcolor=(90, 90, 90)
    )
    max_x = max(canvas.width - rotated.width, 1)
    max_y = max(canvas.height - rotated.height, 1)
    x = rng.randint(0, max_x)
    y = rng.randint(0, max_y)
    canvas.paste(rotated, (x, y))
    return canvas


def generate(
    output: Path,
    count: int,
    seed: int,
    width: int,
    height: int,
) -> list[Sample]:
    """Generate the dataset and return the ground truth of every sample."""
    output.mkdir(parents=True, exist_ok=True)
    images_dir = output / "images"
    images_dir.mkdir(parents=True, exist_ok=True)
    samples: list[Sample] = []

    for index in range(count):
        sample_seed = seed + index
        sample_rng = random.Random(sample_seed)
        is_container = index % 2 == 0
        kind = "CONTAINER" if is_container else "PLATE"
        orientation = "vertical" if is_container and index % 4 == 1 else "horizontal"
        text = random_bic(sample_rng) if is_container else random_plate(sample_rng)
        size_type_code = sample_rng.choice(SIZE_TYPE_CODES) if is_container else ""

        font_size = sample_rng.randint(44, 72)
        font = load_font(font_size)
        if is_container:
            foreground = (245, 245, 240)
            background = (105, 105, 110)
            rendered_text = text if not size_type_code else f"{text} {size_type_code}"
        else:
            foreground = (25, 25, 30)
            background = (235, 235, 230)
            rendered_text = text

        patch = render_text(rendered_text, font, foreground, background)
        if orientation == "vertical":
            patch = patch.rotate(90, expand=True, resample=Image.BICUBIC)

        canvas = synthetic_background(sample_rng, width, height)
        rotation = sample_rng.uniform(-4.0, 4.0)
        place_on_canvas(canvas, patch, sample_rng, rotation)

        brightness = sample_rng.uniform(0.65, 1.35)
        noise_sigma = sample_rng.uniform(0.0, 12.0)
        blur_radius = sample_rng.uniform(0.0, 1.6)
        jpeg_quality = sample_rng.choice([95, 92, 88, 82, 75, 68])
        canvas = degrade(canvas, sample_rng, brightness, noise_sigma, blur_radius, jpeg_quality)

        file_name = f"{kind.lower()}_{index:04d}.jpg"
        path = images_dir / file_name
        canvas.save(path, format="JPEG", quality=jpeg_quality)

        samples.append(
            Sample(
                id=f"{index:04d}",
                file=f"images/{file_name}",
                kind=kind,
                text=text,
                orientation=orientation,
                size_type_code=size_type_code,
                width=width,
                height=height,
                rotation_deg=round(rotation, 3),
                brightness=round(brightness, 4),
                noise_sigma=round(noise_sigma, 3),
                blur_radius=round(blur_radius, 3),
                jpeg_quality=jpeg_quality,
            )
        )

    return samples


def write_ground_truth(samples: list[Sample], path: Path) -> None:
    """Write the ground truth of the dataset as CSV."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(asdict(samples[0]).keys()))
        writer.writeheader()
        for sample in samples:
            writer.writerow(asdict(sample))


def main(argv: list[str] | None = None) -> int:
    """Entry point of the script."""
    parser = argparse.ArgumentParser(description="Synthetic dataset for the edge OCR service")
    parser.add_argument("--output", default="data/synthetic", help="output directory")
    parser.add_argument("--count", type=int, default=240, help="number of images")
    parser.add_argument("--seed", type=int, default=20260222, help="seed of the generator")
    parser.add_argument("--width", type=int, default=1280, help="width of the images")
    parser.add_argument("--height", type=int, default=720, help="height of the images")
    arguments = parser.parse_args(argv)

    output = Path(arguments.output)
    samples = generate(output, arguments.count, arguments.seed, arguments.width, arguments.height)
    write_ground_truth(samples, output / "ground_truth.csv")
    containers = sum(1 for sample in samples if sample.kind == "CONTAINER")
    print(f"{len(samples)} images written to {output}")
    print(f"  containers: {containers} | plates: {len(samples) - containers}")
    print(f"  ground truth: {output / 'ground_truth.csv'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
