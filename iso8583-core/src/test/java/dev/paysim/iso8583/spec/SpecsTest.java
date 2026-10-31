package dev.paysim.iso8583.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpecsTest {

    private static final MessageSpec SPEC = Specs.PAYSIM_ASCII_87;

    @Test
    void definesExactlyTheDocumentedFields() {
        List<Integer> expected = List.of(2, 3, 4, 7, 11, 12, 13, 14, 18, 22, 23, 25, 28, 32, 35,
                37, 38, 39, 41, 42, 43, 45, 49, 52, 54, 55, 64, 70, 90, 95, 128);

        assertThat(SPEC.fields()).hasSize(31);
        assertThat(SPEC.fields()).extracting(FieldSpec::number).containsExactlyElementsOf(expected);
    }

    @Test
    void usesAsciiForMtiBitmapAndEveryField() {
        assertThat(SPEC.name()).isEqualTo("paysim-ascii-87");
        assertThat(SPEC.mtiEncoding()).isEqualTo(Encoding.ASCII);
        assertThat(SPEC.bitmapEncoding()).isEqualTo(Encoding.ASCII);
        assertThat(SPEC.fields()).allMatch(f -> f.encoding() == Encoding.ASCII);
    }

    @Test
    void primaryAccountNumber() {
        FieldSpec f = SPEC.field(2);
        assertThat(f.type()).isEqualTo(FieldType.N);
        assertThat(f.lengthKind()).isEqualTo(LengthKind.LLVAR);
        assertThat(f.maxLength()).isEqualTo(19);
        assertThat(f.padding()).isEqualTo(Padding.NONE);
    }

    @Test
    void track2Data() {
        FieldSpec f = SPEC.field(35);
        assertThat(f.type()).isEqualTo(FieldType.Z);
        assertThat(f.lengthKind()).isEqualTo(LengthKind.LLVAR);
        assertThat(f.maxLength()).isEqualTo(37);
    }

    @Test
    void transactionFeeCountsItsSignInLength() {
        FieldSpec f = SPEC.field(28);
        assertThat(f.type()).isEqualTo(FieldType.XN);
        assertThat(f.lengthKind()).isEqualTo(LengthKind.FIXED);
        assertThat(f.maxLength()).isEqualTo(9);
        assertThat(f.padding()).isEqualTo(Padding.LEFT_ZEROS);
    }

    @Test
    void pinDataIsFixedBinary() {
        FieldSpec f = SPEC.field(52);
        assertThat(f.type()).isEqualTo(FieldType.B);
        assertThat(f.lengthKind()).isEqualTo(LengthKind.FIXED);
        assertThat(f.maxLength()).isEqualTo(8);
        assertThat(f.padding()).isEqualTo(Padding.NONE);
    }

    @Test
    void iccDataIsLlLvarBinary() {
        FieldSpec f = SPEC.field(55);
        assertThat(f.type()).isEqualTo(FieldType.B);
        assertThat(f.lengthKind()).isEqualTo(LengthKind.LLLVAR);
        assertThat(f.maxLength()).isEqualTo(255);
    }

    @Test
    void terminalIdIsSpacePaddedAnsi() {
        FieldSpec f = SPEC.field(41);
        assertThat(f.type()).isEqualTo(FieldType.ANS);
        assertThat(f.lengthKind()).isEqualTo(LengthKind.FIXED);
        assertThat(f.maxLength()).isEqualTo(8);
        assertThat(f.padding()).isEqualTo(Padding.RIGHT_SPACES);
    }

    @Test
    void fieldOneAndUnlistedFieldsAreAbsent() {
        assertThat(SPEC.has(1)).isFalse();
        assertThat(SPEC.has(5)).isFalse();
    }
}
