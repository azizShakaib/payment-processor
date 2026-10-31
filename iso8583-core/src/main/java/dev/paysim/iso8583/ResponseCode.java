package dev.paysim.iso8583;

import java.util.Arrays;
import java.util.Optional;

/**
 * Field 39 values this simulator knows. The issuer picks the code; the
 * terminal shows the cardholder a message derived from it, and the acquirer
 * decides from it whether a retry makes sense (91 may succeed later, 51 will
 * not). Real networks define many more codes, and each adds its own, so an
 * unknown code is normal and {@link #fromCode} returns empty for it.
 */
public enum ResponseCode {
    APPROVED("00"),
    REFER_TO_ISSUER("01"),
    INVALID_MERCHANT("03"),
    DO_NOT_HONOR("05"),
    INVALID_TRANSACTION("12"),
    INVALID_AMOUNT("13"),
    INVALID_CARD_NUMBER("14"),
    FORMAT_ERROR("30"),
    LOST_CARD("41"),
    STOLEN_CARD("43"),
    INSUFFICIENT_FUNDS("51"),
    EXPIRED_CARD("54"),
    INCORRECT_PIN("55"),
    NOT_PERMITTED_TO_CARDHOLDER("57"),
    EXCEEDS_WITHDRAWAL_LIMIT("61"),
    RESTRICTED_CARD("62"),
    EXCEEDS_FREQUENCY_LIMIT("65"),
    PIN_TRIES_EXCEEDED("75"),
    ISSUER_UNAVAILABLE("91"),
    DUPLICATE_TRANSMISSION("94"),
    SYSTEM_MALFUNCTION("96");

    private final String code;

    ResponseCode(String code) {
        this.code = code;
    }

    /** The two characters that go into field 39. */
    public String code() {
        return code;
    }

    /** Only 00 approves; every other code, known or not, is a decline or an error. */
    public boolean isApproved() {
        return this == APPROVED;
    }

    public static Optional<ResponseCode> fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }
}
