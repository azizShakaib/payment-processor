package dev.paysim.iso8583;

import java.util.Arrays;
import java.util.Objects;

/**
 * The value of one data element: text for every type except B, bytes for B.
 * Values can be card numbers, track data or PIN blocks, so no {@code toString}
 * here ever prints the content, only its size.
 */
public sealed interface FieldValue permits FieldValue.Text, FieldValue.Binary {

    static Text text(String value) {
        return new Text(value);
    }

    static Binary binary(byte[] value) {
        return new Binary(value);
    }

    /** Character data (types N, AN, ANS, Z, XN). */
    record Text(String value) implements FieldValue {

        public Text {
            Objects.requireNonNull(value, "text value must not be null");
        }

        @Override
        public String toString() {
            return "Text[" + value.length() + " chars]";
        }
    }

    /** Binary data (type B). The array is copied on the way in and on the way out. */
    record Binary(byte[] value) implements FieldValue {

        public Binary {
            Objects.requireNonNull(value, "binary value must not be null");
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Binary that && Arrays.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }

        @Override
        public String toString() {
            return "Binary[" + value.length + " bytes]";
        }
    }
}
