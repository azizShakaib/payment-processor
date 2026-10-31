package dev.paysim.iso8583;

import java.util.Objects;

/**
 * Message Type Indicator: four digits that say what a message is. Digit 1 is
 * the ISO 8583 version (only 0, the 1987 edition, is supported), digit 2 the
 * {@link MessageClass}, digit 3 the {@link MessageFunction} and digit 4 the
 * {@link MessageOrigin}. 0100 is an authorization request from an acquirer,
 * 0110 its response. This is the in-memory value; how it is encoded on the
 * wire (ASCII or BCD) is the message spec's business.
 */
public record Mti(String code) {

    public static final Mti AUTH_REQUEST = new Mti("0100");
    public static final Mti AUTH_RESPONSE = new Mti("0110");
    public static final Mti AUTH_ADVICE = new Mti("0120");
    public static final Mti FINANCIAL_REQUEST = new Mti("0200");
    public static final Mti FINANCIAL_RESPONSE = new Mti("0210");
    public static final Mti REVERSAL_REQUEST = new Mti("0400");
    public static final Mti REVERSAL_ADVICE = new Mti("0420");
    public static final Mti NETWORK_REQUEST = new Mti("0800");
    public static final Mti NETWORK_RESPONSE = new Mti("0810");

    public Mti {
        Objects.requireNonNull(code, "MTI must not be null");
        if (code.length() != 4) {
            throw new IllegalArgumentException("MTI must be exactly 4 digits, got length " + code.length());
        }
        for (int i = 0; i < 4; i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("MTI contains a non-digit at index " + i);
            }
        }
        if (code.charAt(0) != '0') {
            throw new IllegalArgumentException(
                    "MTI version digit must be 0 (ISO 8583:1987), other editions are not supported");
        }
        MessageClass.fromDigit(code.charAt(1));
        MessageFunction.fromDigit(code.charAt(2));
        MessageOrigin.fromDigit(code.charAt(3));
    }

    public static Mti of(MessageClass messageClass, MessageFunction function, MessageOrigin origin) {
        Objects.requireNonNull(messageClass, "messageClass must not be null");
        Objects.requireNonNull(function, "function must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        return new Mti("0" + messageClass.digit() + function.digit() + origin.digit());
    }

    public MessageClass messageClass() {
        return MessageClass.fromDigit(code.charAt(1));
    }

    public MessageFunction function() {
        return MessageFunction.fromDigit(code.charAt(2));
    }

    public MessageOrigin origin() {
        return MessageOrigin.fromDigit(code.charAt(3));
    }

    /** True for request, advice, notification and instruction: the messages that start an exchange. */
    public boolean isRequest() {
        return function().isRequest();
    }

    public boolean isResponse() {
        return !isRequest();
    }

    /** True when the origin digit marks a retransmission (0101, 0201, ...). */
    public boolean isRepeat() {
        return origin().isRepeat();
    }

    /**
     * The MTI of the answer: 0100 and 0101 give 0110, 0120 gives 0130, 0200
     * gives 0210, 0400 gives 0410, 0420 gives 0430, 0800 gives 0810. A repeat
     * is answered like the original, so the repeat flag drops.
     *
     * @throws IllegalStateException if this MTI is already a response
     */
    public Mti responseMti() {
        if (isResponse()) {
            throw new IllegalStateException("MTI " + code + " is already a response");
        }
        return of(messageClass(), function().response(), origin().base());
    }

    @Override
    public String toString() {
        return code;
    }
}
