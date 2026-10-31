package dev.paysim.iso8583.codec;

import static dev.paysim.iso8583.codec.CodecSupport.FIELD;
import static dev.paysim.iso8583.codec.CodecSupport.START;
import static dev.paysim.iso8583.codec.CodecSupport.hexOf;
import static dev.paysim.iso8583.codec.CodecSupport.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.iso8583.FieldValue;
import dev.paysim.iso8583.IsoPackException;
import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import dev.paysim.iso8583.spec.Padding;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class FieldCodecErrorTest {

    private static final FieldSpec N6 = spec(FieldType.N, LengthKind.FIXED, 6, Encoding.ASCII);
    private static final FieldSpec PAN = spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.ASCII);
    private static final FieldSpec BCD_PAN = spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.BCD);
    private static final FieldSpec TRACK = spec(FieldType.Z, LengthKind.LLVAR, 37, Encoding.BCD);

    private static void assertParse(FieldSpec spec, String wireHex, int offset, String message) {
        assertThatThrownBy(() -> CodecSupport.unpackAt(spec, wireHex))
                .isInstanceOfSatisfying(IsoParseException.class, e -> {
                    assertThat(e.field()).isEqualTo(FIELD);
                    assertThat(e.offset()).isEqualTo(offset);
                    assertThat(e).hasMessage("field " + FIELD + " at offset " + offset + ": " + message);
                });
    }

    private static void assertPack(FieldSpec spec, FieldValue value, String message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> FieldCodec.pack(spec, value, out))
                .isInstanceOfSatisfying(IsoPackException.class, e -> {
                    assertThat(e.field()).isEqualTo(FIELD);
                    assertThat(e).hasMessage("field " + FIELD + ": " + message);
                });
        assertThat(out.size()).as("nothing written on failure").isZero();
    }

    // ---- unpack: truncation

    @Test
    void truncatedFixedValue() {
        assertParse(N6, hexOf("12345"), START, "needs 6 bytes, only 5 left");
    }

    @Test
    void truncatedVariableValueReportsWhereTheDataStarts() {
        assertParse(PAN, hexOf("16" + "1234567"), START + 2, "needs 16 bytes, only 7 left");
    }

    @Test
    void truncatedPrefix() {
        assertParse(PAN, hexOf("1"), START, "needs 2 bytes, only 1 left");
    }

    @Test
    void offsetPastTheEndCountsAsZeroLeft() {
        assertThatThrownBy(() -> FieldCodec.unpack(N6, new byte[3], 10))
                .isInstanceOfSatisfying(IsoParseException.class, e -> {
                    assertThat(e.offset()).isEqualTo(10);
                    assertThat(e).hasMessage("field 7 at offset 10: needs 6 bytes, only 0 left");
                });
    }

    @Test
    void truncatedBcdValue() {
        assertParse(BCD_PAN, "0801", START + 1, "needs 4 bytes, only 1 left");
    }

    // ---- unpack: prefix

    @Test
    void nonDigitAsciiPrefix() {
        assertParse(PAN, hexOf("1X"), START + 1, "non-digit in length prefix at index 1");
    }

    @Test
    void lengthOverMaxPointsAtThePrefix() {
        assertParse(PAN, hexOf("25"), START, "length 25 exceeds max 19");
    }

    @Test
    void bcdPrefixWithInvalidNibble() {
        assertParse(BCD_PAN, "A5", START, "invalid BCD nibble in length prefix");
        assertParse(BCD_PAN, "1A", START, "invalid BCD nibble in length prefix");
    }

    @Test
    void bcdLllvarPrefixWithNonZeroHighNibble() {
        FieldSpec spec = spec(FieldType.N, LengthKind.LLLVAR, 999, Encoding.BCD);
        assertParse(spec, "1005", START, "invalid BCD nibble in length prefix");
        assertParse(spec, "0A05", START, "invalid BCD nibble in length prefix");
    }

    @Test
    void bcdLengthOverMax() {
        assertParse(BCD_PAN, "25", START, "length 25 exceeds max 19");
    }

    // ---- unpack: content

    @Test
    void badDigitReportsOffsetOfOffendingByteAndIndexOnly() {
        assertParse(N6, hexOf("12A456"), START + 2, "non-digit at index 2 for type N");
    }

    @Test
    void badCharacterInVariableFieldIsAbsolute() {
        assertParse(PAN, hexOf("051234x"), START + 2 + 4, "non-digit at index 4 for type N");
    }

    @Test
    void highBitByteIsRejected() {
        FieldSpec an = spec(FieldType.AN, LengthKind.FIXED, 4, Encoding.ASCII);
        assertParse(an, "414243E9", START + 3, "invalid character at index 3 for type AN");
    }

    @Test
    void signedAmountWithBadSignOrDigit() {
        FieldSpec xn = spec(FieldType.XN, LengthKind.FIXED, 9, Encoding.ASCII);
        assertParse(xn, hexOf("X00000100"), START, "invalid sign at index 0 for type XN");
        assertParse(xn, hexOf("C0000O100"), START + 5, "non-digit at index 5 for type XN");
    }

    @Test
    void invalidBcdNibbleInNumeric() {
        FieldSpec spec = spec(FieldType.N, LengthKind.FIXED, 6, Encoding.BCD);
        assertParse(spec, "123A56", START + 1, "invalid BCD nibble at index 3 for type N");
    }

    @Test
    void trackTwoRejectsLettersOtherThanSeparator() {
        assertParse(TRACK, "04411A", START + 1 + 1, "invalid BCD nibble at index 3 for type Z");
        assertParse(TRACK, "04411E", START + 2, "invalid BCD nibble at index 3 for type Z");
    }

    @Test
    void trackTwoRejectsFOutsideTheFinalPad() {
        assertParse(TRACK, "04411F", START + 2, "invalid BCD nibble at index 3 for type Z");
        assertParse(TRACK, "04F111", START + 1, "invalid BCD nibble at index 0 for type Z");
        // odd length: the F belongs only at the very end
        assertParse(TRACK, "03F123", START + 1, "invalid BCD nibble at index 0 for type Z");
        assertParse(TRACK, "0341F1", START + 2, "invalid BCD nibble at index 2 for type Z");
    }

    @Test
    void trackTwoOddLengthWithoutFPad() {
        assertParse(TRACK, "03411" + "2", START + 2, "missing 0xF pad nibble");
    }

    @Test
    void numericOddLengthWithNonZeroPadNibble() {
        assertParse(spec(FieldType.N, LengthKind.FIXED, 3, Encoding.BCD), "1123", START, "non-zero pad nibble");
    }

    @Test
    void invalidHexInBinaryField() {
        FieldSpec spec = spec(FieldType.B, LengthKind.FIXED, 4, Encoding.ASCII);
        assertParse(spec, hexOf("0123456G"), START + 7, "invalid hex digit at index 7");
    }

    // ---- pack

    @Test
    void textRequiredForNonBinaryTypes() {
        assertPack(N6, FieldValue.binary(new byte[6]), "type N requires a Text value, got Binary");
    }

    @Test
    void binaryRequiredForTypeB() {
        FieldSpec b = spec(FieldType.B, LengthKind.FIXED, 8, Encoding.BINARY);
        assertPack(b, FieldValue.text("12345678"), "type B requires a Binary value, got Text");
    }

    @Test
    void fixedValueLongerThanSpec() {
        assertPack(N6, FieldValue.text("1234567"), "length 7 exceeds max 6");
    }

    @Test
    void variableValueLongerThanSpec() {
        assertPack(PAN, FieldValue.text("1".repeat(20)), "length 20 exceeds max 19");
    }

    @Test
    void binaryFixedNeedsExactLength() {
        FieldSpec b = spec(FieldType.B, LengthKind.FIXED, 8, Encoding.ASCII);
        assertPack(b, FieldValue.binary(new byte[7]), "length 7 does not match fixed length 8");
        assertPack(b, FieldValue.binary(new byte[9]), "length 9 exceeds max 8");
    }

    @Test
    void badCharactersReportIndexNotContent() {
        assertPack(N6, FieldValue.text("12A456"), "non-digit at index 2 for type N");
        assertPack(spec(FieldType.AN, LengthKind.FIXED, 4, Encoding.ASCII), FieldValue.text("Aé"),
                "invalid character at index 1 for type AN");
    }

    @Test
    void trackTwoRejectsOtherLetters() {
        assertPack(TRACK, FieldValue.text("4111X25"), "invalid character at index 4 for type Z");
    }

    @Test
    void shortSignedAmountIsRejectedNotZeroPadded() {
        FieldSpec xn = spec(FieldType.XN, LengthKind.FIXED, 9, Encoding.ASCII);
        assertPack(xn, FieldValue.text("C100"), "XN value must be exactly 9 characters including its sign, got 4");
    }

    @Test
    void signedAmountNeedsSignAndDigit() {
        FieldSpec xn = spec(FieldType.XN, LengthKind.FIXED, 9, Encoding.ASCII);
        assertPack(xn, FieldValue.text("100000000"), "invalid sign at index 0 for type XN");
        assertPack(xn, FieldValue.text(""), "XN needs a C or D sign and at least one digit");
    }

    @Test
    void shortFixedValueWithoutPaddingIsRejected() {
        FieldSpec spec = new FieldSpec(FIELD, "t", FieldType.N, LengthKind.FIXED, 6, Encoding.ASCII, Padding.NONE);
        assertPack(spec, FieldValue.text("123"),
                "length 3 does not match fixed length 6 and the spec has no padding");
    }

    @Test
    void spacePaddingCannotBeEncodedInBcd() {
        FieldSpec spec = new FieldSpec(FIELD, "t", FieldType.Z, LengthKind.FIXED, 6, Encoding.BCD,
                Padding.RIGHT_SPACES);
        assertPack(spec, FieldValue.text("123"), "space padding cannot be encoded in BCD");
    }

    // ---- no content leaks

    @Test
    void errorMessagesNeverContainPartOfAPan() {
        String pan = "4111111111111111";
        String longPan = pan + "999";
        String badPan = pan.substring(0, 3) + "x" + pan.substring(4);
        FieldSpec narrow = spec(FieldType.N, LengthKind.LLVAR, 16, Encoding.ASCII);
        FieldSpec ascii = spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.ASCII);
        FieldSpec bcd = spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.BCD);

        java.util.List<String> messages = new java.util.ArrayList<>();
        messages.add(packFailure(narrow, longPan));
        messages.add(packFailure(ascii, badPan));
        messages.add(unpackFailure(ascii, hexOf("16" + badPan)));
        messages.add(unpackFailure(ascii, hexOf("16" + pan.substring(0, 7))));
        messages.add(unpackFailure(bcd, "16" + pan.substring(0, 15) + "A"));
        for (String message : messages) {
            assertThat(message).doesNotContainPattern("\\d{4}");
            assertThat(message).doesNotContain("4111");
        }
    }

    private static String packFailure(FieldSpec spec, String value) {
        try {
            FieldCodec.pack(spec, FieldValue.text(value), new ByteArrayOutputStream());
        } catch (IsoPackException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected pack failure");
    }

    private static String unpackFailure(FieldSpec spec, String wireHex) {
        try {
            CodecSupport.unpackAt(spec, wireHex);
        } catch (IsoParseException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected parse failure");
    }
}
