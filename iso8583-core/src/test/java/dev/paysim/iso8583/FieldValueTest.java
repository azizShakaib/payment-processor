package dev.paysim.iso8583;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class FieldValueTest {

    private static final String PAN = "4111111111111111";

    @Test
    void textEqualityIsByValue() {
        assertThat(FieldValue.text("abc")).isEqualTo(FieldValue.text("abc"));
        assertThat(FieldValue.text("abc")).isNotEqualTo(FieldValue.text("abd"));
        assertThat(FieldValue.text("abc").hashCode()).isEqualTo(FieldValue.text("abc").hashCode());
    }

    @Test
    void textRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> FieldValue.text(null));
    }

    @Test
    void binaryEqualityIsByContent() {
        FieldValue a = FieldValue.binary(new byte[] {1, 2, 3});
        FieldValue b = FieldValue.binary(new byte[] {1, 2, 3});
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(FieldValue.binary(new byte[] {1, 2, 4}));
        assertThat(a).isNotEqualTo(FieldValue.text("abc"));
    }

    @Test
    void binaryCopiesOnTheWayIn() {
        byte[] source = {1, 2, 3};
        FieldValue.Binary value = FieldValue.binary(source);
        source[0] = 99;
        assertThat(value.value()).containsExactly(1, 2, 3);
    }

    @Test
    void binaryCopiesOnTheWayOut() {
        FieldValue.Binary value = FieldValue.binary(new byte[] {1, 2, 3});
        value.value()[0] = 99;
        assertThat(value.value()).containsExactly(1, 2, 3);
    }

    @Test
    void binaryRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> FieldValue.binary(null));
    }

    @Test
    void toStringShowsSizeOnly() {
        assertThat(FieldValue.text(PAN)).hasToString("Text[16 chars]");
        assertThat(FieldValue.binary(new byte[8])).hasToString("Binary[8 bytes]");
    }

    @Test
    void toStringNeverContainsPartOfTheValue() {
        String text = FieldValue.text(PAN).toString();
        String binary = FieldValue.binary(PAN.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).toString();
        for (String shown : new String[] {text, binary}) {
            for (int i = 0; i + 4 <= PAN.length(); i++) {
                assertThat(shown).doesNotContain(PAN.substring(i, i + 4));
            }
        }
    }

    @Test
    void exceptionsExposeFieldAndOffset() {
        IsoParseException parse = new IsoParseException(35, 20, "oops");
        assertThat(parse).hasMessage("field 35 at offset 20: oops");
        assertThat(parse.field()).isEqualTo(35);
        assertThat(parse.offset()).isEqualTo(20);
        IsoPackException pack = new IsoPackException(2, "oops");
        assertThat(pack).hasMessage("field 2: oops");
        assertThat(pack.field()).isEqualTo(2);
    }
}
