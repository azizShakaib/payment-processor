package dev.paysim.iso8583.codec;

import dev.paysim.iso8583.FieldValue;
import dev.paysim.iso8583.IsoPackException;
import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Encodes and decodes one data element (length prefix plus value) under its
 * {@link FieldSpec}. Length is counted in characters for text types (digits
 * for BCD) and in bytes for type B. Error messages carry the field number,
 * offsets, lengths and character indexes, never field content.
 */
public final class FieldCodec {

    private FieldCodec() {
    }

    /**
     * Appends the field to {@code out}. Fixed fields shorter than the spec are
     * padded per {@link FieldSpec#padding()}; XN is the exception and must be
     * given in full with its sign, since zero-padding would push the sign out
     * of first place. Nothing is written if the value is rejected.
     */
    public static void pack(FieldSpec spec, FieldValue value, ByteArrayOutputStream out) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(out, "out must not be null");
        int field = spec.number();
        byte[] data;
        int length;
        if (spec.type() == FieldType.B) {
            if (!(value instanceof FieldValue.Binary binary)) {
                throw new IsoPackException(field, "type B requires a Binary value, got " + kind(value));
            }
            byte[] raw = binary.value();
            length = raw.length;
            checkLength(spec, length);
            data = spec.encoding() == Encoding.BINARY ? raw : HexCodec.encode(raw);
        } else {
            if (!(value instanceof FieldValue.Text text)) {
                throw new IsoPackException(field,
                        "type " + spec.type() + " requires a Text value, got " + kind(value));
            }
            String s = text.value();
            checkLength(spec, s.length());
            TextRules.Violation bad = TextRules.check(spec, s);
            if (bad != null) {
                throw new IsoPackException(field, bad.reason());
            }
            s = pad(spec, s);
            length = s.length();
            data = spec.encoding() == Encoding.BCD
                    ? BcdCodec.encode(spec, s)
                    : s.getBytes(StandardCharsets.US_ASCII);
        }
        if (spec.lengthKind() != LengthKind.FIXED) {
            out.writeBytes(LengthPrefix.encode(spec, length));
        }
        out.writeBytes(data);
    }

    /**
     * Reads the field starting at the absolute {@code offset} of {@code buffer}.
     * Fixed fields come back exactly as on the wire, padding included ("000123"
     * stays "000123"), so unpacking loses nothing; stripping is the caller's
     * business. A BCD track 2 separator always comes back as '='. Exception
     * offsets are absolute positions in {@code buffer}; a read that runs past
     * the end reports the offset where that read started.
     */
    public static Unpacked unpack(FieldSpec spec, byte[] buffer, int offset) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, got " + offset);
        }
        int field = spec.number();
        int length;
        int dataStart;
        if (spec.lengthKind() == LengthKind.FIXED) {
            length = spec.maxLength();
            dataStart = offset;
        } else {
            length = LengthPrefix.read(spec, buffer, offset);
            dataStart = offset + LengthPrefix.width(spec);
        }
        int wire = wireBytes(spec, length);
        requireBytes(field, buffer, dataStart, wire);
        FieldValue value;
        if (spec.type() == FieldType.B) {
            value = FieldValue.binary(spec.encoding() == Encoding.BINARY
                    ? Arrays.copyOfRange(buffer, dataStart, dataStart + wire)
                    : HexCodec.decode(field, buffer, dataStart, length));
        } else if (spec.encoding() == Encoding.BCD) {
            value = FieldValue.text(BcdCodec.decode(spec, buffer, dataStart, length));
        } else {
            String s = new String(buffer, dataStart, length, StandardCharsets.US_ASCII);
            TextRules.Violation bad = TextRules.check(spec, s);
            if (bad != null) {
                throw new IsoParseException(field, dataStart + bad.index(), bad.reason());
            }
            value = FieldValue.text(s);
        }
        return new Unpacked(value, dataStart + wire);
    }

    static void requireBytes(int field, byte[] buffer, int offset, int needed) {
        int left = Math.max(0, buffer.length - offset);
        if (left < needed) {
            throw new IsoParseException(field, offset, "needs " + needed + " bytes, only " + left + " left");
        }
    }

    private static void checkLength(FieldSpec spec, int length) {
        if (length > spec.maxLength()) {
            throw new IsoPackException(spec.number(), "length " + length + " exceeds max " + spec.maxLength());
        }
        if (spec.type() == FieldType.B && spec.lengthKind() == LengthKind.FIXED && length != spec.maxLength()) {
            throw new IsoPackException(spec.number(),
                    "length " + length + " does not match fixed length " + spec.maxLength());
        }
    }

    private static String pad(FieldSpec spec, String s) {
        int missing = spec.maxLength() - s.length();
        if (spec.lengthKind() != LengthKind.FIXED || missing == 0) {
            return s;
        }
        if (spec.type() == FieldType.XN) {
            throw new IsoPackException(spec.number(), "XN value must be exactly " + spec.maxLength()
                    + " characters including its sign, got " + s.length());
        }
        return switch (spec.padding()) {
            case NONE -> throw new IsoPackException(spec.number(),
                    "length " + s.length() + " does not match fixed length " + spec.maxLength()
                            + " and the spec has no padding");
            case LEFT_ZEROS -> "0".repeat(missing) + s;
            case RIGHT_SPACES -> {
                if (spec.encoding() == Encoding.BCD) {
                    throw new IsoPackException(spec.number(), "space padding cannot be encoded in BCD");
                }
                yield s + " ".repeat(missing);
            }
        };
    }

    private static int wireBytes(FieldSpec spec, int length) {
        if (spec.type() == FieldType.B) {
            return spec.encoding() == Encoding.BINARY ? length : length * 2;
        }
        return spec.encoding() == Encoding.BCD ? BcdCodec.byteCount(length) : length;
    }

    private static String kind(FieldValue value) {
        return value.getClass().getSimpleName();
    }
}
