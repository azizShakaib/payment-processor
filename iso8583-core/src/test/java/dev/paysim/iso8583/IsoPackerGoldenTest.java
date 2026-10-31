package dev.paysim.iso8583;

import static dev.paysim.iso8583.PackerSupport.ASCII;
import static dev.paysim.iso8583.PackerSupport.COMPACT;
import static org.assertj.core.api.Assertions.assertThat;

import dev.paysim.common.Hex;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exact wire bytes for known messages, plus the byte-stability of unpack then pack. */
class IsoPackerGoldenTest {

    /** The hand-built echo from Phase 0's EchoDemo. */
    private static final String ECHO_0800 =
            "0800" + "8220000000000000" + "0400000000000000" + "1015042100" + "000001" + "301";

    /** 0100 as ASCII text: MTI, bitmap, then each field in order. */
    private static final String AUTH_0100 = "0100" + "723C048020C09000"
            + "16" + "4111111111111111"       // 2 PAN, LLVAR
            + "000000"                        // 3
            + "000000001500"                  // 4
            + "1031143015"                    // 7
            + "000123"                        // 11
            + "103015"                        // 12
            + "1031"                          // 13
            + "2512"                          // 14
            + "051"                           // 22
            + "00"                            // 25
            + "24" + "4111111111111111=2512101" // 35, LLVAR
            + "TERM0001"                      // 41
            + "MERCHANT0000001"               // 42
            + "840"                           // 49
            + "0123456789ABCDEF";             // 52, hex text

    private static final String AUTH_0100_HEX = String.join("",
            "3031303037323343303438303230433039303030313634313131313131313131",
            "3131313131313030303030303030303030303030313530303130333131343330",
            "3135303030313233313033303135313033313235313230353130303234343131",
            "313131313131313131313131313D323531323130315445524D303030314D4552",
            "4348414E543030303030303138343030313233343536373839414243444546");

    private static final String RESPONSE_0110 = "0110" + "7238000006C08000"
            + "16" + "4111111111111111" + "000000" + "000000001500" + "1031143015" + "000123" + "103015"
            + "1031" + "A1B2C3" + "00" + "TERM0001" + "MERCHANT0000001" + "840";

    private static final String RESPONSE_0110_HEX = String.join("",
            "3031313037323338303030303036433038303030313634313131313131313131",
            "3131313131313030303030303030303030303030313530303130333131343330",
            "31353030303132333130333031353130333141314232433330305445524D3030",
            "30314D45524348414E5430303030303031383430");

    /** Compact 0100: BCD MTI, binary bitmap, BCD numerics, raw PIN block. */
    private static final String COMPACT_0100_HEX = String.join("",
            "0100",                              // MTI
            "723C048020C09000",                  // bitmap
            "16", "4111111111111111",            // 2
            "000000",                            // 3
            "000000001500",                      // 4
            "1031143015",                        // 7
            "000123",                            // 11
            "103015",                            // 12
            "1031",                              // 13
            "2512",                              // 14
            "0051",                              // 22 (odd digit count, zero nibble on the left)
            "00",                                // 25
            "24", "4111111111111111D2512101",    // 35 (separator '=' is nibble D)
            "5445524D30303031",                  // 41 "TERM0001"
            "4D45524348414E54" + "30303030303031", // 42 "MERCHANT0000001"
            "0840",                              // 49
            "0123456789ABCDEF");                 // 52

    private static final String COMPACT_0800_HEX = String.join("",
            "0800", "82200000000000000400000000000000", "1015042100", "000001", "0301");

