package dev.paysim.iso8583.codec;

import static dev.paysim.iso8583.codec.CodecSupport.START;
import static dev.paysim.iso8583.codec.CodecSupport.packHex;
import static dev.paysim.iso8583.codec.CodecSupport.spec;
import static dev.paysim.iso8583.codec.CodecSupport.unpackAt;
import static org.assertj.core.api.Assertions.assertThat;

import dev.paysim.iso8583.FieldValue;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import org.junit.jupiter.api.Test;

class BcdFieldCodecTest {

    private static void assertRoundTrip(FieldSpec spec, String value, String hex) {
        assertThat(packHex(spec, FieldValue.text(value))).isEqualTo(hex);
        Unpacked result = unpackAt(spec, hex);
        assertThat(result.value()).isEqualTo(FieldValue.text(value));
        assertThat(result.nextOffset()).isEqualTo(START + hex.length() / 2);
    }

    @Test
    void evenDigitsPackTwoPerByte() {
        assertRoundTrip(spec(FieldType.N, LengthKind.FIXED, 6, Encoding.BCD), "123456", "123456");
    }

    @Test
    void oddDigitsGetALeadingZeroNibble() {
        assertRoundTrip(spec(FieldType.N, LengthKind.FIXED, 3, Encoding.BCD), "123", "0123");
    }

    @Test
    void shortFixedValueIsZeroPaddedThenPacked() {
        FieldSpec spec = spec(FieldType.N, LengthKind.FIXED, 6, Encoding.BCD);
        assertThat(packHex(spec, FieldValue.text("123"))).isEqualTo("000123");
        assertThat(unpackAt(spec, "000123").value()).isEqualTo(FieldValue.text("000123"));
    }

    @Test
    void llvarPrefixIsOneBcdByte() {
        assertRoundTrip(spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.BCD), "12345", "05012345");
    }

    @Test
    void lllvarPrefixIsTwoBcdBytes() {
        assertRoundTrip(spec(FieldType.N, LengthKind.LLLVAR, 999, Encoding.BCD), "123", "00030123");
        assertRoundTrip(spec(FieldType.N, LengthKind.LLLVAR, 999, Encoding.BCD), "1".repeat(123),
                "0123" + "01" + "11".repeat(61));
    }

    @Test
    void emptyVariableValueHasOnlyThePrefix() {
        assertRoundTrip(spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.BCD), "", "00");
    }

    @Test
    void trackTwoUsesSeparatorNibbleAndRightFPad() {
        assertRoundTrip(spec(FieldType.Z, LengthKind.LLVAR, 37, Encoding.BCD), "4111=25", "074111D25F");
    }

    @Test
    void trackTwoWithEvenLengthHasNoPad() {
        assertRoundTrip(spec(FieldType.Z, LengthKind.LLVAR, 37, Encoding.BCD), "4111=256", "084111D256");
    }

    @Test
    void trackTwoDSeparatorPacksTheSameAndComesBackAsEquals() {
        FieldSpec spec = spec(FieldType.Z, LengthKind.LLVAR, 37, Encoding.BCD);
        assertThat(packHex(spec, FieldValue.text("4111D25"))).isEqualTo("074111D25F");
    }

    @Test
    void binaryEncodedLengthPrefixIsAlsoBcd() {
        FieldSpec spec = spec(FieldType.B, LengthKind.LLLVAR, 255, Encoding.BINARY);
        assertThat(packHex(spec, FieldValue.binary(new byte[3]))).isEqualTo("0003000000");
    }
}
