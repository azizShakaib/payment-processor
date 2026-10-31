package dev.paysim.iso8583.codec;

import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.Mti;
import dev.paysim.iso8583.spec.Encoding;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encodes and decodes the MTI at the start of a message. ASCII is four bytes
 * ("0100" is 30 31 30 30); BCD is two bytes (01 00). Parse errors are reported
 * against field 0 by convention (field 0 is the MTI, field 1 the bitmap), with
 * absolute offsets, and never contain the bytes themselves.
 */
public final class MtiCodec {

    /** By convention parse errors in the MTI are reported as field 0. */
    private static final int FIELD = 0;
    private static final int DIGITS = 4;

    private MtiCodec() {
    }

    /** A decoded MTI plus the absolute offset of the first byte after it. */
    public record UnpackedMti(Mti mti, int nextOffset) {
    }

    /** Bytes the MTI occupies on the wire. */
    public static int wireLength(Encoding encoding) {
        checkEncoding(encoding);
        return encoding == Encoding.ASCII ? DIGITS : DIGITS / 2;
    }

    /** Appends the MTI. Only ASCII and BCD are valid. */
    public static void pack(Mti mti, Encoding encoding, ByteArrayOutputStream out) {
        Objects.requireNonNull(mti, "mti must not be null");
        Objects.requireNonNull(out, "out must not be null");
        checkEncoding(encoding);
        String code = mti.code();
        if (encoding == Encoding.ASCII) {
            out.writeBytes(code.getBytes(StandardCharsets.US_ASCII));
        } else {
            for (int i = 0; i < DIGITS; i += 2) {
                out.write(((code.charAt(i) - '0') << 4) | (code.charAt(i + 1) - '0'));
            }
        }
    }

    /**
     * Reads the MTI at the absolute {@code offset} of {@code buffer}.
     *
     * @throws IsoParseException (field 0) if the buffer is too short, a digit
     *         is invalid, or {@link Mti} rejects the four digits
     */
    public static UnpackedMti unpack(Encoding encoding, byte[] buffer, int offset) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        int length = wireLength(encoding);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, got " + offset);
        }
        int left = Math.max(0, buffer.length - offset);
        if (left < length) {
            throw new IsoParseException(FIELD, offset, "truncated MTI, need " + length + " bytes, have " + left);
        }
        char[] digits = new char[DIGITS];
        if (encoding == Encoding.ASCII) {
            for (int i = 0; i < DIGITS; i++) {
                int c = buffer[offset + i];
                if (c < '0' || c > '9') {
                    throw new IsoParseException(FIELD, offset + i, "non-digit in MTI at index " + i);
                }
                digits[i] = (char) c;
            }
        } else {
            for (int i = 0; i < DIGITS; i++) {
                int b = buffer[offset + i / 2] & 0xFF;
                int nibble = i % 2 == 0 ? b >>> 4 : b & 0x0F;
                if (nibble > 9) {
                    throw new IsoParseException(FIELD, offset + i / 2, "invalid BCD nibble in MTI at index " + i);
                }
                digits[i] = (char) ('0' + nibble);
            }
        }
        try {
            return new UnpackedMti(new Mti(new String(digits)), offset + length);
        } catch (IllegalArgumentException e) {
            throw new IsoParseException(FIELD, offset, "MTI rejected: " + e.getMessage());
        }
    }

    private static void checkEncoding(Encoding encoding) {
        if (encoding != Encoding.ASCII && encoding != Encoding.BCD) {
            throw new IllegalArgumentException("MTI encoding must be ASCII or BCD, got " + encoding);
        }
    }
}
