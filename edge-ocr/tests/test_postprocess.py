"""Tests of the shared post-processing layer."""

from __future__ import annotations

from app.pipeline import postprocess


def test_extract_bic_ignores_the_size_type_code() -> None:
    """The size/type code printed next to the BIC is not part of the code."""
    assert postprocess.extract_bic("CSQU3054383 45G1") == "CSQU3054383"
    assert postprocess.extract_bic("csqu3054383") == "CSQU3054383"
    assert postprocess.extract_bic("no code here") == ""


def test_normalise_reading_by_kind() -> None:
    """Each kind of identifier has its own normalisation."""
    assert postprocess.normalise_reading("CONTAINER", "CSQU 3054383") == "CSQU3054383"
    assert postprocess.normalise_reading("PLATE", "abc-123") == "ABC123"


def test_is_probable_by_kind() -> None:
    """The shape of the reading is checked before the validation."""
    assert postprocess.is_probable("CONTAINER", "CSQU3054383") is True
    assert postprocess.is_probable("CONTAINER", "CSQU3054AB3") is False
    assert postprocess.is_probable("PLATE", "ABC123") is True
    assert postprocess.is_probable("PLATE", "ABC") is False


def test_validate_reading_accepts_a_valid_code() -> None:
    """A code with a correct check digit is published untouched."""
    result = postprocess.validate_reading("CONTAINER", "CSQU3054383")
    assert result.iso_valid is True
    assert result.corrected is False
    assert result.text == "CSQU3054383"


def test_validate_reading_repairs_a_single_confusion() -> None:
    """One confused character is repaired and flagged."""
    result = postprocess.validate_reading("CONTAINER", "CSQU3054883")
    assert result.iso_valid is True
    assert result.corrected is True
    assert result.text == "CSQU3054383"


def test_validate_reading_keeps_an_unrepairable_code() -> None:
    """A code that cannot be repaired goes to the manual review queue."""
    result = postprocess.validate_reading("CONTAINER", "CSQU3054384")
    assert result.iso_valid is False
    assert result.corrected is False
    assert result.text == "CSQU3054384"


def test_validate_reading_ignores_the_check_digit_of_plates() -> None:
    """Plates have no ISO 6346 check digit."""
    result = postprocess.validate_reading("PLATE", "ABC123")
    assert result.iso_valid is None
    assert result.corrected is False
