package dev.paysim.iso8583.spec;

import static dev.paysim.iso8583.spec.Encoding.ASCII;
import static dev.paysim.iso8583.spec.Encoding.BCD;
import static dev.paysim.iso8583.spec.Encoding.BINARY;
import static dev.paysim.iso8583.spec.FieldType.AN;
import static dev.paysim.iso8583.spec.FieldType.ANS;
import static dev.paysim.iso8583.spec.FieldType.B;
import static dev.paysim.iso8583.spec.FieldType.N;
import static dev.paysim.iso8583.spec.FieldType.XN;
import static dev.paysim.iso8583.spec.FieldType.Z;
import static dev.paysim.iso8583.spec.LengthKind.FIXED;
import static dev.paysim.iso8583.spec.LengthKind.LLLVAR;
import static dev.paysim.iso8583.spec.LengthKind.LLVAR;
import static dev.paysim.iso8583.spec.Padding.LEFT_ZEROS;
import static dev.paysim.iso8583.spec.Padding.NONE;
import static dev.paysim.iso8583.spec.Padding.RIGHT_SPACES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FieldSpecTest {

    @Test
    void validSpecKeepsItsValues() {
        FieldSpec spec = new FieldSpec(4, "Amount", N, FIXED, 12, ASCII, LEFT_ZEROS);

        assertThat(spec.number()).isEqualTo(4);
        assertThat(spec.name()).isEqualTo("Amount");
        assertThat(spec.type()).isEqualTo(N);
        assertThat(spec.lengthKind()).isEqualTo(FIXED);
        assertThat(spec.maxLength()).isEqualTo(12);
        assertThat(spec.encoding()).isEqualTo(ASCII);
        assertThat(spec.padding()).isEqualTo(LEFT_ZEROS);
    }

    @Test
    void rejectsNumberOutsideTwoTo128() {
        assertThatThrownBy(() -> new FieldSpec(1, "Bitmap", B, FIXED, 8, BINARY, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 1");
        assertThatThrownBy(() -> new FieldSpec(0, "Zero", N, FIXED, 8, ASCII, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 0");
        assertThatThrownBy(() -> new FieldSpec(129, "Too high", N, FIXED, 8, ASCII, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 129");
    }

    @Test
    void acceptsBoundaryNumbers() {
        assertThat(new FieldSpec(2, "Low", N, LLVAR, 19, ASCII, NONE).number()).isEqualTo(2);
        assertThat(new FieldSpec(128, "High", B, FIXED, 8, BINARY, NONE).number()).isEqualTo(128);
    }

    @Test
    void rejectsNullsAndBlankName() {
        assertThatThrownBy(() -> new FieldSpec(3, null, N, FIXED, 6, ASCII, NONE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("field 3");
        assertThatThrownBy(() -> new FieldSpec(3, "x", null, FIXED, 6, ASCII, NONE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("field 3");
        assertThatThrownBy(() -> new FieldSpec(3, "x", N, null, 6, ASCII, NONE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("field 3");
        assertThatThrownBy(() -> new FieldSpec(3, "x", N, FIXED, 6, null, NONE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("field 3");
        assertThatThrownBy(() -> new FieldSpec(3, "x", N, FIXED, 6, ASCII, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("field 3");
        assertThatThrownBy(() -> new FieldSpec(3, "  ", N, FIXED, 6, ASCII, NONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("field 3");
    }

    @Test
    void rejectsMaxLengthBelowOne() {
        assertThatThrownBy(() -> new FieldSpec(3, "x", N, FIXED, 0, ASCII, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 3");
    }

    @Test
    void rejectsMaxLengthAboveWhatThePrefixCanExpress() {
        assertThatThrownBy(() -> new FieldSpec(2, "PAN", N, LLVAR, 100, ASCII, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 2");
        assertThatThrownBy(() -> new FieldSpec(55, "ICC", B, LLLVAR, 1000, BINARY, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 55");
        assertThat(new FieldSpec(2, "PAN", N, LLVAR, 99, ASCII, NONE).maxLength()).isEqualTo(99);
        assertThat(new FieldSpec(55, "ICC", B, LLLVAR, 999, BINARY, NONE).maxLength()).isEqualTo(999);
    }

    @Test
    void rejectsBinaryEncodingForTextTypes() {
        assertThatThrownBy(() -> new FieldSpec(4, "Amount", N, FIXED, 12, BINARY, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 4");
    }

    @Test
    void rejectsBcdEncodingExceptForNumericAndTrack2() {
        assertThatThrownBy(() -> new FieldSpec(39, "Response", AN, FIXED, 2, BCD, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 39");
        assertThatThrownBy(() -> new FieldSpec(41, "Terminal", ANS, FIXED, 8, BCD, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 41");
        assertThatThrownBy(() -> new FieldSpec(52, "PIN", B, FIXED, 8, BCD, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 52");
        assertThatThrownBy(() -> new FieldSpec(28, "Fee", XN, FIXED, 9, BCD, NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 28");
        assertThat(new FieldSpec(4, "Amount", N, FIXED, 12, BCD, LEFT_ZEROS).encoding()).isEqualTo(BCD);
        assertThat(new FieldSpec(35, "Track 2", Z, LLVAR, 37, BCD, NONE).encoding()).isEqualTo(BCD);
    }

    @Test
    void rejectsPaddingOnVariableLengthFields() {
        assertThatThrownBy(() -> new FieldSpec(2, "PAN", N, LLVAR, 19, ASCII, LEFT_ZEROS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 2");
        assertThatThrownBy(() -> new FieldSpec(54, "Additional", AN, LLLVAR, 120, ASCII, RIGHT_SPACES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 54");
    }

    @Test
    void rejectsPaddingOnBinaryFields() {
        assertThatThrownBy(() -> new FieldSpec(52, "PIN", B, FIXED, 8, BINARY, LEFT_ZEROS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 52");
    }

    @Test
    void rejectsLeftZerosPaddingUnlessNumeric() {
        assertThatThrownBy(() -> new FieldSpec(41, "Terminal", ANS, FIXED, 8, ASCII, LEFT_ZEROS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 41");
        assertThatThrownBy(() -> new FieldSpec(39, "Response", AN, FIXED, 2, ASCII, LEFT_ZEROS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 39");
        assertThatThrownBy(() -> new FieldSpec(35, "Track 2", Z, FIXED, 37, ASCII, LEFT_ZEROS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 35");
        assertThat(new FieldSpec(28, "Fee", XN, FIXED, 9, ASCII, LEFT_ZEROS).padding()).isEqualTo(LEFT_ZEROS);
    }

    @Test
    void rejectsRightSpacesPaddingUnlessText() {
        assertThatThrownBy(() -> new FieldSpec(4, "Amount", N, FIXED, 12, ASCII, RIGHT_SPACES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 4");
        assertThatThrownBy(() -> new FieldSpec(28, "Fee", XN, FIXED, 9, ASCII, RIGHT_SPACES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 28");
        assertThat(new FieldSpec(41, "Terminal", ANS, FIXED, 8, ASCII, RIGHT_SPACES).padding())
                .isEqualTo(RIGHT_SPACES);
    }

    @Test
    void ofPicksLeftZerosForFixedNumericAndSignedAmount() {
        assertThat(FieldSpec.of(4, "Amount", N, FIXED, 12, ASCII).padding()).isEqualTo(LEFT_ZEROS);
        assertThat(FieldSpec.of(28, "Fee", XN, FIXED, 9, ASCII).padding()).isEqualTo(LEFT_ZEROS);
    }

    @Test
    void ofPicksRightSpacesForFixedTextAndTrack() {
        assertThat(FieldSpec.of(39, "Response", AN, FIXED, 2, ASCII).padding()).isEqualTo(RIGHT_SPACES);
        assertThat(FieldSpec.of(41, "Terminal", ANS, FIXED, 8, ASCII).padding()).isEqualTo(RIGHT_SPACES);
        assertThat(FieldSpec.of(35, "Track 2", Z, FIXED, 37, ASCII).padding()).isEqualTo(RIGHT_SPACES);
    }

    @Test
    void ofPicksNoPaddingForBinaryAndVariableLength() {
        assertThat(FieldSpec.of(52, "PIN", B, FIXED, 8, BINARY).padding()).isEqualTo(NONE);
        assertThat(FieldSpec.of(2, "PAN", N, LLVAR, 19, ASCII).padding()).isEqualTo(NONE);
        assertThat(FieldSpec.of(54, "Additional", AN, LLLVAR, 120, ASCII).padding()).isEqualTo(NONE);
    }
}
