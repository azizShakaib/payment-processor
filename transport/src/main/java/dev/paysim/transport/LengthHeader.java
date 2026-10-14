package dev.paysim.transport;

import java.nio.charset.StandardCharsets;

/**
 * How the length of each message is written in front of it on the wire.
 *
 * <p>TCP delivers a stream of bytes with no message boundaries. Every ISO 8583
 * link therefore agrees up front on a header that says "the next N bytes are
 * one message". Both styles below are common in production.
 */
public enum LengthHeader {

    /**
     * Two bytes, unsigned, big-endian (network byte order).
     * {@code 47} is written as {@code 0x00 0x2F}. Max payload 65,535 bytes.
     */
    BINARY_2(2, 0xFFFF) {
        @Override
        byte[] encode(int length) {
            checkRange(length);
            return new byte[] {(byte) (length >>> 8), (byte) length};
        }

        @Override
        int decode(byte[] header) {
            return ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        }
    },

    /**
     * Four ASCII digits, zero-padded. {@code 47} is written as the text
     * {@code "0047"}. Max payload 9,999 bytes. Easy to read in a hex dump.
     */
    ASCII_4(4, 9_999) {
        @Override
        byte[] encode(int length) {
            checkRange(length);
            return String.format("%04d", length).getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        int decode(byte[] header) {
            int value = 0;
            for (byte b : header) {
                if (b < '0' || b > '9') {
                    throw new FramingException(
                            "ASCII length header contains a non-digit byte: 0x%02X".formatted(b & 0xFF));
                }
                value = value * 10 + (b - '0');
            }
            return value;
        }
    };

    private final int size;
    private final int maxLength;

    LengthHeader(int size, int maxLength) {
        this.size = size;
        this.maxLength = maxLength;
    }

    /** Number of header bytes on the wire. */
    public int size() {
        return size;
    }

    /** Largest payload this header can describe. */
    public int maxLength() {
        return maxLength;
    }

    abstract byte[] encode(int length);

    abstract int decode(byte[] header);

    void checkRange(int length) {
        if (length < 0 || length > maxLength) {
            throw new FramingException(
                    "Payload length %d does not fit a %s header (max %d)".formatted(length, name(), maxLength));
        }
    }
}
