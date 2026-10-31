package dev.paysim.iso8583.spec;

import java.util.Objects;

/**
 * Definition of one data element. {@code maxLength} is in characters for text
 * types (XN counts its sign, so x+n 8 has maxLength 9) and in bytes for type B.
 */
public record FieldSpec(
        int number,
        String name,
        FieldType type,
        LengthKind lengthKind,
        int maxLength,
        Encoding encoding,
        Padding padding) {

    /** Field 1 is the secondary bitmap, so data elements start at 2. */
    public static final int MIN_NUMBER = 2;
    public static final int MAX_NUMBER = 128;

    public FieldSpec {
        if (number < MIN_NUMBER || number > MAX_NUMBER) {
            throw new IllegalArgumentException(
                    "field " + number + " must be in 2..128 (field 1 is the secondary bitmap)");
        }
        String label = "field " + number;
        Objects.requireNonNull(name, label + ": name must not be null");
        Objects.requireNonNull(type, label + ": type must not be null");
        Objects.requireNonNull(lengthKind, label + ": lengthKind must not be null");
        Objects.requireNonNull(encoding, label + ": encoding must not be null");
        Objects.requireNonNull(padding, label + ": padding must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException(label + ": name must not be blank");
        }
        if (maxLength < 1) {
            throw new IllegalArgumentException(label + ": maxLength must be at least 1, got " + maxLength);
        }
        if (maxLength > lengthKind.maxPossibleLength()) {
            throw new IllegalArgumentException(label + ": maxLength " + maxLength
                    + " exceeds the " + lengthKind + " limit of " + lengthKind.maxPossibleLength());
        }
        if (encoding == Encoding.BINARY && type != FieldType.B) {
            throw new IllegalArgumentException(label + ": BINARY encoding is only valid for type B, not " + type);
        }
        if (encoding == Encoding.BCD && type != FieldType.N && type != FieldType.Z) {
            throw new IllegalArgumentException(label + ": BCD encoding is only valid for type N or Z, not " + type);
        }
        if (lengthKind != LengthKind.FIXED && padding != Padding.NONE) {
            throw new IllegalArgumentException(
                    label + ": padding must be NONE for " + lengthKind + " fields, got " + padding);
        }
        if (padding == Padding.LEFT_ZEROS && type != FieldType.N && type != FieldType.XN) {
            throw new IllegalArgumentException(
                    label + ": LEFT_ZEROS padding is only valid for type N or XN, not " + type);
        }
        if (padding == Padding.RIGHT_SPACES
                && type != FieldType.AN && type != FieldType.ANS && type != FieldType.Z) {
            throw new IllegalArgumentException(
                    label + ": RIGHT_SPACES padding is only valid for type AN, ANS or Z, not " + type);
        }
        if (type == FieldType.B && padding != Padding.NONE) {
            throw new IllegalArgumentException(label + ": padding must be NONE for type B, got " + padding);
        }
    }

    /**
     * Creates a spec with the conventional padding: fixed numeric fields are
     * zero-filled on the left, other fixed text fields space-filled on the
     * right, everything else unpadded.
     */
    public static FieldSpec of(int number, String name, FieldType type, LengthKind lengthKind,
                               int maxLength, Encoding encoding) {
        Objects.requireNonNull(type, "field " + number + ": type must not be null");
        Objects.requireNonNull(lengthKind, "field " + number + ": lengthKind must not be null");
        Padding padding = Padding.NONE;
        if (lengthKind == LengthKind.FIXED) {
            padding = switch (type) {
                case N, XN -> Padding.LEFT_ZEROS;
                case AN, ANS, Z -> Padding.RIGHT_SPACES;
                case B -> Padding.NONE;
            };
        }
        return new FieldSpec(number, name, type, lengthKind, maxLength, encoding, padding);
    }
}
