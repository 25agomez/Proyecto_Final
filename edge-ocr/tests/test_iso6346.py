"""ISO 6346 check digit tests (at least 10 scenarios)."""

from __future__ import annotations

import pytest

from app.pipeline import iso6346


def independent_value(char: str) -> int:
    """Value derived with a different formula than the implementation table.

    The ISO 6346 values are 10, 12, 13, ... : they start at 10 and skip the
    multiples of 11 (11, 22, 33).
    """
    if char.isdigit():
        return int(char)
    index = ord(char.upper()) - ord("A")
    return (
        10
        + index
        + (1 if index >= 1 else 0)
        + (1 if index >= 11 else 0)
        + (1 if index >= 21 else 0)
    )


def independent_check_digit(prefix: str) -> int:
    """Check digit computed with the independent value formula."""
    total = sum(independent_value(char) * (1 << index) for index, char in enumerate(prefix.upper()))
    return (total % 11) % 10


def test_letter_values_match_the_standard():
    """Every letter value matches the independent derivation."""
    for index in range(26):
        letter = chr(ord("A") + index)
        assert iso6346.LETTER_VALUES[letter] == independent_value(letter), letter


def test_classic_example_is_valid():
    """The reference code of the prompt validates."""
    assert iso6346.is_valid("CSQU3054383")
    assert iso6346.check_digit("CSQU305438") == 3


@pytest.mark.parametrize(
    "serial",
    [
        "000000",
        "000001",
        "123456",
        "654321",
        "999999",
        "100000",
        "010101",
        "555555",
        "707070",
        "314159",
    ],
)
def test_generated_codes_validate(serial: str):
    """Codes whose check digit is computed independently validate (10 cases)."""
    prefix = f"LRGU{serial}"
    code = prefix + str(independent_check_digit(prefix))
    assert iso6346.is_valid(code), code
    assert iso6346.repair(code) == (code, False)


def test_wrong_check_digit_is_rejected():
    """A valid code with a wrong check digit does not validate."""
    code = "CSQU3054383"
    wrong = code[:10] + str((int(code[10]) + 1) % 10)
    assert not iso6346.is_valid(wrong)
    assert iso6346.format_matches(wrong)


@pytest.mark.parametrize(
    "code",
    [
        "",
        "CSQU305438",  # too short
        "CSQU30543833",  # too long
        "CSQX3054383",  # invalid category
        "CS1U3054383",  # digit in the owner part
        "CSQU305438A",  # letter in the digit part
        "csqu3054383x",  # invalid symbols
    ],
)
def test_malformed_codes_are_rejected(code: str):
    """Codes that do not respect the format never validate."""
    assert not iso6346.is_valid(code)
    assert not iso6346.format_matches(code)


def test_check_digit_raises_on_wrong_length():
    """The check digit requires exactly ten characters."""
    with pytest.raises(ValueError):
        iso6346.check_digit("CSQU30543")
    with pytest.raises(ValueError):
        iso6346.check_digit("CSQU305438X")


def test_repair_fixes_a_single_confusable_character():
    """A single confused character is repaired and flagged as corrected."""
    # The serial digit 3 was read as an 8 (both are digits, so the format holds).
    broken = "CSQU3054883"
    assert not iso6346.is_valid(broken)
    repaired, corrected = iso6346.repair(broken)
    assert corrected is True
    assert repaired == "CSQU3054383"
    assert iso6346.is_valid(repaired)


def test_repair_can_fix_a_misread_check_digit():
    """When the check digit itself was misread, the repair still finds a valid code."""
    broken = "CSQU3054388"
    assert not iso6346.is_valid(broken)
    repaired, corrected = iso6346.repair(broken)
    assert corrected is True
    assert iso6346.is_valid(repaired)
    assert len(repaired) == 11


def test_repair_gives_up_when_two_characters_are_wrong():
    """Two wrong characters cannot be repaired with a single change."""
    broken = "AJQU3054383"
    repaired, corrected = iso6346.repair(broken, max_changes=1)
    assert corrected is False
    assert repaired == broken


def test_repair_with_two_changes_is_possible():
    """With two changes the repair finds a valid code."""
    broken = "AJQU3054383"
    repaired, corrected = iso6346.repair(broken, max_changes=2)
    assert corrected is True
    assert iso6346.is_valid(repaired)


def test_repair_keeps_malformed_codes_untouched():
    """A code that does not respect the format is never "repaired"."""
    broken = "XX!123"
    assert iso6346.repair(broken) == (broken, False)
