"""ISO 6346 container code validation (check digit).

The check digit is computed over the first ten characters of a BIC code:

    sum(value(char[i]) * 2**i for i in range(10)) % 11 % 10

Letter values follow the standard: A=10, B=12, C=13, ... (multiples of 11 are
skipped). Digits keep their numeric value.
"""

from __future__ import annotations

from collections.abc import Iterable

LETTER_VALUES: dict[str, int] = {
    "A": 10,
    "B": 12,
    "C": 13,
    "D": 14,
    "E": 15,
    "F": 16,
    "G": 17,
    "H": 18,
    "I": 19,
    "J": 20,
    "K": 21,
    "L": 23,
    "M": 24,
    "N": 25,
    "O": 26,
    "P": 27,
    "Q": 28,
    "R": 29,
    "S": 30,
    "T": 31,
    "U": 32,
    "V": 34,
    "W": 35,
    "X": 36,
    "Y": 37,
    "Z": 38,
}

#: Characters that are visually ambiguous in OCR output.
#: The table mixes letter/letter, digit/digit and letter/digit confusions.
#: The repair step filters the candidates by the class required at each position
#: (letters on the owner code, digits on the serial and on the check digit).
CONFUSABLE_GROUPS: tuple[tuple[str, ...], ...] = (
    ("O", "Q", "D", "0"),
    ("I", "L", "1"),
    ("S", "5"),
    ("B", "8"),
    ("Z", "2"),
    ("G", "6"),
    ("T", "7"),
    ("C", "G"),
    ("E", "F"),
    ("U", "V"),
    ("3", "8"),
    ("5", "6"),
    ("5", "8"),
    ("6", "8"),
    ("8", "9"),
    ("0", "6"),
    ("0", "8"),
    ("1", "7"),
    ("2", "7"),
    ("3", "9"),
    ("4", "9"),
)

CODE_LENGTH = 11
OWNER_LENGTH = 4


def char_value(char: str) -> int | None:
    """Return the ISO 6346 value of a single character, or None if not valid."""
    if not char:
        return None
    upper = char.upper()
    if upper.isdigit():
        return int(upper)
    return LETTER_VALUES.get(upper)


def check_digit(prefix: str) -> int:
    """Compute the check digit for the first ten characters of a BIC code.

    Raises:
        ValueError: if the prefix is not ten characters or holds an invalid symbol.
    """
    if len(prefix) != CODE_LENGTH - 1:
        raise ValueError(f"expected {CODE_LENGTH - 1} characters, got {len(prefix)}")
    total = 0
    for index, char in enumerate(prefix.upper()):
        value = char_value(char)
        if value is None:
            raise ValueError(f"invalid character in BIC prefix: {char!r}")
        total += value * (1 << index)
    return (total % 11) % 10


def is_valid(code: str) -> bool:
    """Return True when the code matches the BIC format *and* its check digit."""
    if not code or len(code) != CODE_LENGTH:
        return False
    upper = code.upper()
    if not upper[:OWNER_LENGTH].isalpha():
        return False
    if upper[3] not in {"U", "J", "Z"}:
        return False
    if not upper[OWNER_LENGTH:].isdigit():
        return False
    try:
        expected = check_digit(upper[: CODE_LENGTH - 1])
    except ValueError:
        return False
    return expected == int(upper[CODE_LENGTH - 1])


def format_matches(code: str) -> bool:
    """Return True when the code only matches the *format* (digits may be wrong)."""
    if not code or len(code) != CODE_LENGTH:
        return False
    upper = code.upper()
    return (
        upper[:OWNER_LENGTH].isalpha()
        and upper[3] in {"U", "J", "Z"}
        and upper[OWNER_LENGTH:].isdigit()
    )


def _confusable_candidates(char: str, want_letter: bool) -> Iterable[str]:
    """Yield visually similar replacements that keep the class of the position.

    Args:
        char: the character that was read.
        want_letter: True for the owner code positions (1-4), False for the
            serial and the check digit.
    """
    upper = char.upper()
    for group in CONFUSABLE_GROUPS:
        if upper not in group:
            continue
        for candidate in group:
            if candidate == upper:
                continue
            if want_letter and not candidate.isalpha():
                continue
            if not want_letter and not candidate.isdigit():
                continue
            yield candidate


def _repair_one(code: str) -> str | None:
    """Return the code obtained by changing exactly one character, if any."""
    for position in range(CODE_LENGTH):
        want_letter = position < OWNER_LENGTH
        for candidate in _confusable_candidates(code[position], want_letter):
            attempt = code[:position] + candidate + code[position + 1 :]
            if is_valid(attempt):
                return attempt
    return None


def _repair_two(code: str) -> str | None:
    """Return the code obtained by changing exactly two characters, if any."""
    for first in range(CODE_LENGTH):
        want_letter_a = first < OWNER_LENGTH
        for candidate_a in _confusable_candidates(code[first], want_letter_a):
            partial = code[:first] + candidate_a + code[first + 1 :]
            for second in range(first + 1, CODE_LENGTH):
                want_letter_b = second < OWNER_LENGTH
                for candidate_b in _confusable_candidates(partial[second], want_letter_b):
                    attempt = partial[:second] + candidate_b + partial[second + 1 :]
                    if is_valid(attempt):
                        return attempt
    return None


def repair(code: str, max_changes: int = 1) -> tuple[str, bool]:
    """Try to fix a failing check digit by changing at most ``max_changes`` characters.

    Only visually confusable characters are considered and the class of every
    position is preserved (letters on the owner code, digits elsewhere).

    Returns:
        tuple: ``(code, corrected)``. ``corrected`` is False when no valid
        candidate was found (the code is returned unchanged).
    """
    if not format_matches(code):
        return code, False
    upper = code.upper()
    if is_valid(upper):
        return upper, False

    if max_changes >= 1:
        candidate = _repair_one(upper)
        if candidate is not None:
            return candidate, True

    if max_changes >= 2:
        candidate = _repair_two(upper)
        if candidate is not None:
            return candidate, True

    return upper, False
