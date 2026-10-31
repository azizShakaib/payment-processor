package dev.paysim.iso8583;

/**
 * Fourth MTI digit: who sent the message, and whether it is a repeat (a
 * retransmission after no answer arrived).
 */
public enum MessageOrigin {
    ACQUIRER(0, false),
    ACQUIRER_REPEAT(1, true),
    ISSUER(2, false),
    ISSUER_REPEAT(3, true),
    OTHER(4, false),
    OTHER_REPEAT(5, true);

    private final int digit;
    private final boolean repeat;

    MessageOrigin(int digit, boolean repeat) {
        this.digit = digit;
        this.repeat = repeat;
    }

    public int digit() {
        return digit;
    }

    public boolean isRepeat() {
        return repeat;
    }

    /** The origin without the repeat flag; a response is never itself marked as a repeat. */
    MessageOrigin base() {
        return repeat ? values()[ordinal() - 1] : this;
    }

    static MessageOrigin fromDigit(char c) {
        for (MessageOrigin value : values()) {
            if (value.digit == c - '0') {
                return value;
            }
        }
        throw new IllegalArgumentException("MTI message origin digit is not defined (digit 4 of the MTI)");
    }
}
