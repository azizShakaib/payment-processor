package dev.paysim.iso8583;

import static dev.paysim.iso8583.PackerSupport.ASCII;
import static org.assertj.core.api.Assertions.assertThat;

import dev.paysim.common.Hex;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import dev.paysim.iso8583.spec.MessageSpec;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class IsoPrinterTest {

    private static final String EXPECTED_0100 = String.join("\n",
            "MTI    0100  AUTHORIZATION REQUEST from ACQUIRER",
            "BITMAP 723C048020C09000",
            "[002]  Primary account number ............... 411111******1111",
            "[003]  Processing code ...................... \"000000\"",
            "[004]  Amount, transaction .................. \"000000001500\"",
            "[007]  Transmission date and time ........... \"1031143015\"",
            "[011]  System trace audit number ............ \"000123\"",
            "[012]  Time, local transaction .............. \"103015\"",
            "[013]  Date, local transaction .............. \"1031\"",
            "[014]  Date, expiration ..................... \"2512\"",
            "[022]  POS entry mode ....................... \"051\"",
            "[025]  POS condition code ................... \"00\"",
            "[035]  Track 2 data ......................... 411111******1111=******* (24 chars)",
            "[041]  Card acceptor terminal ID ............ \"TERM0001\"",
            "[042]  Card acceptor ID code ................ \"MERCHANT0000001\"",
            "[049]  Currency code, transaction ........... \"840\"",
            "[052]  PIN data ............................. [8 bytes, PIN block hidden]");

    private static final String EXPECTED_0800 = String.join("\n",
            "MTI    0800  NETWORK MANAGEMENT REQUEST from ACQUIRER",
            "BITMAP 8220000000000000 0400000000000000",
            "[007]  Transmission date and time ........... \"1015042100\"",
            "[011]  System trace audit number ............ \"000001\"",
            "[070]  Network management information code .. \"301\"");

    private static final String EXPECTED_0110 = String.join("\n",
            "MTI    0110  AUTHORIZATION REQUEST RESPONSE from ACQUIRER",
            "BITMAP 7238000006C08000",
            "[002]  Primary account number ............... 411111******1111",
            "[003]  Processing code ...................... \"000000\"",
            "[004]  Amount, transaction .................. \"000000001500\"",
            "[007]  Transmission date and time ........... \"1031143015\"",
            "[011]  System trace audit number ............ \"000123\"",
            "[012]  Time, local transaction .............. \"103015\"",
            "[013]  Date, local transaction .............. \"1031\"",
            "[038]  Authorization ID response ............ \"A1B2C3\"",
            "[039]  Response code ........................ \"00\"",
            "[041]  Card acceptor terminal ID ............ \"TERM0001\"",
            "[042]  Card acceptor ID code ................ \"MERCHANT0000001\"",
            "[049]  Currency code, transaction ........... \"840\"");

    private static IsoMessage sample0100() {
        return PackerSupport.sample0100(ASCII).build();
    }

    private static MessageSpec specWithField2As(FieldType type) {
        return MessageSpec.builder("custom")
                .field(2, "Primary account number", type, LengthKind.LLVAR, 19, Encoding.ASCII)
                .field(11, "System trace audit number", FieldType.N, LengthKind.FIXED, 6, Encoding.ASCII)
                .build();
    }

    @Test
    void printsTheAuthorizationRequest() {
        assertThat(IsoPrinter.format(sample0100())).isEqualTo(EXPECTED_0100);
    }

    @Test
    void printsTheEchoWithItsSecondaryBitmap() {
        assertThat(IsoPrinter.format(PackerSupport.echo0800(ASCII))).isEqualTo(EXPECTED_0800);
    }

    @Test
    void printsTheResponse() {
        assertThat(IsoPrinter.format(PackerSupport.sample0110(ASCII).build())).isEqualTo(EXPECTED_0110);
    }

    @Test
    void labelColumnFitsTheLongestFieldNameInTheDefaultSpec() {
        int longest = ASCII.fields().stream().mapToInt(f -> f.name().length()).max().orElseThrow();
        assertThat(longest).isEqualTo(35);
        String line = IsoPrinter.format(PackerSupport.echo0800(ASCII)).lines()
                .filter(l -> l.startsWith("[070]")).findFirst().orElseThrow();
        assertThat(line).contains("code .. \"301\"");
    }

    @Test
    void describesRepeatsAndOtherOrigins() {
        IsoMessage repeat = IsoMessage.builder(ASCII, new Mti("0101")).stan(7).build();
        assertThat(IsoPrinter.format(repeat)).startsWith("MTI    0101  AUTHORIZATION REQUEST from ACQUIRER (repeat)\n");
        IsoMessage advice = IsoMessage.builder(ASCII, new Mti("0422")).stan(7).build();
        assertThat(IsoPrinter.format(advice)).startsWith("MTI    0422  REVERSAL ADVICE from ISSUER\n");
    }

    @Test
    void outputLeaksNoSensitiveValue() {
        String out = IsoPrinter.format(sample0100());
        assertThat(out).contains("411111******1111");
        assertThat(out).doesNotContain("4111111111111111");
        // the PAN's middle digits, the track's tail and the PIN block, in every form they could appear
        assertThat(out).doesNotContain("4111111111").doesNotContain("1111111111");
        assertThat(out).doesNotContain("=2512101").doesNotContain("2512101");
        assertThat(out).doesNotContain("0123456789ABCDEF").doesNotContain("0123456789abcdef");
        assertThat(out).doesNotContain("01 23 45");
        // A full PAN is at least 12 digits in a row; the longest legitimate run is the 12-digit amount,
        // so any run of 13 or more digits would be a card number or a track.
        assertThat(Pattern.compile("\\d{13,}").matcher(out).find()).isFalse();
    }

    @Test
    void responseOutputLeaksNoPan() {
        String out = IsoPrinter.format(PackerSupport.sample0110(ASCII).build());
        assertThat(out).doesNotContain("4111111111111111").doesNotContain("4111111111");
        assertThat(Pattern.compile("\\d{13,}").matcher(out).find()).isFalse();
    }

    @Test
    void clearTextShowsPaddingInsideTheQuotes() {
        IsoMessage message = IsoMessage.builder(ASCII, Mti.AUTH_REQUEST).set(41, "T1      ").build();
        assertThat(IsoPrinter.format(message)).contains("\"T1      \"");
    }

    @Test
    void malformedPanInAnAnsFieldIsReducedToItsLength() {
        MessageSpec spec = specWithField2As(FieldType.ANS);
        IsoMessage message = IsoMessage.builder(spec, Mti.AUTH_REQUEST).set(2, "4111x11111111111").build();
        String out = IsoPrinter.format(message);
        assertThat(out).endsWith("[16 chars]").doesNotContain("4111").doesNotContain("x");
    }

    @Test
    void track2WithDSeparatorIsMaskedLikeEquals() {
        IsoMessage message = IsoMessage.builder(ASCII, Mti.AUTH_REQUEST)
                .set(35, "4111111111111111D2512101").build();
        assertThat(IsoPrinter.format(message)).endsWith("411111******1111=******* (24 chars)");
    }

    @Test
    void track2WithoutSeparatorShowsOnlyTheLength() {
        IsoMessage message = IsoMessage.builder(ASCII, Mti.AUTH_REQUEST).set(35, "41111111111111112512").build();
        assertThat(IsoPrinter.format(message)).endsWith("[20 chars]").doesNotContain("4111");
    }

    @Test
    void track1ShowsOnlyTheLength() {
        String track1 = "B4111111111111111^DOE/JOHN^2512101";
        IsoMessage message = IsoMessage.builder(ASCII, Mti.AUTH_REQUEST).set(45, track1).build();
        String out = IsoPrinter.format(message);
        assertThat(out).endsWith("[" + track1.length() + " chars, track 1 hidden]")
                .doesNotContain("DOE").doesNotContain("4111");
    }

    @Test
    void iccDataShowsOnlyTheLength() {
        byte[] icc = Hex.decode("5A084111111111111111" + "5F2403251231");
        IsoMessage message = IsoMessage.builder(ASCII, Mti.AUTH_REQUEST).stan(1).set(55, icc).build();
        String out = IsoPrinter.format(message);
        assertThat(out).endsWith("[16 bytes, ICC data hidden]");
        assertThat(out).doesNotContain("5A08").doesNotContain("4111").doesNotContain("5F24");
    }

    @Test
    void macIsShownAsHexByDefault() {
        byte[] mac = Hex.decode("0011223344556677");
        IsoMessage message = IsoMessage.builder(ASCII, Mti.AUTH_REQUEST).set(64, mac).build();
        assertThat(IsoPrinter.format(message)).endsWith("0011223344556677");
    }

    @Test
    void customRulesChangeTheOutput() {
        byte[] mac = Hex.decode("0011223344556677");
        IsoMessage message = IsoMessage.builder(ASCII, Mti.AUTH_REQUEST).set(14, "2512").set(64, mac).build();
        MaskingRules strict = MaskingRules.ISO87_DEFAULT.with(14, MaskRule.PAN).with(64, MaskRule.PIN_BLOCK);
        String out = IsoPrinter.format(message, strict);
        assertThat(out).contains("Date, expiration ..................... [4 chars]");
        assertThat(out).endsWith("[8 bytes, PIN block hidden]");
        assertThat(out).doesNotContain("2512").doesNotContain("0011223344556677");
        // the default rules are untouched
        assertThat(IsoPrinter.format(message)).contains("\"2512\"").contains("0011223344556677");
    }

    @Test
    void safeDumpOfAValidFrameEqualsFormat() {
        IsoMessage message = sample0100();
        assertThat(IsoPrinter.safeDump(ASCII, IsoPacker.pack(message))).isEqualTo(IsoPrinter.format(message));
    }

    @Test
    void safeDumpOfATruncatedFrameReportsSizeAndReasonOnly() {
        byte[] frame = IsoPacker.pack(sample0100());
        byte[] truncated = Arrays.copyOf(frame, 30);
        String out = IsoPrinter.safeDump(ASCII, truncated);
        assertThat(out).startsWith("unparseable frame: 30 bytes; ");
        // not the PAN digits that fall inside the first 30 bytes, nor any hex of the frame
        assertThat(out).doesNotContain("4111").doesNotContain("723C");
        assertThat(out).doesNotContain(Hex.encode(truncated).substring(0, 16));
        assertThat(out).doesNotContain(Hex.encode(Arrays.copyOfRange(truncated, 20, 30)));
        assertThat(out).doesNotContainPattern("[0-9A-F]{16}");
    }

    @Test
    void safeDumpOfGarbageAndNull() {
        byte[] garbage = {(byte) 0xFF, 0x00, 0x13, 0x37};
        assertThat(IsoPrinter.safeDump(ASCII, garbage)).startsWith("unparseable frame: 4 bytes; ");
        assertThat(IsoPrinter.safeDump(ASCII, new byte[0])).startsWith("unparseable frame: 0 bytes; ");
        assertThat(IsoPrinter.safeDump(ASCII, null)).isEqualTo("unparseable frame: null");
    }
}
