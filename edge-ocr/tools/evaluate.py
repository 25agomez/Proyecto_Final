"""Evaluation of the edge OCR service.

Two modes are available:

``corrupt``
    Measures the **post-processing layer** (normalisation + ISO 6346 validation
    + repair) over the synthetic dataset. The recognition output is simulated
    with a deterministic error model built on the same confusion table used by
    the repair step, so the numbers describe how much the post-processing
    recovers from a realistic recognition error. They do **not** describe the
    accuracy of the neural OCR engine.

``live``
    Measures the **full pipeline** (YOLOv8-obb + PaddleOCR) over the same
    dataset. It requires the model weights; when they are missing the tool
    reports ``NO MEDIDO`` with the reason instead of inventing numbers.

Usage::

    python tools/evaluate.py --dataset data/synthetic --mode corrupt
    python tools/evaluate.py --dataset data/synthetic --mode live --weights /models
"""

from __future__ import annotations

import argparse
import csv
import json
import random
import statistics
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import Settings  # noqa: E402
from app.pipeline import postprocess  # noqa: E402
from app.pipeline.iso6346 import CONFUSABLE_GROUPS  # noqa: E402

CONTAINER = "CONTAINER"
PLATE = "PLATE"

#: Probabilities of the simulated recognition error model.
ERROR_MODEL: dict[str, float] = {
    "perfect": 0.55,
    "one_substitution": 0.28,
    "one_insertion": 0.07,
    "one_deletion": 0.05,
    "two_substitutions": 0.05,
}

#: Characters that the OCR engine tends to insert as separators.
SEPARATORS = (" ", "-", ".")


@dataclass
class Reading:
    """One evaluated sample."""

    id: str
    kind: str
    truth: str
    raw: str
    predicted: str
    iso_valid: bool | None
    corrected: bool
    latency_ms: float


@dataclass
class Metrics:
    """Aggregated metrics of an evaluation run."""

    mode: str
    samples: int = 0
    containers: int = 0
    plates: int = 0
    raw_exact_matches: int = 0
    exact_matches: int = 0
    raw_edit_distance: int = 0
    final_edit_distance: int = 0
    truth_characters: int = 0
    check_digit_valid: int = 0
    corrected: int = 0
    invalid: int = 0
    latencies_ms: list[float] = field(default_factory=list)
    not_measured: str | None = None

    def to_dict(self) -> dict[str, Any]:
        """Return the metrics as a plain dictionary."""
        data: dict[str, Any] = {
            "mode": self.mode,
            "samples": self.samples,
            "containers": self.containers,
            "plates": self.plates,
            "notMeasured": self.not_measured,
        }
        if self.not_measured:
            return data
        latencies = sorted(self.latencies_ms)
        data.update(
            {
                "rawExactMatchPct": _percentage(self.raw_exact_matches, self.samples),
                "exactMatchPct": _percentage(self.exact_matches, self.samples),
                "cerRaw": _ratio(self.raw_edit_distance, self.truth_characters),
                "cerFinal": _ratio(self.final_edit_distance, self.truth_characters),
                "checkDigitValidPct": _percentage(self.check_digit_valid, self.containers),
                "correctedPct": _percentage(self.corrected, self.containers),
                "invalidPct": _percentage(self.invalid, self.containers),
                "latencyP50Ms": _percentile(latencies, 50),
                "latencyP95Ms": _percentile(latencies, 95),
                "latencyMeanMs": round(statistics.fmean(latencies), 3) if latencies else None,
            }
        )
        return data


def _percentage(part: int, total: int) -> float:
    """Return a rounded percentage."""
    if total <= 0:
        return 0.0
    return round(part * 100.0 / total, 2)


def _ratio(part: int, total: int) -> float:
    """Return a rounded ratio (used by the CER)."""
    if total <= 0:
        return 0.0
    return round(part / total, 6)


def _percentile(values: list[float], percentile: float) -> float:
    """Return a percentile of a sorted list (nearest rank)."""
    if not values:
        return 0.0
    if len(values) == 1:
        return round(values[0], 3)
    rank = max(1, min(len(values), int(round(percentile / 100.0 * len(values) + 0.5))))
    return round(values[rank - 1], 3)


