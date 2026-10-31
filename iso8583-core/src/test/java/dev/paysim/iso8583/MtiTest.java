package dev.paysim.iso8583;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MtiTest {

    @Test
    void decodesEveryClassDigit() {
        MessageClass[] expected = {null, MessageClass.AUTHORIZATION, MessageClass.FINANCIAL,
            MessageClass.FILE_ACTION, MessageClass.REVERSAL, MessageClass.RECONCILIATION,
            MessageClass.ADMINISTRATIVE, MessageClass.FEE_COLLECTION, MessageClass.NETWORK_MANAGEMENT};
        for (int digit = 1; digit <= 8; digit++) {
            Mti mti = new Mti("0" + digit + "00");
            assertThat(mti.messageClass()).isEqualTo(expected[digit]);
            assertThat(expected[digit].digit()).isEqualTo(digit);
        }
    }

    @Test
    void decodesEveryFunctionDigit() {
        MessageFunction[] expected = {MessageFunction.REQUEST, MessageFunction.REQUEST_RESPONSE,
            MessageFunction.ADVICE, MessageFunction.ADVICE_RESPONSE, MessageFunction.NOTIFICATION,
            MessageFunction.NOTIFICATION_ACK, MessageFunction.INSTRUCTION, MessageFunction.INSTRUCTION_ACK};
        for (int digit = 0; digit <= 7; digit++) {
            Mti mti = new Mti("01" + digit + "0");
            assertThat(mti.function()).isEqualTo(expected[digit]);
            assertThat(expected[digit].digit()).isEqualTo(digit);
            assertThat(mti.isRequest()).isEqualTo(digit % 2 == 0);
            assertThat(mti.isResponse()).isEqualTo(digit % 2 == 1);
        }
    }

    @Test
    void decodesEveryOriginDigit() {
        MessageOrigin[] expected = {MessageOrigin.ACQUIRER, MessageOrigin.ACQUIRER_REPEAT,
            MessageOrigin.ISSUER, MessageOrigin.ISSUER_REPEAT, MessageOrigin.OTHER, MessageOrigin.OTHER_REPEAT};
        for (int digit = 0; digit <= 5; digit++) {
            Mti mti = new Mti("010" + digit);
            assertThat(mti.origin()).isEqualTo(expected[digit]);
            assertThat(expected[digit].digit()).isEqualTo(digit);
            assertThat(mti.isRepeat()).isEqualTo(digit % 2 == 1);
        }
    }

    @ParameterizedTest
    @CsvSource({"0100,0110", "0101,0110", "0120,0130", "0200,0210", "0400,0410", "0420,0430", "0800,0810",
        "0103,0112", "0104,0114", "0105,0114", "0140,0150", "0160,0170"})
    void responseMtiMapping(String request, String response) {
        assertThat(new Mti(request).responseMti()).isEqualTo(new Mti(response));
    }

    @Test
    void responseOfAResponseIsRejected() {
        assertThatThrownBy(Mti.AUTH_RESPONSE::responseMti).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(Mti.NETWORK_RESPONSE::responseMti).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1100", "2100", "0900", "0090", "0108", "0109", "0106", "0190", "0180", "0000",
        "abc", "abcd", "01OO", "010", "01000", "", "0 00"})
    void rejectsInvalidCodes(String code) {
        assertThatThrownBy(() -> new Mti(code)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new Mti(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constants() {
        assertThat(Mti.AUTH_REQUEST.code()).isEqualTo("0100");
        assertThat(Mti.AUTH_RESPONSE.code()).isEqualTo("0110");
        assertThat(Mti.AUTH_ADVICE.code()).isEqualTo("0120");
        assertThat(Mti.FINANCIAL_REQUEST.code()).isEqualTo("0200");
        assertThat(Mti.FINANCIAL_RESPONSE.code()).isEqualTo("0210");
        assertThat(Mti.REVERSAL_REQUEST.code()).isEqualTo("0400");
        assertThat(Mti.REVERSAL_ADVICE.code()).isEqualTo("0420");
        assertThat(Mti.NETWORK_REQUEST.code()).isEqualTo("0800");
        assertThat(Mti.NETWORK_RESPONSE.code()).isEqualTo("0810");
    }

    @Test
    void ofBuildsFromParts() {
        assertThat(Mti.of(MessageClass.REVERSAL, MessageFunction.ADVICE, MessageOrigin.ACQUIRER))
                .isEqualTo(Mti.REVERSAL_ADVICE);
    }

    @Test
    void toStringIsTheCode() {
        assertThat(Mti.AUTH_REQUEST).hasToString("0100");
    }

    @Test
    void requestFlags() {
        assertThat(Mti.AUTH_REQUEST.isRequest()).isTrue();
        assertThat(Mti.AUTH_ADVICE.isRequest()).isTrue();
        assertThat(Mti.AUTH_RESPONSE.isRequest()).isFalse();
        assertThat(Mti.AUTH_RESPONSE.isResponse()).isTrue();
        assertThat(new Mti("0101").isRepeat()).isTrue();
        assertThat(Mti.AUTH_REQUEST.isRepeat()).isFalse();
    }
}
