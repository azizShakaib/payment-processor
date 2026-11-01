package dev.paysim.link;

import dev.paysim.common.Money;
import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.Mti;
import dev.paysim.iso8583.spec.MessageSpec;
import dev.paysim.iso8583.spec.Specs;

import java.time.Clock;

/** Message builders shared by the link tests. The PAN is a well-known test number. */
final class TestMessages {

    static final MessageSpec SPEC = Specs.PAYSIM_ASCII_87;

    private TestMessages() {
    }

    static IsoMessage auth(int stan, long minorUnits) {
        return IsoMessage.builder(SPEC, Mti.AUTH_REQUEST)
                .pan("4111111111111111")
                .processingCode("000000")
                .amount(Money.of(minorUnits, "USD"))
                .transmissionDateTime(Clock.systemUTC())
                .stan(stan)
                .set(32, "123456")
                .terminalId("TERM0001")
                .build();
    }

    static IsoMessage networkRequest(int stan, String code) {
        return IsoMessage.builder(SPEC, Mti.NETWORK_REQUEST)
                .transmissionDateTime(Clock.systemUTC())
                .stan(stan)
                .set(70, code)
                .build();
    }
}
