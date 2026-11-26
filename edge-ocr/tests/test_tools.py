"""Tests of the development tools (dataset generation and evaluation)."""

from __future__ import annotations

import hashlib
from pathlib import Path

from tools import make_synthetic_dataset
from tools.evaluate import (
    evaluate_preprocessing,
    levenshtein,
    read_ground_truth,
    simulate_reading,
)


def test_levenshtein_distance() -> None:
    """The edit distance counts insertions, deletions and substitutions."""
    assert levenshtein("CSQU3054383", "CSQU3054383") == 0
    assert levenshtein("CSQU3054383", "CSQU3054388") == 1
    assert levenshtein("CSQU3054383", "CSQU305438") == 1
    assert levenshtein("CSQU3054383", "CSQU305-4383") == 1
    assert levenshtein("", "ABC") == 3
    assert levenshtein("ABC", "") == 3


def test_the_simulated_reading_is_deterministic() -> None:
    """The same text and seed always produce the same simulated reading."""
    first = simulate_reading("CSQU3054383", 42)
    second = simulate_reading("CSQU3054383", 42)
    assert first == second
    assert simulate_reading("CSQU3054383", 43) in first or len(first) <= len("CSQU3054383") + 1


def test_the_error_model_keeps_the_shape_of_the_code() -> None:
    """The simulated reading never loses more than a couple of characters."""
    for seed in range(50):
        reading = simulate_reading("CSQU3054383", seed)
        assert 9 <= len(reading) <= 13


def test_the_dataset_is_reproducible(tmp_path: Path) -> None:
    """Two runs with the same seed produce exactly the same images."""
    first_dir = tmp_path / "first"
    second_dir = tmp_path / "second"
    make_synthetic_dataset.generate(first_dir, count=6, seed=7, width=320, height=240)
    make_synthetic_dataset.generate(second_dir, count=6, seed=7, width=320, height=240)

    def digest(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    first_images = sorted((first_dir / "images").glob("*.jpg"))
    second_images = sorted((second_dir / "images").glob("*.jpg"))
    assert len(first_images) == 6
    for left, right in zip(first_images, second_images, strict=True):
        assert digest(left) == digest(right)


def test_the_ground_truth_matches_the_images(tmp_path: Path) -> None:
    """Every row of the CSV points to an existing image with a plausible text."""
    make_synthetic_dataset.generate(tmp_path, count=8, seed=11, width=320, height=240)
    make_synthetic_dataset.write_ground_truth(
        make_synthetic_dataset.generate(tmp_path, count=8, seed=11, width=320, height=240),
        tmp_path / "ground_truth.csv",
    )
    rows = read_ground_truth(tmp_path / "ground_truth.csv")
    assert len(rows) == 8
    for row in rows:
        assert (tmp_path / row["file"]).is_file()
        assert row["kind"] in {"CONTAINER", "PLATE"}
        if row["kind"] == "CONTAINER":
            assert len(row["text"]) == 11
        else:
            assert 5 <= len(row["text"]) <= 9


def test_the_preprocessing_mode_measures_the_latency(tmp_path: Path) -> None:
    """The mode that does not need the weights reports a measured latency."""
    samples = make_synthetic_dataset.generate(tmp_path, count=4, seed=5, width=320, height=240)
    make_synthetic_dataset.write_ground_truth(samples, tmp_path / "ground_truth.csv")
    rows = read_ground_truth(tmp_path / "ground_truth.csv")

    metrics, readings = evaluate_preprocessing(rows, tmp_path)

    assert metrics.mode == "preprocess"
    assert metrics.samples == 4
    assert len(metrics.latencies_ms) == 4
    assert all(value >= 0 for value in metrics.latencies_ms)
    assert len(readings) == 4
