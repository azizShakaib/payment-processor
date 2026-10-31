package dev.paysim.iso8583.codec;

import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import java.nio.charset.StandardCharsets;

/**
 * LLVAR / LLLVAR length prefixes. ASCII fields carry the length as ASCII
 * digits; BCD and BINARY fields carry it as BCD (LLVAR one byte, LLLVAR two
 * bytes with a zero high nibble).
 */
final class LengthPrefix {

    private LengthPrefix() {
    }

    /** Bytes the prefix occupies on the wire. */
    static int width(FieldSpec spec) {
        int digits = spec.lengthKind().prefixDigits();
        return spec.encoding() == Encoding.ASCII ? digits : (digits + 1) / 2;
    }

    static byte[] encode(FieldSpec spec, int length) {
        int digits = spec.lengthKind().prefixDigits();
        String text = String.format("%0" + digits + "d", length);
        if (spec.encoding() == Encoding.ASCII) {
            return text.getBytes(StandardCharsets.US_ASCII);
        }
        byte[] out = new byte[width(spec)];
        int pad = digits % 2; // leading zero nibble when the digit count is odd
        for (int i = 0; i < digits; i++) {
            int pos = i + pad;
            int nibble = text.charAt(i) - '0';
            out[pos / 2] |= (byte) (pos % 2 == 0 ? nibble << 4 : nibble);
        }
        return out;
    }

    /** Reads and range-checks the prefix at {@code offset}; errors point at the prefix. */
    static int read(FieldSpec spec, byte[] buffer, int offset) {
        int width = width(spec);
        FieldCodec.requireBytes(spec.number(), buffer, offset, width);
        int digits = spec.lengthKind().prefixDigits();
        int length = 0;
        if (spec.encoding() == Encoding.ASCII) {
            for (int i = 0; i < digits; i++) {
                int c = buffer[offset + i];
                if (c < '0' || c > '9') {
                    throw new IsoParseException(spec.number(), offset + i,
                            "non-digit in length prefix at index " + i);
                }
                length = length * 10 + (c - '0');
            }
        } else {
            int pad = digits % 2;
            for (int p = 0; p < digits + pad; p++) {
                int b = buffer[offset + p / 2] & 0xFF;
                int nibble = p % 2 == 0 ? b >>> 4 : b & 0x0F;
                if (p < pad ? nibble != 0 : nibble > 9) {
                    throw new IsoParseException(spec.number(), offset + p / 2,
                            "invalid BCD nibble in length prefix");
                }
                length = length * 10 + nibble;
            }
        }
        if (length > spec.maxLength()) {
            throw new IsoParseException(spec.number(), offset,
                    "length " + length + " exceeds max " + spec.maxLength());
        }
        return length;
    }
}
