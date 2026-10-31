package dev.paysim.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void formatsTwoDecimalCurrency() {
        assertThat(Money.of(1500, "USD")).hasToString("15.00 USD");
        assertThat(Money.of(5, "USD")).hasToString("0.05 USD");
        assertThat(Money.of(0, "USD")).hasToString("0.00 USD");
    }

    @Test
    void formatsZeroDecimalCurrency() {
        assertThat(Money.of(1500, "JPY")).hasToString("1500 JPY");
        assertThat(Money.of(-1500, "JPY")).hasToString("-1500 JPY");
    }

    @Test
    void formatsThreeDecimalCurrency() {
        assertThat(Money.of(1500, "BHD")).hasToString("1.500 BHD");
        assertThat(Money.of(7, "BHD")).hasToString("0.007 BHD");
    }

    @Test
    void formatsNegativeAmounts() {
        assertThat(Money.of(-1500, "USD")).hasToString("-15.00 USD");
        assertThat(Money.of(-50, "USD")).hasToString("-0.50 USD");
        assertThat(Money.of(-5, "BHD")).hasToString("-0.005 BHD");
    }

    @Test
    void formatsExtremeValuesWithoutOverflow() {
        assertThat(Money.of(Long.MIN_VALUE, "USD")).hasToString("-92233720368547758.08 USD");
        assertThat(Money.of(Long.MAX_VALUE, "USD")).hasToString("92233720368547758.07 USD");
    }

    @Test
    void looksUpByNumericCode() {
        assertThat(Money.ofNumeric(100, 840).currency().getCurrencyCode()).isEqualTo("USD");
        assertThat(Money.ofNumeric(100, 978).currency().getCurrencyCode()).isEqualTo("EUR");
        assertThat(Money.ofNumeric(100, 392).currency().getCurrencyCode()).isEqualTo("JPY");
    }

    @Test
    void numericCodeRoundTrips() {
        assertThat(Money.of(1, "USD").numericCode()).isEqualTo(840);
        assertThat(Money.ofNumeric(1, 840)).isEqualTo(Money.of(1, "USD"));
    }

    @Test
    void rejectsUnknownNumericCode() {
        assertThatThrownBy(() -> Money.ofNumeric(1, 999_999))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.ofNumeric(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownAlphaCodeAndNulls() {
        assertThatThrownBy(() -> Money.of(1, "ZZZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Money(1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityUsesAmountAndCurrency() {
        assertThat(new Money(100, Currency.getInstance("USD"))).isEqualTo(Money.of(100, "USD"));
        assertThat(Money.of(100, "USD")).isNotEqualTo(Money.of(100, "EUR"));
    }
}
