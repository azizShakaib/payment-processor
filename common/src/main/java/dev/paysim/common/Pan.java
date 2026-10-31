package dev.paysim.common;

import java.util.Objects;

/**
 * Card number (Primary Account Number) helpers. A clear PAN is PCI-scoped data,
 * so nothing here ever puts PAN digits into an exception message: errors carry
 * only the length, an index and the reason.
 */
public final class Pan {

    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 19;
    private static final int BIN_LENGTH = 6;
    private static final int LAST_DIGITS = 4;

    private Pan() {
    }

    /** {@code "4111111111111111"} becomes {@code "411111******1111"}. */
    public static String mask(String pan) {
        validate(pan);
        int length = pan.length();
        return pan.substring(0, BIN_LENGTH)
                + "*".repeat(length - BIN_LENGTH - LAST_DIGITS)
                + pan.substring(length - LAST_DIGITS);
    }

    /** Luhn mod-10 check, the checksum every issuer builds into card numbers. */
    public static boolean isLuhnValid(String pan) {
        validate(pan);
        int sum = 0;
        boolean doubleIt = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int digit = pan.charAt(i) - '0';
            if (doubleIt) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static void validate(String pan) {
        Objects.requireNonNull(pan, "PAN must not be null");
        int length = pan.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "PAN must be 12-19 digits, got length " + length);
        }
        for (int i = 0; i < length; i++) {
            char c = pan.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(
                        "PAN contains a non-digit at index " + i);
            }
        }
    }
}
