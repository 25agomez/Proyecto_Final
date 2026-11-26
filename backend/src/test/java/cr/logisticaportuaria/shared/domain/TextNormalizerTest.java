package cr.logisticaportuaria.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the normalization of the texts read at the gate.
 */
class TextNormalizerTest {

    @Test
    @DisplayName("La matrícula se normaliza sin guiones, espacios ni puntos")
    void normalizesPlate() {
        assertThat(TextNormalizer.normalizePlate("abc-123")).isEqualTo("ABC123");
        assertThat(TextNormalizer.normalizePlate(" ABC 123 ")).isEqualTo("ABC123");
        assertThat(TextNormalizer.normalizePlate("a.b.c.1.2.3")).isEqualTo("ABC123");
    }

    @Test
    @DisplayName("La matrícula conserva los dígitos y las letras")
    void keepsLettersAndDigits() {
        assertThat(TextNormalizer.normalizePlate("CR12345")).isEqualTo("CR12345");
        assertThat(TextNormalizer.normalizePlate("Ñ12345")).isEqualTo("12345");
    }

    @Test
    @DisplayName("El código BIC se normaliza a mayúsculas sin espacios")
    void normalizesContainerCode() {
        assertThat(TextNormalizer.normalizeContainer("csqu 3054383")).isEqualTo("CSQU3054383");
        assertThat(TextNormalizer.normalizeContainer("CSQU-3054383")).isEqualTo("CSQU3054383");
    }

    @Test
    @DisplayName("Un texto nulo se normaliza a nulo")
    void handlesNull() {
        assertThat(TextNormalizer.normalizePlate(null)).isNull();
        assertThat(TextNormalizer.normalizeContainer(null)).isNull();
    }
}
