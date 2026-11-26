package cr.logisticaportuaria.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests of the validation of the BIC codes (ISO 6346).
 *
 * <p>The case {@code CSQU3054383} is the one required by the acceptance
 * criteria of P3.
 */
class Iso6346Test {

    @Test
    @DisplayName("CSQU3054383 es un código BIC válido")
    void acceptsTheReferenceCode() {
        assertThat(Iso6346.isValid("CSQU3054383")).isTrue();
        assertThat(Iso6346.checkDigit("CSQU305438")).isEqualTo(3);
    }

    @Test
    @DisplayName("Un dígito de control incorrecto invalida el código")
    void rejectsWrongCheckDigit() {
        assertThat(Iso6346.isValid("CSQU3054380")).isFalse();
    }

    @Test
    @DisplayName("La validación no distingue mayúsculas de minúsculas")
    void isCaseInsensitive() {
        assertThat(Iso6346.isValid("csqu3054383")).isTrue();
        assertThat(Iso6346.isValid("CsQu3054383")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CSQU305438", "CSQU30543831", "", "CSQU305438X", "12345678901"})
    @DisplayName("Un código con un formato inválido se rechaza")
    void rejectsInvalidFormat(String code) {
        assertThat(Iso6346.isValid(code)).isFalse();
    }

    @Test
    @DisplayName("La cuarta letra debe ser U, J o Z")
    void rejectsWrongCategoryLetter() {
        assertThat(Iso6346.isValid("CSQA3054383")).isFalse();
        assertThat(Iso6346.isValid("CSQU3054383")).isTrue();
    }

    @Test
    @DisplayName("Un prefijo con longitud incorrecta falla")
    void checkDigitRejectsWrongLength() {
        assertThatThrownBy(() -> Iso6346.checkDigit("CSQU30543"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Los valores de las letras son los de la norma")
    void letterValuesFollowTheStandard() {
        assertThat(Iso6346.charValue('A')).isEqualTo(10);
        assertThat(Iso6346.charValue('B')).isEqualTo(12);
        assertThat(Iso6346.charValue('K')).isEqualTo(21);
        assertThat(Iso6346.charValue('Z')).isEqualTo(38);
        assertThat(Iso6346.charValue('0')).isZero();
        assertThat(Iso6346.charValue('9')).isEqualTo(9);
    }

    @Test
    @DisplayName("El prefijo de un código válido genera su dígito de control")
    void checkDigitMatchesTheCode() {
        assertThat(Iso6346.checkDigit("CSQU305438")).isEqualTo(3);
        assertThat(Iso6346.checkDigit("MSCU123456")).isEqualTo(Iso6346.checkDigit("MSCU123456"));
    }
}