def levenshtein(left: str, right: str) -> int:
    """Return the edit distance between two strings."""
    if left == right:
        return 0
    if not left:
        return len(right)
    if not right:
        return len(left)
    previous = list(range(len(right) + 1))
    for i, left_char in enumerate(left, start=1):
        current = [i]
        for j, right_char in enumerate(right, start=1):
            insertion = current[j - 1] + 1
            deletion = previous[j] + 1
            substitution = previous[j - 1] + (left_char != right_char)
            current.append(min(insertion, deletion, substitution))
        previous = current
    return previous[-1]


def confusable_substitution(char: str, rng: random.Random) -> str:
    """Return a visually confusable replacement of a character."""
    upper = char.upper()
    options: list[str] = []
    for group in CONFUSABLE_GROUPS:
        if upper in group:
            options.extend(member for member in group if member != upper)
    if not options:
        return char
    return rng.choice(options)


def simulate_reading(text: str, seed: int) -> str:
    """Simulate the output of the recognition engine for a known text.

    The error model is deterministic: the same text and seed always produce the
    same simulated reading.
    """
    rng = random.Random(seed)
    draw = rng.random()
    if draw < ERROR_MODEL["perfect"]:
        return text
    if draw < ERROR_MODEL["perfect"] + ERROR_MODEL["one_substitution"]:
        position = rng.randrange(len(text))
        return text[:position] + confusable_substitution(text[position], rng) + text[position + 1 :]
    if (
        draw
        < ERROR_MODEL["perfect"] + ERROR_MODEL["one_substitution"] + ERROR_MODEL["one_insertion"]
    ):
        position = rng.randrange(len(text) + 1)
        separator = rng.choice(SEPARATORS)
        return text[:position] + separator + text[position:]
    if draw < (
        ERROR_MODEL["perfect"]
        + ERROR_MODEL["one_substitution"]
        + ERROR_MODEL["one_insertion"]
        + ERROR_MODEL["one_deletion"]
    ):
        position = rng.randrange(len(text))
        return text[:position] + text[position + 1 :]
    characters = list(text)
    for _ in range(2):
        position = rng.randrange(len(characters))
        characters[position] = confusable_substitution(characters[position], rng)
    return "".join(characters)


def read_ground_truth(path: Path) -> list[dict[str, str]]:
    """Read the ground truth CSV of the dataset."""
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def evaluate_postprocessing(rows: list[dict[str, str]], seed: int) -> tuple[Metrics, list[Reading]]:
    """Measure the post-processing layer over the dataset."""
    metrics = Metrics(mode="corrupt")
    readings: list[Reading] = []
    for index, row in enumerate(rows):
        truth = row["text"]
        kind = row["kind"]
        raw = simulate_reading(truth, seed + index)
        started = time.perf_counter()
        normalised = postprocess.normalise_reading(kind, raw)
        result = postprocess.validate_reading(kind, normalised)
        elapsed = (time.perf_counter() - started) * 1000.0

        metrics.samples += 1
        metrics.latencies_ms.append(elapsed)
        metrics.truth_characters += len(truth)
        metrics.raw_edit_distance += levenshtein(raw.upper(), truth)
        metrics.final_edit_distance += levenshtein(result.text.upper(), truth)
        if raw.upper() == truth:
            metrics.raw_exact_matches += 1
        if result.text.upper() == truth:
            metrics.exact_matches += 1
        if kind == CONTAINER:
            metrics.containers += 1
            if result.iso_valid:
                metrics.check_digit_valid += 1
            else:
                metrics.invalid += 1
            if result.corrected:
                metrics.corrected += 1
        else:
            metrics.plates += 1

        readings.append(
            Reading(
                id=row["id"],
                kind=kind,
                truth=truth,
                raw=raw,
                predicted=result.text,
                iso_valid=result.iso_valid,
                corrected=result.corrected,
                latency_ms=round(elapsed, 4),
            )
        )
    return metrics, readings


