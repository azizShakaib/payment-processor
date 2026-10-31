package dev.paysim.iso8583;

import java.util.Arrays;
import java.util.Optional;

/**
 * Field 70, network management information code: what an 0800 asks for.
 * Sign-on opens a session, sign-off closes it, and the echo test is the
 * heartbeat that proves the link is alive.
 */
public enum NetworkManagementCode {
    SIGN_ON("001"),
    SIGN_OFF("002"),
    ECHO_TEST("301");

    private final String code;

    NetworkManagementCode(String code) {
        this.code = code;
    }

    /** The three digits that go into field 70. */
    public String code() {
        return code;
    }

    public static Optional<NetworkManagementCode> fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }
}
