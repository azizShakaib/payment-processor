package dev.paysim.iso8583.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FieldTypeTest {

    @Test
    void numericAcceptsDigitsOnly() {
        assertThat(FieldType.N.isValid("000012345600")).isTrue();
        assertThat(FieldType.N.isValid("12a")).isFalse();
        assertThat(FieldType.N.isValid("12 3")).isFalse();
        assertThat(FieldType.N.isValid("-123")).isFalse();
    }

    @Test
    void alphanumericAcceptsLettersAndDigits() {
        assertThat(FieldType.AN.isValid("AB12cd")).isTrue();
        assertThat(FieldType.AN.isValid("AB-1")).isFalse();
        assertThat(FieldType.AN.isValid("AB 1")).isFalse();
    }

    @Test
    void alphanumericSpecialAcceptsPrintableAscii() {
        assertThat(FieldType.ANS.isValid("ACME STORE #12, NEW YORK")).isTrue();
        assertThat(FieldType.ANS.isValid(" ~")).isTrue();
        assertThat(FieldType.ANS.isValid("bad\u0007bell")).isFalse();
        assertThat(FieldType.ANS.isValid("tab\there")).isFalse();
        assertThat(FieldType.ANS.isValid("café")).isFalse();
        assertThat(FieldType.ANS.isValid("del\u007f")).isFalse();
    }

    @Test
    void track2AcceptsDigitsSeparatorAndD() {
        assertThat(FieldType.Z.isValid("4111111111111111=2512")).isTrue();
        assertThat(FieldType.Z.isValid("4111111111111111D2512")).isTrue();
        assertThat(FieldType.Z.isValid("4111111111111111^2512")).isFalse();
        assertThat(FieldType.Z.isValid("4111 1111")).isFalse();
    }

    @Test
    void signedAmountNeedsSignThenDigits() {
        assertThat(FieldType.XN.isValid("C00000100")).isTrue();
        assertThat(FieldType.XN.isValid("D00000100")).isTrue();
        assertThat(FieldType.XN.isValid("X00000100")).isFalse();
        assertThat(FieldType.XN.isValid("C")).isFalse();
        assertThat(FieldType.XN.isValid("00000100")).isFalse();
        assertThat(FieldType.XN.isValid("C0000x100")).isFalse();
    }

    @Test
    void emptyStringIsAcceptedByTextTypesExceptSignedAmount() {
        assertThat(FieldType.N.isValid("")).isTrue();
        assertThat(FieldType.AN.isValid("")).isTrue();
        assertThat(FieldType.ANS.isValid("")).isTrue();
        assertThat(FieldType.Z.isValid("")).isTrue();
        assertThat(FieldType.XN.isValid("")).isFalse();
    }

    @Test
    void binaryIsNotValidatedAsText() {
        assertThatThrownBy(() -> FieldType.B.isValid("00FF"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullValueIsRejected() {
        assertThatThrownBy(() -> FieldType.N.isValid(null))
                .isInstanceOf(NullPointerException.class);
    }
}
