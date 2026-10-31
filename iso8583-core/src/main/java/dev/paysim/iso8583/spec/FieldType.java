package dev.paysim.iso8583.spec;

import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * ISO 8583 data element character sets. The type decides which characters a
 * value may hold, which is what lets a receiver reject a corrupt field before
 * it reaches business logic.
 */
public enum FieldType {

    /** Digits 0-9 only, e.g. field 4 amount. */
    N,
    /** Letters and digits, e.g. field 39 response code. */
    AN,
    /** Any printable ASCII (0x20-0x7E), e.g. field 43 card acceptor name/location. */
    ANS,
    /** Binary bytes, e.g. field 52 PIN data. */
    B,
    /** Track 2 characters: digits, '=' separator and 'D', e.g. field 35. */
    Z,
    /** Amount with leading sign: 'C' or 'D' then digits, e.g. field 28 transaction fee. */
    XN;

    /**
     * Checks a text value against this type's character set. An empty string
     * is accepted by every text type except {@link #XN}, which needs its sign
     * and at least one digit; length rules belong to the field spec, not the type.
     *
     * @throws UnsupportedOperationException for {@link #B}: binary values are
     *         byte arrays, not text
     */
    public boolean isValid(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return switch (this) {
            case N -> allMatch(value, 0, FieldType::isDigit);
            case AN -> allMatch(value, 0, c -> isDigit((char) c) || isLetter((char) c));
            case ANS -> allMatch(value, 0, c -> c >= 0x20 && c <= 0x7E);
            case Z -> allMatch(value, 0, c -> isDigit((char) c) || c == '=' || c == 'D');
            case XN -> value.length() >= 2
                    && (value.charAt(0) == 'C' || value.charAt(0) == 'D')
                    && allMatch(value, 1, FieldType::isDigit);
            case B -> throw new UnsupportedOperationException(
                    "binary values are byte[] and are not validated as text");
        };
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean allMatch(String value, int from, IntPredicate test) {
        for (int i = from; i < value.length(); i++) {
            if (!test.test(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
