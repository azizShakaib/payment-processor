package dev.paysim.iso8583.codec;

import static dev.paysim.iso8583.codec.CodecSupport.START;
import static dev.paysim.iso8583.codec.CodecSupport.hexOf;
import static dev.paysim.iso8583.codec.CodecSupport.pack;
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

class AsciiFieldCodecTest {

    private static final String PAN = "4111111111111111";

    @Test
    void numericFixedIsZeroPaddedOnTheLeft() {
        FieldSpec spec = spec(FieldType.N, LengthKind.FIXED, 6, Encoding.ASCII);
        assertThat(packHex(spec, FieldValue.text("123"))).isEqualTo(hexOf("000123"));
    }

    @Test
    void unpackKeepsPaddingSoNothingIsLost() {
        FieldSpec spec = spec(FieldType.N, LengthKind.FIXED, 6, Encoding.ASCII);
        Unpacked result = unpackAt(spec, hexOf("000123"));
        assertThat(result.value()).isEqualTo(FieldValue.text("000123"));
        assertThat(result.nextOffset()).isEqualTo(START + 6);
    }

    @Test
    void alphanumericSpecialFixedIsSpacePaddedOnTheRight() {
        FieldSpec spec = spec(FieldType.ANS, LengthKind.FIXED, 8, Encoding.ASCII);
        assertThat(packHex(spec, FieldValue.text("T1"))).isEqualTo("5431" + "20".repeat(6));
        assertThat(unpackAt(spec, "5431" + "20".repeat(6)).value()).isEqualTo(FieldValue.text("T1      "));
    }

    @Test
    void spacePaddedAlphanumericRoundTripsEvenThoughTypeAnForbidsSpaces() {
        FieldSpec spec = spec(FieldType.AN, LengthKind.FIXED, 8, Encoding.ASCII);
        byte[] wire = pack(spec, FieldValue.text("T1"));
        Unpacked result = unpackAt(spec, dev.paysim.common.Hex.encode(wire));
        assertThat(result.value()).isEqualTo(FieldValue.text("T1      "));
        assertThat(pack(spec, result.value())).isEqualTo(wire);
    }

    @Test
    void panLlvarCarriesTwoDigitAsciiPrefix() {
        FieldSpec spec = spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.ASCII);
        assertThat(packHex(spec, FieldValue.text(PAN))).isEqualTo(hexOf("16" + PAN));
        Unpacked result = unpackAt(spec, hexOf("16" + PAN));
        assertThat(result.value()).isEqualTo(FieldValue.text(PAN));
        assertThat(result.nextOffset()).isEqualTo(START + 18);
    }

    @Test
    void lllvarCarriesThreeDigitAsciiPrefix() {
        FieldSpec spec = spec(FieldType.AN, LengthKind.LLLVAR, 120, Encoding.ASCII);
        assertThat(packHex(spec, FieldValue.text("AB"))).isEqualTo(hexOf("002AB"));
        Unpacked result = unpackAt(spec, hexOf("002AB"));
        assertThat(result.value()).isEqualTo(FieldValue.text("AB"));
        assertThat(result.nextOffset()).isEqualTo(START + 5);
    }

    @Test
    void emptyVariableValueIsAllowed() {
        FieldSpec spec = spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.ASCII);
        assertThat(packHex(spec, FieldValue.text(""))).isEqualTo(hexOf("00"));
        Unpacked result = unpackAt(spec, hexOf("00"));
        assertThat(result.value()).isEqualTo(FieldValue.text(""));
        assertThat(result.nextOffset()).isEqualTo(START + 2);
    }

    @Test
    void maxLengthVariableValueIsAllowed() {
        FieldSpec spec = spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.ASCII);
        String digits = "1".repeat(19);
        assertThat(packHex(spec, FieldValue.text(digits))).isEqualTo(hexOf("19" + digits));
    }

    @Test
    void signedAmountMustBeGivenInFullWithItsSign() {
        FieldSpec spec = spec(FieldType.XN, LengthKind.FIXED, 9, Encoding.ASCII);
        assertThat(packHex(spec, FieldValue.text("C00000100"))).isEqualTo(hexOf("C00000100"));
        Unpacked result = unpackAt(spec, hexOf("D00000250"));
        assertThat(result.value()).isEqualTo(FieldValue.text("D00000250"));
        assertThat(result.nextOffset()).isEqualTo(START + 9);
    }

    @Test
    void unpackLeavesFollowingBytesAlone() {
        FieldSpec spec = spec(FieldType.N, LengthKind.FIXED, 4, Encoding.ASCII);
        byte[] buffer = CodecSupport.buffer(hexOf("1234") + "FFFF");
        Unpacked result = FieldCodec.unpack(spec, buffer, START);
        assertThat(result.value()).isEqualTo(FieldValue.text("1234"));
        assertThat(result.nextOffset()).isEqualTo(START + 4);
    }
}