def evaluate_preprocessing(
    rows: list[dict[str, str]], dataset: Path
) -> tuple[Metrics, list[Reading]]:
    """Measure the cost of the non-neural part of the pipeline.

    The detection and the recognition need the model weights, but the crop
    rectification and the image preprocessing (CLAHE, sharpening, adaptive
    binarisation and the four rotations) always run. This mode measures that
    cost on the real images of the dataset with a fixed crop of the code.
    """
    from app.pipeline.preprocess import PreprocessOptions, crop_oriented, preprocess_crop

    metrics = Metrics(mode="preprocess")
    options = PreprocessOptions()
    readings: list[Reading] = []
    for row in rows:
        path = dataset / row["file"]
        with Image.open(path) as handle:
            image = handle.convert("RGB")
        crop_width = min(420, image.width)
        crop_height = min(70, image.height)
        left = (image.width - crop_width) // 2
        top = (image.height - crop_height) // 2
        points = [
            float(left),
            float(top),
            float(left + crop_width),
            float(top),
            float(left + crop_width),
            float(top + crop_height),
            float(left),
            float(top + crop_height),
        ]
        started = time.perf_counter()
        crop = crop_oriented(image, points)
        variants = preprocess_crop(crop, options)
        elapsed = (time.perf_counter() - started) * 1000.0

        metrics.samples += 1
        metrics.latencies_ms.append(elapsed)
        readings.append(
            Reading(
                id=row["id"],
                kind=row["kind"],
                truth=row["text"],
                raw=f"{crop.width}x{crop.height}",
                predicted=f"{len(variants)} variantes",
                iso_valid=None,
                corrected=False,
                latency_ms=round(elapsed, 4),
            )
        )
    return metrics, readings


def evaluate_live(
    rows: list[dict[str, str]],
    dataset: Path,
    settings: Settings,
) -> tuple[Metrics, list[Reading]]:
    """Measure the full pipeline (detector + OCR) over the dataset."""
    from app.pipeline.service import RecognitionService

    metrics = Metrics(mode="live")
    service = RecognitionService(settings)
    if not service.models_available:
        metrics.not_measured = (
            "NO MEDIDO: el runtime de los modelos no esta disponible "
            "(pesos de YOLOv8-obb o PaddleOCR ausentes en el contenedor de evaluacion)"
        )
        return metrics, []

    readings: list[Reading] = []
    for row in rows:
        truth = row["text"]
        kind = row["kind"]
        path = dataset / row["file"]
        with Image.open(path) as handle:
            image = handle.convert("RGB")
        started = time.perf_counter()
        response = service.recognize(image)
        elapsed = (time.perf_counter() - started) * 1000.0

        candidates = [event for event in response.events if event.kind.value == kind]
        predicted = candidates[0].normalizedText if candidates else ""
        iso_valid = candidates[0].isoValid if candidates else None
        corrected = bool(candidates[0].corrected) if candidates else False

        metrics.samples += 1
        metrics.latencies_ms.append(elapsed)
        metrics.truth_characters += len(truth)
        metrics.final_edit_distance += levenshtein((predicted or "").upper(), truth)
        if (predicted or "").upper() == truth:
            metrics.exact_matches += 1
        if kind == CONTAINER:
            metrics.containers += 1
            if iso_valid:
                metrics.check_digit_valid += 1
            else:
                metrics.invalid += 1
            if corrected:
                metrics.corrected += 1
        else:
            metrics.plates += 1

        readings.append(
            Reading(
                id=row["id"],
                kind=kind,
                truth=truth,
                raw=predicted,
                predicted=predicted or "",
                iso_valid=iso_valid,
                corrected=corrected,
                latency_ms=round(elapsed, 4),
            )
        )
    return metrics, readings


