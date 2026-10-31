package dev.paysim.iso8583;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BitmapTest {

    @Test
    void emptyHasNoFieldsAndNoSecondary() {
        Bitmap bitmap = Bitmap.empty();

        assertThat(bitmap.fields()).isEmpty();
        assertThat(bitmap.hasSecondary()).isFalse();
        assertThat(bitmap.primary()).isZero();
        assertThat(bitmap.secondary()).isZero();
    }

    @Test
    void field2IsBit2OfTheFirstByte() {
        assertThat(Bitmap.of(2).primary()).isEqualTo(0x4000_0000_0000_0000L);
    }

    @Test
    void field64IsTheLastBitOfThePrimary() {
        assertThat(Bitmap.of(64).primary()).isEqualTo(1L);
    }

    @Test
    void field65SetsBitOneAndTheFirstBitOfTheSecondary() {
        Bitmap bitmap = Bitmap.of(65);

        assertThat(bitmap.primary()).isEqualTo(0x8000_0000_0000_0000L);
        assertThat(bitmap.secondary()).isEqualTo(0x8000_0000_0000_0000L);
        assertThat(bitmap.hasSecondary()).isTrue();
    }

    @Test
    void field128IsTheLastBitOfTheSecondary() {
        Bitmap bitmap = Bitmap.of(128);

        assertThat(bitmap.secondary()).isEqualTo(1L);
        assertThat(bitmap.primary()).isEqualTo(0x8000_0000_0000_0000L);
    }

    @Test
    void echoFieldsSetBitOneBecauseOf70() {
        Bitmap bitmap = Bitmap.of(7, 11, 70);

        assertThat(bitmap.primary()).isEqualTo(0x8220_0000_0000_0000L);
        assertThat(bitmap.secondary()).isEqualTo(0x0400_0000_0000_0000L);
    }

    @Test
    void withReturnsANewBitmapAndLeavesTheOriginalAlone() {
        Bitmap base = Bitmap.of(2, 3);
        Bitmap more = base.with(70);

        assertThat(base.has(70)).isFalse();
        assertThat(base.hasSecondary()).isFalse();
        assertThat(more.has(70)).isTrue();
        assertThat(more.has(2)).isTrue();
        assertThat(more.hasSecondary()).isTrue();
    }

    @Test
    void withTheSameFieldTwiceChangesNothing() {
        assertThat(Bitmap.of(4).with(4)).isEqualTo(Bitmap.of(4));
    }

    @Test
    void hasIsFalseForAbsentOutOfRangeAndFlagBit() {
        Bitmap bitmap = Bitmap.of(65);

        assertThat(bitmap.has(2)).isFalse();
        assertThat(bitmap.has(65)).isTrue();
        assertThat(bitmap.has(1)).isFalse();
        assertThat(bitmap.has(0)).isFalse();
        assertThat(bitmap.has(129)).isFalse();
        assertThat(bitmap.has(-5)).isFalse();
    }

    @Test
    void fieldsAreAscendingAndNeverContainOne() {
        Bitmap bitmap = Bitmap.of(128, 70, 11, 2, 64, 65, 7);

        assertThat(bitmap.fields()).containsExactly(2, 7, 11, 64, 65, 70, 128);
    }

    @Test
    void hasSecondaryOnlyForFields65To128() {
        assertThat(Bitmap.of(2, 64).hasSecondary()).isFalse();
        assertThat(Bitmap.of(2, 65).hasSecondary()).isTrue();
        assertThat(Bitmap.of(90).hasSecondary()).isTrue();
    }

    @Test
    void fieldsReturnsACopy() {
        Bitmap bitmap = Bitmap.of(2, 3);
        int[] fields = bitmap.fields();
        fields[0] = 99;

        assertThat(bitmap.fields()).containsExactly(2, 3);
    }

    @Test
    void equalityIsByContentNotByInsertionOrder() {
        Bitmap a = Bitmap.of(2, 3, 70);
        Bitmap b = Bitmap.of(70, 3, 2);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(Bitmap.of(2, 3));
        assertThat(a).isNotEqualTo(Bitmap.of(2, 3, 71));
        assertThat(a).isNotEqualTo("Bitmap[2, 3, 70]");
        assertThat(Bitmap.empty()).isEqualTo(Bitmap.of());
    }

    @Test
    void toStringListsFieldNumbers() {
        assertThat(Bitmap.of(2, 3, 4, 7, 11)).hasToString("Bitmap[2, 3, 4, 7, 11]");
        assertThat(Bitmap.empty()).hasToString("Bitmap[]");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 129, -1, 1000})
    void rejectsFieldsOutsideTwoTo128(int field) {
        assertThatThrownBy(() -> Bitmap.of(field))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field " + field);
        assertThatThrownBy(() -> Bitmap.empty().with(field))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field " + field);
    }

    @Test
    void rejectsFieldOneBecauseItIsManaged() {
        assertThatThrownBy(() -> Bitmap.of(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 1");
        assertThatThrownBy(() -> Bitmap.of(2).with(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field 1");
    }

    @Test
    void everyFieldSetsExactlyOneDataBit() {
        IntStream.rangeClosed(2, 128).forEach(field -> {
            Bitmap bitmap = Bitmap.of(field);
            assertThat(bitmap.fields()).containsExactly(field);
            assertThat(bitmap.has(field)).isTrue();
            long dataBits = Long.bitCount(bitmap.primary() & ~(1L << 63)) + Long.bitCount(bitmap.secondary());
            assertThat(dataBits).isEqualTo(1);
            assertThat(bitmap.primary() < 0).isEqualTo(field > 64);
        });
    }

    @Test
    void fromWordsIgnoresBitOneOfThePrimary() {
        Bitmap bitmap = Bitmap.fromWords(0xC000_0000_0000_0000L, 0L);

        assertThat(bitmap).isEqualTo(Bitmap.of(2));
        assertThat(bitmap.primary()).isEqualTo(0x4000_0000_0000_0000L);
    }
}
