package dev.paysim.iso8583.codec;

import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;

/**
 * Packed decimal for types N and Z, two digits per byte, high nibble first.
 * N with an odd digit count gets a zero nibble on the left. Z (track 2) maps
 * both '=' and 'D' to nibble 0xD and pads an odd count with 0xF on the right,
 * as card networks do; decoding always yields '=', so a 'D' separator does not
 * survive a round trip.
 */
final class BcdCodec {

    private static final int SEPARATOR = 0xD;
    private static final int FILLER = 0xF;

    private BcdCodec() {
    }

    static int byteCount(int digits) {
        return (digits + 1) / 2;
    }

    /** {@code digits} must already have passed the type check. */
    static byte[] encode(FieldSpec spec, String digits) {
        boolean track = spec.type() == FieldType.Z;
        int n = digits.length();
        int pad = n % 2;
        byte[] out = new byte[byteCount(n)];
        for (int i = 0; i < n; i++) {
            char c = digits.charAt(i);
            int nibble = c == '=' || c == 'D' ? SEPARATOR : c - '0';
            int pos = track ? i : i + pad;
            out[pos / 2] |= (byte) (pos % 2 == 0 ? nibble << 4 : nibble);
        }
        if (track && pad == 1) {
            out[out.length - 1] |= FILLER;
        }
        return out;
    }

    static String decode(FieldSpec spec, byte[] buffer, int start, int digits) {
        boolean track = spec.type() == FieldType.Z;
        int pad = digits % 2;
        StringBuilder out = new StringBuilder(digits);
        int nibbles = digits + pad;
        for (int p = 0; p < nibbles; p++) {
            int b = buffer[start + p / 2] & 0xFF;
            int nibble = p % 2 == 0 ? b >>> 4 : b & 0x0F;
            int offset = start + p / 2;
            if (!track && p < pad) {
                if (nibble != 0) {
                    throw new IsoParseException(spec.number(), offset, "non-zero pad nibble");
                }
                continue;
            }
            if (track && p == digits) { // right pad of an odd-length Z
                if (nibble != FILLER) {
                    throw new IsoParseException(spec.number(), offset, "missing 0xF pad nibble");
                }
                continue;
            }
            int index = track ? p : p - pad;
            if (nibble <= 9) {
                out.append((char) ('0' + nibble));
            } else if (track && nibble == SEPARATOR) {
                out.append('=');
            } else {
                throw new IsoParseException(spec.number(), offset,
                        "invalid BCD nibble at index " + index + " for type " + spec.type());
            }
        }
        return out.toString();
    }
}
