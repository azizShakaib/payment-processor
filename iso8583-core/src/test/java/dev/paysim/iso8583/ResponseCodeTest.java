package dev.paysim.iso8583;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseCodeTest {

    private static final Map<String, ResponseCode> TABLE = Map.ofEntries(
            Map.entry("00", ResponseCode.APPROVED),
            Map.entry("01", ResponseCode.REFER_TO_ISSUER),
            Map.entry("03", ResponseCode.INVALID_MERCHANT),
            Map.entry("05", ResponseCode.DO_NOT_HONOR),
            Map.entry("12", ResponseCode.INVALID_TRANSACTION),
            Map.entry("13", ResponseCode.INVALID_AMOUNT),
            Map.entry("14", ResponseCode.INVALID_CARD_NUMBER),
            Map.entry("30", ResponseCode.FORMAT_ERROR),
            Map.entry("41", ResponseCode.LOST_CARD),
            Map.entry("43", ResponseCode.STOLEN_CARD),
            Map.entry("51", ResponseCode.INSUFFICIENT_FUNDS),
            Map.entry("54", ResponseCode.EXPIRED_CARD),
            Map.entry("55", ResponseCode.INCORRECT_PIN),
            Map.entry("57", ResponseCode.NOT_PERMITTED_TO_CARDHOLDER),
            Map.entry("61", ResponseCode.EXCEEDS_WITHDRAWAL_LIMIT),
            Map.entry("62", ResponseCode.RESTRICTED_CARD),
            Map.entry("65", ResponseCode.EXCEEDS_FREQUENCY_LIMIT),
            Map.entry("75", ResponseCode.PIN_TRIES_EXCEEDED),
            Map.entry("91", ResponseCode.ISSUER_UNAVAILABLE),
            Map.entry("94", ResponseCode.DUPLICATE_TRANSMISSION),
            Map.entry("96", ResponseCode.SYSTEM_MALFUNCTION));

    @Test
    void everyCodeMapsBothWays() {
        assertThat(TABLE).hasSize(ResponseCode.values().length);
        TABLE.forEach((code, expected) -> {
            assertThat(expected.code()).isEqualTo(code);
            assertThat(ResponseCode.fromCode(code)).contains(expected);
        });
    }

    @Test
    void onlyZeroZeroApproves() {
        assertThat(Arrays.stream(ResponseCode.values()).filter(ResponseCode::isApproved))
                .containsExactly(ResponseCode.APPROVED);
    }

    @Test
    void unknownOrMalformedCodesAreEmpty() {
        assertThat(ResponseCode.fromCode("99")).isEmpty();
        assertThat(ResponseCode.fromCode("0")).isEmpty();
        assertThat(ResponseCode.fromCode("")).isEmpty();
        assertThat(ResponseCode.fromCode(null)).isEmpty();
    }
}
