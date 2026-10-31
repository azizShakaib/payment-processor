package dev.paysim.iso8583.codec;

import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import dev.paysim.iso8583.spec.Padding;

/**
 * Character-set check shared by pack and unpack, so both directions accept
 * exactly the same values. Reports an index and a reason, never the character.
 */
final class TextRules {

    /** Where the first bad character is and why; {@code index} is within the field. */
    record Violation(int index, String reason) {
    }

    private TextRules() {
    }

    /**
     * Returns null when {@code text} is valid for the spec's type. Fixed fields
     * padded with spaces may end in spaces even if the type forbids them (AN),
     * because the padding itself is spaces and unpack returns it.
     */
    static Violation check(FieldSpec spec, String text) {
        FieldType type = spec.type();
        int end = text.length();
        if (spec.lengthKind() == LengthKind.FIXED && spec.padding() == Padding.RIGHT_SPACES) {
            while (end > 0 && text.charAt(end - 1) == ' ') {
                end--;
            }
        }
        for (int i = 0; i < end; i++) {
            if (!charOk(type, text.charAt(i), i)) {
                return new Violation(i, describe(type, i) + " at index " + i + " for type " + type);
            }
        }
        if (type == FieldType.XN && end < 2) {
            return new Violation(0, "XN needs a C or D sign and at least one digit");
        }
        return null;
    }

    private static boolean charOk(FieldType type, char c, int index) {
        if (type == FieldType.XN) {
            return index == 0 ? c == 'C' || c == 'D' : c >= '0' && c <= '9';
        }
        return type.isValid(String.valueOf(c));
    }

    private static String describe(FieldType type, int index) {
        if (type == FieldType.N || (type == FieldType.XN && index > 0)) {
            return "non-digit";
        }
        if (type == FieldType.XN) {
            return "invalid sign";
        }
        return "invalid character";
    }
}
