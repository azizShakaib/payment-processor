package dev.paysim.iso8583;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NetworkManagementCodeTest {

    @Test
    void codes() {
        assertThat(NetworkManagementCode.SIGN_ON.code()).isEqualTo("001");
        assertThat(NetworkManagementCode.SIGN_OFF.code()).isEqualTo("002");
        assertThat(NetworkManagementCode.ECHO_TEST.code()).isEqualTo("301");
    }

    @Test
    void fromCodeRoundTrips() {
        for (NetworkManagementCode value : NetworkManagementCode.values()) {
            assertThat(NetworkManagementCode.fromCode(value.code())).contains(value);
        }
    }

    @Test
    void unknownCodesAreEmpty() {
        assertThat(NetworkManagementCode.fromCode("999")).isEmpty();
        assertThat(NetworkManagementCode.fromCode("")).isEmpty();
        assertThat(NetworkManagementCode.fromCode(null)).isEmpty();
    }
}
