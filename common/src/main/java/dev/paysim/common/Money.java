package dev.paysim.common;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money as a whole number of minor units (cents, pence, yen)
 * plus its ISO 4217 currency. Card networks move money as integers: a
 * {@code double} cannot represent 0.10 exactly and drifts by a cent after a
 * few operations. How many minor units make one major unit depends on the
 * currency (JPY 0 decimals, USD 2, BHD 3), so the amount is meaningless
 * without the currency.
 */
public record Money(long minorUnits, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    /** {@code Money.of(1500, "USD")} is 15.00 USD. */
    public static Money of(long minorUnits, String alphaCode) {
        Objects.requireNonNull(alphaCode, "currency code must not be null");
        try {
            return new Money(minorUnits, Currency.getInstance(alphaCode));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown ISO 4217 alpha code, length " + alphaCode.length(), e);
        }
    }

    /** ISO 8583 field 49 carries the numeric code: 840 is USD, 978 EUR, 392 JPY. */
    public static Money ofNumeric(long minorUnits, int numericCode) {
        for (Currency candidate : Currency.getAvailableCurrencies()) {
            // some historic currencies report numeric code 0, which is not a real ISO 4217 code
            if (numericCode > 0 && candidate.getNumericCode() == numericCode) {
                return new Money(minorUnits, candidate);
            }
        }
        throw new IllegalArgumentException("unknown ISO 4217 numeric code " + numericCode);
    }

    public int numericCode() {
        return currency.getNumericCode();
    }

    /** {@code "15.00 USD"}, {@code "1500 JPY"}, {@code "-0.50 USD"}. Integer arithmetic only. */
    @Override
    public String toString() {
        int digits = Math.max(0, currency.getDefaultFractionDigits());
        if (digits == 0) {
            return minorUnits + " " + currency.getCurrencyCode();
        }
        long divisor = 1;
        for (int i = 0; i < digits; i++) {
            divisor *= 10;
        }
        String whole = Long.toString(minorUnits / divisor);
        if (whole.startsWith("-")) {
            whole = whole.substring(1);
        }
        String fraction = Long.toString(Math.abs(minorUnits % divisor));
        fraction = "0".repeat(digits - fraction.length()) + fraction;
        return (minorUnits < 0 ? "-" : "") + whole + "." + fraction + " " + currency.getCurrencyCode();
    }
}
