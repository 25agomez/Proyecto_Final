"""Text normalisation and position aware OCR confusion correction."""

from __future__ import annotations

import re

#: Letters that look like digits (used when a digit is expected).
DIGIT_LOOKALIKES: dict[str, str] = {
    "O": "0",
    "Q": "0",
    "D": "0",
    "I": "1",
    "L": "1",
    "S": "5",
    "B": "8",
    "Z": "2",
    "G": "6",
    "T": "7",
}

#: Digits that look like letters (used when a letter is expected).
LETTER_LOOKALIKES: dict[str, str] = {
    "0": "O",
    "1": "I",
    "5": "S",
    "8": "B",
    "2": "Z",
    "6": "G",
    "7": "T",
}

BIC_OWNER_LENGTH = 4
BIC_LENGTH = 11

_NON_ALNUM = re.compile(r"[^A-Z0-9]")


def _strip(raw: str) -> str:
    """Uppercase and drop every character that is not a letter or a digit."""
    return _NON_ALNUM.sub("", (raw or "").upper())


def normalize_plate(raw: str) -> str:
    """Normalise a Costa Rican plate: uppercase, no dashes or spaces.

    The raw reading is kept untouched by the caller (``raw_text``); this function
    only produces the comparable form.
    """
    return _strip(raw)


def normalize_bic(raw: str) -> str:
    """Normalise a BIC code and correct characters according to their position.

    Positions 1-4 must be letters (owner code + category), positions 5-11 must be
    digits. A character that clearly belongs to the other class is converted when
    the visual confusion is unambiguous.
    """
    text = _strip(raw)
    if not text:
        return ""

    chars = list(text[:BIC_LENGTH])
    for index in range(len(chars)):
        char = chars[index]
        if index < BIC_OWNER_LENGTH:
            if char.isdigit() and char in LETTER_LOOKALIKES:
                chars[index] = LETTER_LOOKALIKES[char]
        else:
            if char.isalpha() and char in DIGIT_LOOKALIKES:
                chars[index] = DIGIT_LOOKALIKES[char]
    return "".join(chars)


def is_probable_plate(text: str) -> bool:
    """Heuristic: a plate has between 5 and 8 alphanumeric characters."""
    return 5 <= len(text) <= 8


def is_probable_bic(text: str) -> bool:
    """Heuristic: a BIC code has 11 characters with the expected class per position."""
    if len(text) != BIC_LENGTH:
        return False
    if not text[:BIC_OWNER_LENGTH].isalpha():
        return False
    if text[3] not in {"U", "J", "Z"}:
        return False
    return text[BIC_OWNER_LENGTH:].isdigit()
