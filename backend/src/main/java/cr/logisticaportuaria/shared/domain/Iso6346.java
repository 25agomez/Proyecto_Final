package cr.logisticaportuaria.shared.domain;

import java.util.Map;

/**
 * Validation of the BIC codes (ISO 6346).
 *
 * <p>The check digit is computed over the first ten characters:
 * {@code sum(value(char[i]) * 2^i) % 11 % 10}. The values of the letters are
 * the standard ones (A=10, B=12, C=13, ... skipping the multiples of 11).
 *
 * <p>The implementation mirrors the one of the edge service
 * ({@code edge-ocr/app/pipeline/iso6346.py}) so both sides agree on which
 * codes are valid.
 */
public final class Iso6346 {

    /** Length of a BIC code. */
    public static final int CODE_LENGTH = 11;

    /** Number of letters of the prefix (owner code plus the category U, J or Z). */
    public static final int OWNER_LENGTH = 4;

    private static final Map<Character, Integer> LETTER_VALUES = Map.ofEntries(
            Map.entry('A', 10), Map.entry('B', 12), Map.entry('C', 13), Map.entry('D', 14),
            Map.entry('E', 15), Map.entry('F', 16), Map.entry('G', 17), Map.entry('H', 18),
            Map.entry('I', 19), Map.entry('J', 20), Map.entry('K', 21), Map.entry('L', 23),
            Map.entry('M', 24), Map.entry('N', 25), Map.entry('O', 26), Map.entry('P', 27),
            Map.entry('Q', 28), Map.entry('R', 29), Map.entry('S', 30), Map.entry('T', 31),
            Map.entry('U', 32), Map.entry('V', 34), Map.entry('W', 35), Map.entry('X', 36),
            Map.entry('Y', 37), Map.entry('Z', 38));

    private Iso6346() {
        // utility class
    }

    /**
     * @param code BIC code
     * @return true when the code has the format of ISO 6346 and its check digit is correct
     */
    public static boolean isValid(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return false;
        }
        String upper = code.toUpperCase(java.util.Locale.ROOT);
        for (int index = 0; index < OWNER_LENGTH; index++) {
            if (!Character.isLetter(upper.charAt(index))) {
                return false;
            }
        }
        if ("UJZ".indexOf(upper.charAt(3)) < 0) {
            return false;
        }
        for (int index = OWNER_LENGTH; index < CODE_LENGTH; index++) {
            if (!Character.isDigit(upper.charAt(index))) {
                return false;
            }
        }
        return checkDigit(upper.substring(0, CODE_LENGTH - 1)) == Character.digit(upper.charAt(CODE_LENGTH - 1), 10);
    }

    /**
     * Computes the check digit of a prefix of ten characters.
     *
     * @param prefix first ten characters of the code
     * @return the check digit (0..9)
     * @throws IllegalArgumentException when the prefix has an invalid length or an invalid symbol
     */
    public static int checkDigit(String prefix) {
        if (prefix == null || prefix.length() != CODE_LENGTH - 1) {
            throw new IllegalArgumentException(
                    "El prefijo del código BIC debe tener %d caracteres.".formatted(CODE_LENGTH - 1));
        }
        int total = 0;
        for (int index = 0; index < prefix.length(); index++) {
            int value = charValue(prefix.charAt(index));
            total += value * (1 << index);
        }
        return (total % 11) % 10;
    }

    /**
     * @param character character of the code
     * @return the value of the character in ISO 6346
     * @throws IllegalArgumentException when the character is not valid
     */
    public static int charValue(char character) {
        char upper = Character.toUpperCase(character);
        if (Character.isDigit(upper)) {
            return Character.digit(upper, 10);
        }
        Integer value = LETTER_VALUES.get(upper);
        if (value == null) {
            throw new IllegalArgumentException("Carácter inválido en un código BIC: " + character);
        }
        return value;
    }
}
