package dev.paysim.iso8583.codec;

import static dev.paysim.iso8583.codec.CodecSupport.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.iso8583.FieldValue;
import dev.paysim.iso8583.IsoPackException;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import dev.paysim.iso8583.spec.Padding;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/** validate must accept and reject exactly what pack does, with the same message. */
class FieldCodecValidateTest {

    private static final FieldSpec N6 = spec(FieldType.N, LengthKind.FIXED, 6, Encoding.ASCII);
    private static final FieldSpec PAN = spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.ASCII);
    private static final FieldSpec AN4 = spec(FieldType.AN, LengthKind.FIXED, 4, Encoding.ASCII);
    private static final FieldSpec XN9 = spec(FieldType.XN, LengthKind.FIXED, 9, Encoding.ASCII);
    private static final FieldSpec B8 = spec(FieldType.B, LengthKind.FIXED, 8, Encoding.ASCII);
    private static final FieldSpec B_VAR = spec(FieldType.B, LengthKind.LLLVAR, 4, Encoding.BINARY);

    private static void assertRejectedLikePack(FieldSpec spec, FieldValue value, String message) {
        assertThatThrownBy(() -> FieldCodec.validate(spec, value))
                .isInstanceOfSatisfying(IsoPackException.class,
                        e -> assertThat(e).hasMessage("field 7: " + message));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> FieldCodec.pack(spec, value, out))
                .isInstanceOfSatisfying(IsoPackException.class,
                        e -> assertThat(e).hasMessage("field 7: " + message));
        assertThat(out.size()).isZero();
    }

    private static void assertAcceptedLikePack(FieldSpec spec, FieldValue value) {
        assertThatCode(() -> FieldCodec.validate(spec, value)).doesNotThrowAnyException();
        assertThatCode(() -> FieldCodec.pack(spec, value, new ByteArrayOutputStream()))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsValidValuesOfEveryShape() {
        assertAcceptedLikePack(N6, FieldValue.text("123456"));
        assertAcceptedLikePack(N6, FieldValue.text("123"));
        assertAcceptedLikePack(PAN, FieldValue.text("4111111111111111"));
        assertAcceptedLikePack(AN4, FieldValue.text("AB"));
        assertAcceptedLikePack(XN9, FieldValue.text("C00000100"));
        assertAcceptedLikePack(B8, FieldValue.binary(new byte[8]));
        assertAcceptedLikePack(B_VAR, FieldValue.binary(new byte[2]));
    }

    @Test
    void rejectsWrongValueKinds() {
        assertRejectedLikePack(B8, FieldValue.text("12345678"), "type B requires a Binary value, got Text");
        assertRejectedLikePack(N6, FieldValue.binary(new byte[6]), "type N requires a Text value, got Binary");
    }

    @Test
    void rejectsTooLong() {
        assertRejectedLikePack(N6, FieldValue.text("1234567"), "length 7 exceeds max 6");
        assertRejectedLikePack(B_VAR, FieldValue.binary(new byte[5]), "length 5 exceeds max 4");
    }

    @Test
    void rejectsWrongFixedBinaryLength() {
        assertRejectedLikePack(B8, FieldValue.binary(new byte[7]), "length 7 does not match fixed length 8");
    }

    @Test
    void rejectsBadCharacters() {
        assertRejectedLikePack(N6, FieldValue.text("12A456"), "non-digit at index 2 for type N");
        assertRejectedLikePack(PAN, FieldValue.text("41x1"), "non-digit at index 2 for type N");
    }

    @Test
    void rejectsXnWithoutSignOrShort() {
        assertRejectedLikePack(XN9, FieldValue.text("X00000100"), "invalid sign at index 0 for type XN");
        assertRejectedLikePack(XN9, FieldValue.text("C100"),
                "XN value must be exactly 9 characters including its sign, got 4");
    }

    @Test
    void rejectsFixedTextShortOfSpecWithoutPadding() {
        FieldSpec noPad = new FieldSpec(7, "t", FieldType.AN, LengthKind.FIXED, 4, Encoding.ASCII, Padding.NONE);
        assertRejectedLikePack(noPad, FieldValue.text("AB"),
                "length 2 does not match fixed length 4 and the spec has no padding");
    }

    @Test
    void rejectsSpacePaddingInBcd() {
        FieldSpec bcdTrack = new FieldSpec(7, "t", FieldType.Z, LengthKind.FIXED, 6, Encoding.BCD,
                Padding.RIGHT_SPACES);
        assertRejectedLikePack(bcdTrack, FieldValue.text("12"), "space padding cannot be encoded in BCD");
    }

    @Test
    void messagesNeverContainFieldContent() {
        assertThatThrownBy(() -> FieldCodec.validate(PAN, FieldValue.text("4111x11111111111")))
                .hasMessageNotContaining("4111");
    }
}
