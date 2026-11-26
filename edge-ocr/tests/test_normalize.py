"""Normalisation and position aware confusion correction tests."""

from __future__ import annotations

import pytest

from app.pipeline.normalize import (
    is_probable_bic,
    is_probable_plate,
    normalize_bic,
    normalize_plate,
)


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("abc-123", "ABC123"),
        ("ABC 123", "ABC123"),
        ("  p123456  ", "P123456"),
        ("a.b.c", "ABC"),
        ("P-1 2-3 4-5-6", "P123456"),
        ("", ""),
    ],
)
def test_normalize_plate(raw: str, expected: str):
    """Plates are uppercased and stripped of every separator."""
    assert normalize_plate(raw) == expected


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("csqu3054383", "CSQU3054383"),
        ("CSQU 3054383", "CSQU3054383"),
        ("CSQU-3054383", "CSQU3054383"),
        ("CSQU3054383 45G1", "CSQU3054383"),
    ],
)
def test_normalize_bic_keeps_the_code(raw: str, expected: str):
    """The BIC code is uppercased and stripped of separators."""
    assert normalize_bic(raw) == expected


def test_normalize_bic_fixes_the_class_of_each_position():
    """A digit in the owner part becomes a letter and the other way round."""
    # "0" in the owner part is read as the letter O.
    assert normalize_bic("CSQ03054383") == "CSQO3054383"
    # A letter in the serial part is read as the matching digit.
    assert normalize_bic("CSQU305438B") == "CSQU3054388"
    assert normalize_bic("CSQU3O54383") == "CSQU3054383"
    assert normalize_bic("CSQU30543B3") == "CSQU3054383"


def test_normalize_bic_truncates_to_eleven_characters():
    """Extra characters (size/type code) are dropped."""
    assert len(normalize_bic("CSQU305438345G1")) == 11


@pytest.mark.parametrize(
    "text,expected",
    [
        ("CSQU3054383", True),
        ("CSQU305438", False),
        ("CSQX3054383", False),
        ("CSQU30543AB", False),
    ],
)
def test_is_probable_bic(text: str, expected: bool):
    """The shape of a BIC code is verified before the check digit."""
    assert is_probable_bic(text) is expected


@pytest.mark.parametrize(
    "text,expected",
    [("ABC123", True), ("P123456", True), ("AB", False), ("ABCDEFGHIJK", False)],
)
def test_is_probable_plate(text: str, expected: bool):
    """A plate has between five and eight alphanumeric characters."""
    assert is_probable_plate(text) is expected