    private static String hex(String ascii) {
        return Hex.encode(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    private static String text(byte[] wire) {
        return new String(wire, StandardCharsets.US_ASCII);
    }

    @Test
    void echoMatchesPhaseZeroHandBuiltMessage() {
        byte[] wire = IsoPacker.pack(PackerSupport.echo0800(ASCII));

        assertThat(text(wire)).isEqualTo(ECHO_0800);
        assertThat(wire).hasSize(55);
        assertThat(Hex.encode(wire)).isEqualTo(hex(ECHO_0800));
    }

    @Test
    void authorizationRequestIsExactlyThese159Bytes() {
        byte[] wire = IsoPacker.pack(PackerSupport.sample0100(ASCII).build());

        assertThat(text(wire)).isEqualTo(AUTH_0100);
        assertThat(Hex.encode(wire)).isEqualTo(AUTH_0100_HEX).isEqualTo(hex(AUTH_0100));
        assertThat(wire).hasSize(159);
    }

    @Test
    void authorizationResponseIsExactlyThese116Bytes() {
        byte[] wire = IsoPacker.pack(PackerSupport.sample0110(ASCII).build());

        assertThat(text(wire)).isEqualTo(RESPONSE_0110);
        assertThat(Hex.encode(wire)).isEqualTo(RESPONSE_0110_HEX).isEqualTo(hex(RESPONSE_0110));
        assertThat(wire).hasSize(116);
    }

    @Test
    void compactAuthorizationRequestIsShorterThanAscii() {
        byte[] compact = IsoPacker.pack(PackerSupport.sample0100(COMPACT).build());
        byte[] ascii = IsoPacker.pack(PackerSupport.sample0100(ASCII).build());

        assertThat(Hex.encode(compact)).isEqualTo(COMPACT_0100_HEX);
        assertThat(compact).hasSize(92);
        assertThat(compact.length).isLessThan(ascii.length);
    }

    @Test
    void compactEchoIsShorterThanAscii() {
        byte[] compact = IsoPacker.pack(PackerSupport.echo0800(COMPACT));

        assertThat(Hex.encode(compact)).isEqualTo(COMPACT_0800_HEX);
        assertThat(compact).hasSize(28);
        assertThat(compact.length).isLessThan(IsoPacker.pack(PackerSupport.echo0800(ASCII)).length);
    }

    @Test
    void unpackThenPackReproducesEveryGoldenMessage() {
        List<byte[]> goldens = List.of(
                ECHO_0800.getBytes(StandardCharsets.US_ASCII),
                AUTH_0100.getBytes(StandardCharsets.US_ASCII),
                RESPONSE_0110.getBytes(StandardCharsets.US_ASCII));
        for (byte[] wire : goldens) {
            assertThat(IsoPacker.pack(IsoPacker.unpack(ASCII, wire))).isEqualTo(wire);
        }
        byte[] compact0100 = Hex.decode(COMPACT_0100_HEX);
        byte[] compact0800 = Hex.decode(COMPACT_0800_HEX);
        assertThat(IsoPacker.pack(IsoPacker.unpack(COMPACT, compact0100))).isEqualTo(compact0100);
        assertThat(IsoPacker.pack(IsoPacker.unpack(COMPACT, compact0800))).isEqualTo(compact0800);
    }

    /**
     * Byte stability is the real guarantee. Object equality after a round trip
     * holds only when fixed fields were set at full length, because unpack
     * returns the padded wire value (by design since step 2). Messages made
     * with the typed setters (stan() pads to six digits) compare equal after
     * a round trip; a hand-set short value like "0" does not, although its
     * packed bytes are identical.
     */
    @Test
    void objectEqualityAfterRoundTripNeedsFullLengthFixedFields() {
        IsoMessage full = PackerSupport.sample0100(ASCII).build();
        assertThat(IsoPacker.unpack(ASCII, IsoPacker.pack(full))).isEqualTo(full);

        IsoMessage shortValue = IsoMessage.builder(ASCII, Mti.AUTH_REQUEST).set(3, "0").build();
        IsoMessage back = IsoPacker.unpack(ASCII, IsoPacker.pack(shortValue));
        assertThat(back).isNotEqualTo(shortValue);
        assertThat(back.text(3)).contains("000000");
        assertThat(IsoPacker.pack(back)).isEqualTo(IsoPacker.pack(shortValue));
    }

    @Test
    void unpackedFieldsReadBackThroughTheTypedAccessors() {
        IsoMessage message = IsoPacker.unpack(ASCII, AUTH_0100.getBytes(StandardCharsets.US_ASCII));

        assertThat(message.mti()).isEqualTo(Mti.AUTH_REQUEST);
        assertThat(message.pan()).contains("4111111111111111");
        assertThat(message.amount().orElseThrow().minorUnits()).isEqualTo(1500);
        assertThat(message.stan()).hasValue(123);
        assertThat(message.terminalId()).contains("TERM0001");
        assertThat(message.bytes(52).orElseThrow()).isEqualTo(PackerSupport.PIN_BLOCK);
    }

    @Test
    void sliceOverloadParsesOnlyTheSlice() {
        byte[] wire = ECHO_0800.getBytes(StandardCharsets.US_ASCII);
        byte[] framed = PackerSupport.framed(wire, 7, 9);

        assertThat(IsoPacker.unpack(ASCII, framed, 7, wire.length)).isEqualTo(PackerSupport.echo0800(ASCII));
    }
}
