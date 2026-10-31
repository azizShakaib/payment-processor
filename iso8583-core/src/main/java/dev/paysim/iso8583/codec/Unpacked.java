package dev.paysim.iso8583.codec;

import dev.paysim.iso8583.FieldValue;

/** A decoded field plus the absolute offset of the first byte after it. */
public record Unpacked(FieldValue value, int nextOffset) {
}
