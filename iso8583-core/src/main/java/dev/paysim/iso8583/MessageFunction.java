package dev.paysim.iso8583;

/**
 * Third MTI digit: the role in the conversation. Requests, advices,
 * notifications and instructions start an exchange; the others answer one.
 */
public enum MessageFunction {
    REQUEST(0, true),
    REQUEST_RESPONSE(1, false),
    ADVICE(2, true),
    ADVICE_RESPONSE(3, false),
    NOTIFICATION(4, true),
    NOTIFICATION_ACK(5, false),
    INSTRUCTION(6, true),
    INSTRUCTION_ACK(7, false);

    private final int digit;
    private final boolean request;

    MessageFunction(int digit, boolean request) {
        this.digit = digit;
        this.request = request;
    }

    public int digit() {
        return digit;
    }

    /** True for the functions that start an exchange and expect an answer. */
    public boolean isRequest() {
        return request;
    }

    /** The answering function: REQUEST to REQUEST_RESPONSE, ADVICE to ADVICE_RESPONSE, and so on. */
    MessageFunction response() {
        if (!request) {
            throw new IllegalStateException(this + " is already a response");
        }
        return values()[ordinal() + 1];
    }

    static MessageFunction fromDigit(char c) {
        for (MessageFunction value : values()) {
            if (value.digit == c - '0') {
                return value;
            }
        }
        throw new IllegalArgumentException("MTI message function digit is not defined (digit 3 of the MTI)");
    }
}