def render_markdown(metrics: Metrics, dataset: Path, readings: list[Reading]) -> str:
    """Render the metrics as a Markdown report."""
    lines: list[str] = []
    lines.append(f"# Evaluacion OCR - modo `{metrics.mode}`")
    lines.append("")
    lines.append(f"- Dataset: `{dataset}`")
    lines.append(f"- Imagenes evaluadas: {metrics.samples}")
    if metrics.not_measured:
        lines.append(f"- **NO MEDIDO**: {metrics.not_measured}")
        lines.append("")
        return "\n".join(lines)
    lines.append("")
    if metrics.mode == "preprocess":
        lines.append("| Metrica | Valor |")
        lines.append("|---|---|")
        lines.append(
            f"| Latencia p50 (recorte + preprocesado) | {metrics.to_dict()['latencyP50Ms']} ms |"
        )
        lines.append(
            f"| Latencia p95 (recorte + preprocesado) | {metrics.to_dict()['latencyP95Ms']} ms |"
        )
        lines.append(f"| Latencia media | {metrics.to_dict()['latencyMeanMs']} ms |")
        lines.append("")
        lines.append("> Mide la parte del pipeline que no depende de los pesos (recorte orientado,")
        lines.append(
            "> CLAHE, nitidez, binarizacion adaptativa y las cuatro rotaciones). La deteccion"
        )
        lines.append("> y el reconocimiento se miden en el modo `live` (NO MEDIDO sin los pesos).")
        lines.append("")
        return "\n".join(lines)
    lines.append("| Metrica | Valor |")
    lines.append("|---|---|")
    lines.append(
        f"| Exactitud por codigo completo (raw) | {metrics.to_dict()['rawExactMatchPct']} % |"
    )
    lines.append(
        f"| Exactitud por codigo completo (post-proceso) | {metrics.to_dict()['exactMatchPct']} % |"
    )
    lines.append(f"| CER (raw) | {metrics.to_dict()['cerRaw']} |")
    lines.append(f"| CER (post-proceso) | {metrics.to_dict()['cerFinal']} |")
    lines.append(f"| Check digit valido | {metrics.to_dict()['checkDigitValidPct']} % |")
    lines.append(f"| Lecturas corregidas | {metrics.to_dict()['correctedPct']} % |")
    lines.append(f"| Lecturas invalidas (revision manual) | {metrics.to_dict()['invalidPct']} % |")
    lines.append(f"| Latencia p50 (post-proceso) | {metrics.to_dict()['latencyP50Ms']} ms |")
    lines.append(f"| Latencia p95 (post-proceso) | {metrics.to_dict()['latencyP95Ms']} ms |")
    lines.append("")
    lines.append("> El modo `corrupt` mide la capa de post-procesamiento con un modelo de error")
    lines.append("> simulado; no mide la precision del OCR neuronal (modo `live`).")
    lines.append("")
    failures = [
        reading for reading in readings if reading.predicted.upper() != reading.truth.upper()
    ]
    lines.append(f"## Muestra de errores ({min(len(failures), 15)} de {len(failures)})")
    lines.append("")
    lines.append("| id | tipo | verdad | lectura simulada | salida | check digit | corregido |")
    lines.append("|---|---|---|---|---|---|---|")
    for reading in failures[:15]:
        lines.append(
            f"| {reading.id} | {reading.kind} | {reading.truth} | {reading.raw} | "
            f"{reading.predicted} | {reading.iso_valid} | {reading.corrected} |"
        )
    lines.append("")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    """Entry point of the script."""
    parser = argparse.ArgumentParser(description="Evaluation of the edge OCR service")
    parser.add_argument("--dataset", default="data/synthetic", help="dataset directory")
    parser.add_argument("--mode", choices=["corrupt", "live", "preprocess"], default="corrupt")
    parser.add_argument("--seed", type=int, default=20260222, help="seed of the error model")
    parser.add_argument("--limit", type=int, default=0, help="evaluate only the first N samples")
    parser.add_argument(
        "--weights", default="", help="directory with the model weights (live mode)"
    )
    parser.add_argument("--out", default="", help="file to write the JSON report to")
    arguments = parser.parse_args(argv)

    dataset = Path(arguments.dataset)
    ground_truth = dataset / "ground_truth.csv"
    if not ground_truth.is_file():
        print(f"ERROR: no existe {ground_truth}; ejecuta tools/make_synthetic_dataset.py primero")
        return 1

    rows = read_ground_truth(ground_truth)
    if arguments.limit > 0:
        rows = rows[: arguments.limit]

    if arguments.mode == "corrupt":
        metrics, readings = evaluate_postprocessing(rows, arguments.seed)
    elif arguments.mode == "preprocess":
        metrics, readings = evaluate_preprocessing(rows, dataset)
    else:
        settings = Settings()
        if arguments.weights:
            settings.models_dir = arguments.weights
        metrics, readings = evaluate_live(rows, dataset, settings)

    report = metrics.to_dict()
    report["dataset"] = str(dataset)
    report["seed"] = arguments.seed
    report["samplesDetail"] = [
        {
            "id": reading.id,
            "kind": reading.kind,
            "truth": reading.truth,
            "raw": reading.raw,
            "predicted": reading.predicted,
            "isoValid": reading.iso_valid,
            "corrected": reading.corrected,
            "latencyMs": reading.latency_ms,
        }
        for reading in readings
    ]

    if arguments.out:
        out_path = Path(arguments.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"informe JSON: {out_path}")
        markdown_path = out_path.with_suffix(".md")
        markdown_path.write_text(render_markdown(metrics, dataset, readings), encoding="utf-8")
        print(f"informe Markdown: {markdown_path}")

    print(render_markdown(metrics, dataset, readings))
    if metrics.not_measured:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
