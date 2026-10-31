package dev.paysim.iso8583.codec;

import dev.paysim.iso8583.IsoParseException;
import java.nio.charset.StandardCharsets;

/** Type B with ASCII encoding: two uppercase hex characters per byte. */
final class HexCodec {

    private static final byte[] DIGITS = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    private HexCodec() {
    }

    static byte[] encode(byte[] raw) {
        byte[] out = new byte[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            out[2 * i] = DIGITS[(raw[i] >>> 4) & 0x0F];
            out[2 * i + 1] = DIGITS[raw[i] & 0x0F];
        }
        return out;
    }

    /** Accepts upper or lower case; the error offset is the offending wire byte. */
    static byte[] decode(int field, byte[] buffer, int start, int byteCount) {
        byte[] out = new byte[byteCount];
        for (int i = 0; i < byteCount * 2; i++) {
            int c = buffer[start + i];
            int v = c < 0 ? -1 : Character.digit(c, 16);
            if (v < 0) {
                throw new IsoParseException(field, start + i, "invalid hex digit at index " + i);
            }
            out[i / 2] |= (byte) (i % 2 == 0 ? v << 4 : v);
        }
        return out;
    }
}
