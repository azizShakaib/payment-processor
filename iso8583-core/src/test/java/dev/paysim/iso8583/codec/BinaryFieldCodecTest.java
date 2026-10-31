package dev.paysim.iso8583.codec;

import static dev.paysim.iso8583.codec.CodecSupport.START;
import static dev.paysim.iso8583.codec.CodecSupport.hexOf;
import static dev.paysim.iso8583.codec.CodecSupport.packHex;
import static dev.paysim.iso8583.codec.CodecSupport.spec;
import static dev.paysim.iso8583.codec.CodecSupport.unpackAt;
import static org.assertj.core.api.Assertions.assertThat;

import dev.paysim.common.Hex;
import dev.paysim.iso8583.FieldValue;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import org.junit.jupiter.api.Test;

class BinaryFieldCodecTest {

    private static final byte[] EIGHT = Hex.decode("0123456789ABCDEF");

    @Test
    void asciiFixedIsSixteenUppercaseHexCharacters() {
        FieldSpec spec = spec(FieldType.B, LengthKind.FIXED, 8, Encoding.ASCII);
        assertThat(packHex(spec, FieldValue.binary(EIGHT))).isEqualTo(hexOf("0123456789ABCDEF"));
        Unpacked result = unpackAt(spec, hexOf("0123456789ABCDEF"));
        assertThat(result.value()).isEqualTo(FieldValue.binary(EIGHT));
        assertThat(result.nextOffset()).isEqualTo(START + 16);
    }

    @Test
    void asciiLllvarCountsBytesNotHexCharacters() {
        FieldSpec spec = spec(FieldType.B, LengthKind.LLLVAR, 255, Encoding.ASCII);
        byte[] value = Hex.decode("AABBCC");
        assertThat(packHex(spec, FieldValue.binary(value))).isEqualTo(hexOf("003AABBCC"));
        Unpacked result = unpackAt(spec, hexOf("003AABBCC"));
        assertThat(result.value()).isEqualTo(FieldValue.binary(value));
        assertThat(result.nextOffset()).isEqualTo(START + 9);
    }

    @Test
    void lowercaseHexIsAccepted() {
        FieldSpec spec = spec(FieldType.B, LengthKind.FIXED, 8, Encoding.ASCII);
        assertThat(unpackAt(spec, hexOf("0123456789abcdef")).value()).isEqualTo(FieldValue.binary(EIGHT));
    }

    @Test
    void binaryFixedIsRaw() {
        FieldSpec spec = spec(FieldType.B, LengthKind.FIXED, 8, Encoding.BINARY);
        assertThat(packHex(spec, FieldValue.binary(EIGHT))).isEqualTo("0123456789ABCDEF");
        Unpacked result = unpackAt(spec, "0123456789ABCDEF");
        assertThat(result.value()).isEqualTo(FieldValue.binary(EIGHT));
        assertThat(result.nextOffset()).isEqualTo(START + 8);
    }

    @Test
    void binaryLlvarHasBcdPrefixThenRawBytes() {
        FieldSpec spec = spec(FieldType.B, LengthKind.LLVAR, 50, Encoding.BINARY);
        byte[] value = Hex.decode("DEADBEEF");
        assertThat(packHex(spec, FieldValue.binary(value))).isEqualTo("04DEADBEEF");
        Unpacked result = unpackAt(spec, "04DEADBEEF");
        assertThat(result.value()).isEqualTo(FieldValue.binary(value));
        assertThat(result.nextOffset()).isEqualTo(START + 5);
    }

    @Test
    void emptyBinaryVariableValueIsAllowed() {
        FieldSpec spec = spec(FieldType.B, LengthKind.LLVAR, 50, Encoding.BINARY);
        assertThat(packHex(spec, FieldValue.binary(new byte[0]))).isEqualTo("00");
        assertThat(unpackAt(spec, "00").value()).isEqualTo(FieldValue.binary(new byte[0]));
    }
}
