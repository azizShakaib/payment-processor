package dev.paysim.iso8583.spec;

/** How a field's length travels on the wire: implied by the spec, or in a decimal prefix. */
public enum LengthKind {

    /** Length is fixed by the spec; no prefix on the wire. */
    FIXED(0, Integer.MAX_VALUE),
    /** Two-digit length prefix, so at most 99, e.g. field 2 PAN. */
    LLVAR(2, 99),
    /** Three-digit length prefix, so at most 999, e.g. field 55 ICC data. */
    LLLVAR(3, 999);

    private final int prefixDigits;
    private final int maxPossibleLength;

    LengthKind(int prefixDigits, int maxPossibleLength) {
        this.prefixDigits = prefixDigits;
        this.maxPossibleLength = maxPossibleLength;
    }

    /** Number of decimal digits in the length prefix (0 for fixed). */
    public int prefixDigits() {
        return prefixDigits;
    }

    /** Largest length the prefix can express. */
    public int maxPossibleLength() {
        return maxPossibleLength;
    }
}
