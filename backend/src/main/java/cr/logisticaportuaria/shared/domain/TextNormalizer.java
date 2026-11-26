package cr.logisticaportuaria.shared.domain;

import java.util.Locale;

/**
 * Normalization of the texts read at the gate.
 *
 * <p>The raw reading is always kept in its own column; this class only
 * produces the normalized value used to match the master data.
 */
public final class TextNormalizer {

    private TextNormalizer() {
        // utility class
    }

    /**
     * Normalizes a plate: upper case, without spaces, dashes or dots.
     *
     * <p>Only ASCII letters and digits are kept: the plates of Costa Rica and
     * the BIC codes are ASCII, so any other character read by the OCR (an
     * accent, a {@code Ñ}, a punctuation mark) is noise for the matching. The
     * raw reading is always kept in its own column.
     *
     * @param raw raw text of the reading
     * @return the normalized plate (may be null)
     */
    public static String normalizePlate(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (char character : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            if (isAsciiLetterOrDigit(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return (character >= 'A' && character <= 'Z') || (character >= '0' && character <= '9');
    }

    /**
     * Normalizes a BIC code (ISO 6346): upper case and without spaces.
     *
     * @param raw raw text of the reading
     * @return the normalized code (may be null)
     */
    public static String normalizeContainer(String raw) {
        return normalizePlate(raw);
    }
}
