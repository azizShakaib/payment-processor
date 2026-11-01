package dev.paysim.link;

import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.Mti;
import dev.paysim.iso8583.ResponseCode;
import org.junit.jupiter.api.Test;

import static dev.paysim.link.TestMessages.SPEC;
import static dev.paysim.link.TestMessages.auth;
import static org.assertj.core.api.Assertions.assertThat;

class MatchKeyTest {

    @Test
    void requestAndItsResponseHaveEqualKeys() {
        IsoMessage request = auth(42, 1250);
        IsoMessage response = request.responseBuilder().responseCode(ResponseCode.APPROVED).build();
        assertThat(MatchKey.of(response)).isEqualTo(MatchKey.of(request));
        assertThat(MatchKey.of(response).hashCode()).isEqualTo(MatchKey.of(request).hashCode());
    }

    @Test
    void differentStanDiffers() {
        assertThat(MatchKey.of(auth(42, 1250))).isNotEqualTo(MatchKey.of(auth(43, 1250)));
    }

    @Test
    void differentTransmissionTimeDiffers() {
        IsoMessage a = auth(42, 1250);
        IsoMessage b = a.toBuilder().set(7, "0101000000").build();
        assertThat(MatchKey.of(a)).isNotEqualTo(MatchKey.of(b));
    }

    @Test
    void differentTerminalDiffers() {
        IsoMessage a = auth(42, 1250);
        IsoMessage b = a.toBuilder().terminalId("TERM0002").build();
        assertThat(MatchKey.of(a)).isNotEqualTo(MatchKey.of(b));
    }

    @Test
    void differentAcquirerDiffers() {
        IsoMessage a = auth(42, 1250);
        IsoMessage b = a.toBuilder().set(32, "654321").build();
        assertThat(MatchKey.of(a)).isNotEqualTo(MatchKey.of(b));
    }

    @Test
    void sameStanInADifferentMessageClassDiffers() {
        IsoMessage a = auth(42, 1250);
        IsoMessage b = a.toBuilder().mti(Mti.FINANCIAL_REQUEST).build();
        assertThat(MatchKey.of(a)).isNotEqualTo(MatchKey.of(b));
    }

    @Test
    void missingFieldsBecomeEmptyStrings() {
        IsoMessage m = IsoMessage.builder(SPEC, Mti.NETWORK_REQUEST).stan(7).build();
        MatchKey key = MatchKey.of(m);
        assertThat(key.stan()).isEqualTo("000007");
        assertThat(key.dateTime()).isEmpty();
        assertThat(key.acquirer()).isEmpty();
        assertThat(key.terminal()).isEmpty();
    }

    @Test
    void toStringShowsClassAndStanOnly() {
        assertThat(MatchKey.of(auth(42, 1250)).toString())
                .isEqualTo("MatchKey[AUTHORIZATION stan=000042]");
    }
}
