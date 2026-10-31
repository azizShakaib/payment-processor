package dev.paysim.iso8583.spec;

/**
 * How a value, MTI or bitmap is represented in bytes. For type B fields,
 * {@link #ASCII} means hex text (two characters per byte) and {@link #BINARY}
 * means the raw bytes.
 */
public enum Encoding {

    /** One byte per character. */
    ASCII,
    /** Packed decimal, two digits per byte. */
    BCD,
    /** Raw bytes. */
    BINARY
}
