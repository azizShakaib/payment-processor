package dev.paysim.iso8583;

/**
 * Second MTI digit: what kind of business the message is about. A switch
 * routes on this before looking at any field.
 */
public enum MessageClass {
    AUTHORIZATION(1),
    FINANCIAL(2),
    FILE_ACTION(3),
    REVERSAL(4),
    RECONCILIATION(5),
    ADMINISTRATIVE(6),
    FEE_COLLECTION(7),
    NETWORK_MANAGEMENT(8);

    private final int digit;

    MessageClass(int digit) {
        this.digit = digit;
    }

    public int digit() {
        return digit;
    }

    static MessageClass fromDigit(char c) {
        for (MessageClass value : values()) {
            if (value.digit == c - '0') {
                return value;
            }
        }
        throw new IllegalArgumentException("MTI message class digit is not defined (digit 2 of the MTI)");
    }
}
