package dev.paysim.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PanTest {

    @Test
    void masksSixteenDigitVisa() {
        assertThat(Pan.mask("4111111111111111")).isEqualTo("411111******1111");
    }

    @Test
    void masksMinimumLength() {
        assertThat(Pan.mask("123456789012")).isEqualTo("123456**9012");
    }

    @Test
    void masksMaximumLength() {
        assertThat(Pan.mask("1234567890123456789")).isEqualTo("123456*********6789");
    }

    @Test
    void rejectsTooShort() {
        assertThatThrownBy(() -> Pan.mask("12345678901"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length 11");
    }

    @Test
    void rejectsTooLong() {
        assertThatThrownBy(() -> Pan.mask("12345678901234567890"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length 20");
    }

    @Test
    void rejectsNonDigits() {
        assertThatThrownBy(() -> Pan.mask("4111-1111-1111-1111"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pan.mask("41111111111111 1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 14");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> Pan.mask(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Pan.isLuhnValid(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void errorMessageNeverContainsPanDigits() {
        String input = "41111111x1111111";
        assertThat(input).hasSize(16);
        assertThatThrownBy(() -> Pan.mask(input))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("411111")
                        .doesNotContain("1111111")
                        .doesNotContain(input)
                        .doesNotContainPattern(Pattern.compile("\\d{6,}")));
    }

    @Test
    void luhnAcceptsKnownValidNumbers() {
        assertThat(Pan.isLuhnValid("4111111111111111")).isTrue();
        assertThat(Pan.isLuhnValid("5500005555555559")).isTrue();
        assertThat(Pan.isLuhnValid("378282246310005")).isTrue();
    }

    @Test
    void luhnRejectsOneDigitOff() {
        assertThat(Pan.isLuhnValid("4111111111111112")).isFalse();
        assertThat(Pan.isLuhnValid("5500005555555558")).isFalse();
        assertThat(Pan.isLuhnValid("378282246310006")).isFalse();
    }

    @Test
    void luhnValidatesInput() {
        assertThatThrownBy(() -> Pan.isLuhnValid("123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pan.isLuhnValid("41111111111111a1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
