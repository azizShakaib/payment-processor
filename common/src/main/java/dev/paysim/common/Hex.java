package dev.paysim.common;

import java.util.HexFormat;

/**
 * Hex helpers for logging raw frames. Payment engineers read wire bytes in hex
 * all day, so every module logs frames the same way.
 */
public final class Hex {

    private static final HexFormat UPPER = HexFormat.of().withUpperCase();

    private Hex() {
    }

    /** {@code [0x00, 0x2F]} becomes {@code "002F"}. */
    public static String encode(byte[] bytes) {
        return UPPER.formatHex(bytes);
    }

    /** {@code "002F"} becomes {@code [0x00, 0x2F]}. Case-insensitive. */
    public static byte[] decode(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    /**
     * Classic hex dump: offset, 16 bytes in hex, then printable ASCII.
     * <pre>
     * 0000  30 38 30 30 38 32 32 30  ...  0800822000000000
     * </pre>
     */
    public static String dump(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < bytes.length; offset += 16) {
            int end = Math.min(offset + 16, bytes.length);
            out.append(String.format("%04X  ", offset));
            for (int i = offset; i < offset + 16; i++) {
                if (i < end) {
                    out.append(String.format("%02X ", bytes[i]));
                } else {
                    out.append("   ");
                }
                if (i == offset + 7) {
                    out.append(' ');
                }
            }
            out.append(' ');
            for (int i = offset; i < end; i++) {
                int b = bytes[i] & 0xFF;
                out.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
            }
            out.append(System.lineSeparator());
        }
        return out.toString();
    }
}
