package dev.paysim.iso8583.spec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LengthKindTest {

    @Test
    void prefixDigits() {
        assertThat(LengthKind.FIXED.prefixDigits()).isZero();
        assertThat(LengthKind.LLVAR.prefixDigits()).isEqualTo(2);
        assertThat(LengthKind.LLLVAR.prefixDigits()).isEqualTo(3);
    }

    @Test
    void maxPossibleLength() {
        assertThat(LengthKind.FIXED.maxPossibleLength()).isEqualTo(Integer.MAX_VALUE);
        assertThat(LengthKind.LLVAR.maxPossibleLength()).isEqualTo(99);
        assertThat(LengthKind.LLLVAR.maxPossibleLength()).isEqualTo(999);
    }
}
