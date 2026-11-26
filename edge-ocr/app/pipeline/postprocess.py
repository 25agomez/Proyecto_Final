"""Post-processing of a raw reading: normalisation, validation and repair.

The functions live in their own module so the service, the tests and the
evaluation tools all exercise exactly the same code path.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from . import iso6346
from .normalize import (
    is_probable_bic,
    is_probable_plate,
    normalize_bic,
    normalize_plate,
)

#: A BIC code inside a text that may also carry the size/type code (e.g. 45G1).
BIC_PATTERN = re.compile(r"[A-Z]{3}[UJZ][0-9]{7}")

CONTAINER = "CONTAINER"
PLATE = "PLATE"


@dataclass(frozen=True)
class ValidatedReading:
    """Result of the validation of a normalised reading."""

    text: str
    iso_valid: bool | None
    corrected: bool


def extract_bic(text: str) -> str:
    """Extract the BIC code from a text that may carry the size/type code."""
    match = BIC_PATTERN.search(text.upper())
    return match.group(0) if match else ""


def normalise_reading(kind: str, raw_text: str) -> str:
    """Normalise a raw reading according to the kind of identifier."""
    if kind == CONTAINER:
        return normalize_bic(extract_bic(raw_text) or raw_text)
    return normalize_plate(raw_text)


def is_probable(kind: str, text: str) -> bool:
    """True when the text has the shape expected for the kind of identifier."""
    if kind == CONTAINER:
        return is_probable_bic(text)
    return is_probable_plate(text)


def validate_reading(kind: str, normalised: str) -> ValidatedReading:
    """Validate a normalised reading and repair it when the check digit fails.

    A reading that cannot be repaired keeps ``iso_valid=False`` so the backend
    sends it to the manual review queue.
    """
    if kind != CONTAINER:
        return ValidatedReading(text=normalised, iso_valid=None, corrected=False)
    if not iso6346.format_matches(normalised):
        return ValidatedReading(text=normalised, iso_valid=False, corrected=False)
    if iso6346.is_valid(normalised):
        return ValidatedReading(text=normalised, iso_valid=True, corrected=False)
    repaired, corrected = iso6346.repair(normalised, max_changes=1)
    if corrected:
        return ValidatedReading(text=repaired, iso_valid=True, corrected=True)
    return ValidatedReading(text=normalised, iso_valid=False, corrected=False)
