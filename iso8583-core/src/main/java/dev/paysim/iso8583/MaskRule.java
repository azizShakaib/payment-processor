package dev.paysim.iso8583;

/**
 * How {@link IsoPrinter} shows one data element. The rule is chosen per field
 * number by {@link MaskingRules}, never by looking at the value, because a
 * value that "does not look sensitive" is exactly the one that leaks.
 */
public enum MaskRule {

    /** Shown as is: text in quotes (padding visible), bytes as hex. For fields that identify nothing secret. */
    CLEAR,

    /**
     * Card number: first 6 and last 4 digits via {@code Pan.maskLenient}.
     * Lenient because a logging path must not throw on a malformed PAN.
     */
    PAN,

    /**
     * Track 2: the PAN part masked, everything after the separator replaced by
     * stars (it holds expiry, service code and often discretionary data such as
     * a CVV equivalent). Sensitive authentication data.
     */
    TRACK2,

    /** Track 1: only the length. It carries the cardholder name as well as the PAN, so masking the PAN part is not enough. */
    TRACK1,

    /** PIN block: only the length. An encrypted PIN block is still sensitive authentication data. */
    PIN_BLOCK,

    /**
     * EMV chip data (field 55): only the length. The blob is a list of TLV
     * tags, and tags 5A (PAN) and 57 (track 2 equivalent) may be inside, so
     * even a truncated hex prefix can leak. The Phase 1 roadmap said "truncate
     * 55"; this deliberately overrides that.
     */
    ICC,

    /** Message authentication code: uppercase hex. A MAC is a checksum for integrity, not a secret, and seeing it helps debug MAC failures. */
    MAC
}
