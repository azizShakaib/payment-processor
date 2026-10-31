package dev.paysim.iso8583.spec;

import static dev.paysim.iso8583.spec.Encoding.ASCII;
import static dev.paysim.iso8583.spec.Encoding.BCD;
import static dev.paysim.iso8583.spec.Encoding.BINARY;
import static dev.paysim.iso8583.spec.FieldType.N;
import static dev.paysim.iso8583.spec.LengthKind.FIXED;
import static dev.paysim.iso8583.spec.LengthKind.LLVAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageSpecTest {

    @Test
    void builderRoundTrip() {
        MessageSpec spec = MessageSpec.builder("paysim-ascii-87")
                .field(2, "Primary account number", N, LLVAR, 19, ASCII)
                .field(3, "Processing code", N, FIXED, 6, ASCII)
                .build();

        assertThat(spec.name()).isEqualTo("paysim-ascii-87");
        assertThat(spec.has(2)).isTrue();
        assertThat(spec.has(3)).isTrue();
        assertThat(spec.has(4)).isFalse();
        assertThat(spec.field(2).lengthKind()).isEqualTo(LLVAR);
        assertThat(spec.field(3)).isEqualTo(FieldSpec.of(3, "Processing code", N, FIXED, 6, ASCII));
        assertThat(spec.toString()).contains("paysim-ascii-87").contains("2");
    }

    @Test
    void encodingsDefaultToAscii() {
        MessageSpec spec = MessageSpec.builder("defaults").build();

        assertThat(spec.mtiEncoding()).isEqualTo(ASCII);
        assertThat(spec.bitmapEncoding()).isEqualTo(ASCII);
        assertThat(spec.fields()).isEmpty();
    }

    @Test
    void acceptsBcdMtiAndBinaryBitmap() {
        MessageSpec spec = MessageSpec.builder("binary-ish")
                .mtiEncoding(BCD)
                .bitmapEncoding(BINARY)
                .build();

        assertThat(spec.mtiEncoding()).isEqualTo(BCD);
        assertThat(spec.bitmapEncoding()).isEqualTo(BINARY);
    }

    @Test
    void rejectsDuplicateFieldNumber() {
        MessageSpec.Builder builder = MessageSpec.builder("dup")
                .field(3, "Processing code", N, FIXED, 6, ASCII);

        assertThatThrownBy(() -> builder.field(3, "Again", N, FIXED, 6, ASCII))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 3");
    }

    @Test
    void unknownFieldLookupNamesFieldAndSpec() {
        MessageSpec spec = MessageSpec.builder("tiny").build();

        assertThatThrownBy(() -> spec.field(7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("field 7 is not defined in spec tiny");
    }

    @Test
    void hasIsFalseForOutOfRangeNumbers() {
        MessageSpec spec = MessageSpec.builder("tiny").build();

        assertThat(spec.has(-1)).isFalse();
        assertThat(spec.has(0)).isFalse();
        assertThat(spec.has(1)).isFalse();
        assertThat(spec.has(129)).isFalse();
    }

    @Test
    void fieldsAreSortedByNumberRegardlessOfInsertionOrder() {
        MessageSpec spec = MessageSpec.builder("order")
                .field(11, "STAN", N, FIXED, 6, ASCII)
                .field(3, "Processing code", N, FIXED, 6, ASCII)
                .field(128, "MAC", FieldType.B, FIXED, 8, BINARY)
                .field(2, "PAN", N, LLVAR, 19, ASCII)
                .build();

        assertThat(spec.fields()).extracting(FieldSpec::number).containsExactly(2, 3, 11, 128);
    }

    @Test
    void fieldsListIsUnmodifiable() {
        MessageSpec spec = MessageSpec.builder("ro")
                .field(3, "Processing code", N, FIXED, 6, ASCII)
                .build();

        assertThatThrownBy(() -> spec.fields().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builtSpecIsNotAffectedByLaterBuilderChanges() {
        MessageSpec.Builder builder = MessageSpec.builder("frozen")
                .field(3, "Processing code", N, FIXED, 6, ASCII);
        MessageSpec spec = builder.build();

        builder.field(4, "Amount", N, FIXED, 12, ASCII);

        assertThat(spec.has(4)).isFalse();
        assertThat(spec.fields()).hasSize(1);
    }

    @Test
    void rejectsBinaryMtiEncoding() {
        MessageSpec.Builder builder = MessageSpec.builder("bad-mti");

        assertThatThrownBy(() -> builder.mtiEncoding(BINARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mtiEncoding");
    }

    @Test
    void rejectsBcdBitmapEncoding() {
        MessageSpec.Builder builder = MessageSpec.builder("bad-bitmap");

        assertThatThrownBy(() -> builder.bitmapEncoding(BCD))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bitmapEncoding");
    }

    @Test
    void rejectsBlankSpecName() {
        assertThatThrownBy(() -> MessageSpec.builder(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
