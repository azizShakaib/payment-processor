package dev.paysim.iso8583.codec;

import dev.paysim.iso8583.Bitmap;
import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.spec.Encoding;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Encodes and decodes the bitmap in front of the data elements. ASCII means
 * hex text (16 characters, 32 with a secondary bitmap); BINARY means the raw
 * 8 bytes (16 with a secondary). Parse errors are reported against field 1 by
 * convention, with absolute offsets.
 */
public final class BitmapCodec {

    /** By convention parse errors in the bitmap are reported as field 1. */
    private static final int FIELD = 1;
    private static final int WORD_BYTES = 8;

    private BitmapCodec() {
    }

    /**
     * Appends the primary bitmap, and the secondary when any of fields 65..128
     * is present. Only ASCII and BINARY are valid. Nothing is written if the
     * encoding is rejected.
     */
    public static void pack(Bitmap bitmap, Encoding encoding, ByteArrayOutputStream out) {
        Objects.requireNonNull(bitmap, "bitmap must not be null");
        Objects.requireNonNull(encoding, "encoding must not be null");
        Objects.requireNonNull(out, "out must not be null");
        checkEncoding(encoding);
        byte[] raw = new byte[bitmap.hasSecondary() ? 2 * WORD_BYTES : WORD_BYTES];
        putWord(raw, 0, bitmap.primary());
        if (bitmap.hasSecondary()) {
            putWord(raw, WORD_BYTES, bitmap.secondary());
        }
        out.writeBytes(encoding == Encoding.ASCII ? HexCodec.encode(raw) : raw);
    }

    /**
     * Reads the bitmap starting at the absolute {@code offset} of {@code buffer}.
     * A flagged secondary bitmap is read too. Hex digits may be upper or lower
     * case. A secondary bitmap that bit 1 announces but that has no bits set
     * is rejected. That is deliberate strictness: such a bitmap could not be
     * re-packed to the same bytes, and it usually signals a peer bug. Some
     * real hosts tolerate and even send these; a lenient dialect could relax
     * this later. Truncation is reported at the offset where the short word
     * starts.
     */
    public static UnpackedBitmap unpack(Encoding encoding, byte[] buffer, int offset) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        return unpack(encoding, buffer, offset, buffer.length);
    }

    /**
     * Like {@link #unpack(Encoding, byte[], int)} but reads only below the
     * exclusive {@code limit}; bytes at or past it are never touched.
     *
     * @throws IllegalArgumentException if {@code offset} is negative or
     *         {@code limit} lies beyond the buffer
     */
    public static UnpackedBitmap unpack(Encoding encoding, byte[] buffer, int offset, int limit) {
        Objects.requireNonNull(encoding, "encoding must not be null");
        Objects.requireNonNull(buffer, "buffer must not be null");
        checkEncoding(encoding);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, got " + offset);
        }
        if (limit > buffer.length) {
            throw new IllegalArgumentException("limit " + limit + " is beyond a buffer of " + buffer.length + " bytes");
        }
        long primary = readWord(encoding, buffer, offset, limit, "primary");
        int next = offset + wireLength(encoding);
        long secondary = 0L;
        if (primary < 0) { // bit 1, the most significant bit, is the sign bit
            secondary = readWord(encoding, buffer, next, limit, "secondary");
            if (secondary == 0L) {
                throw new IsoParseException(FIELD, next, "secondary bitmap flagged but empty");
            }
            next += wireLength(encoding);
        }
        return new UnpackedBitmap(Bitmap.fromWords(primary, secondary), next);
    }

    private static long readWord(Encoding encoding, byte[] buffer, int start, int limit, String which) {
        int length = wireLength(encoding);
        if (start > limit || limit - start < length) {
            throw new IsoParseException(FIELD, start, "truncated " + which + " bitmap, need " + length
                    + " bytes, have " + Math.max(0, limit - start));
        }
        byte[] raw = encoding == Encoding.ASCII
                ? HexCodec.decode(FIELD, buffer, start, WORD_BYTES)
                : Arrays.copyOfRange(buffer, start, start + WORD_BYTES);
        long word = 0L;
        for (byte b : raw) {
            word = (word << 8) | (b & 0xFF);
        }
        return word;
    }

    private static int wireLength(Encoding encoding) {
        return encoding == Encoding.ASCII ? 2 * WORD_BYTES : WORD_BYTES;
    }

    private static void putWord(byte[] target, int at, long word) {
        for (int i = 0; i < WORD_BYTES; i++) {
            target[at + i] = (byte) (word >>> (56 - 8 * i));
        }
    }

    private static void checkEncoding(Encoding encoding) {
        if (encoding != Encoding.ASCII && encoding != Encoding.BINARY) {
            throw new IllegalArgumentException(
                    "bitmap encoding must be ASCII or BINARY, got " + encoding);
        }
    }
}
