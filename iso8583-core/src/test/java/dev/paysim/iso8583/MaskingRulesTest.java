package dev.paysim.iso8583;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MaskingRulesTest {

    @Test
    void defaultRulesCoverTheSensitiveIso87Fields() {
        MaskingRules rules = MaskingRules.ISO87_DEFAULT;
        assertThat(rules.ruleFor(2)).isEqualTo(MaskRule.PAN);
        assertThat(rules.ruleFor(35)).isEqualTo(MaskRule.TRACK2);
        assertThat(rules.ruleFor(45)).isEqualTo(MaskRule.TRACK1);
        assertThat(rules.ruleFor(52)).isEqualTo(MaskRule.PIN_BLOCK);
        assertThat(rules.ruleFor(55)).isEqualTo(MaskRule.ICC);
        assertThat(rules.ruleFor(64)).isEqualTo(MaskRule.MAC);
        assertThat(rules.ruleFor(128)).isEqualTo(MaskRule.MAC);
    }

    @Test
    void otherFieldsAreClear() {
        assertThat(MaskingRules.ISO87_DEFAULT.ruleFor(4)).isEqualTo(MaskRule.CLEAR);
        assertThat(MaskingRules.ISO87_DEFAULT.ruleFor(41)).isEqualTo(MaskRule.CLEAR);
        assertThat(MaskingRules.ISO87_DEFAULT.ruleFor(99)).isEqualTo(MaskRule.CLEAR);
    }

    @Test
    void withReturnsANewInstanceAndLeavesTheOriginalAlone() {
        MaskingRules changed = MaskingRules.ISO87_DEFAULT.with(14, MaskRule.PAN).with(64, MaskRule.CLEAR);
        assertThat(changed.ruleFor(14)).isEqualTo(MaskRule.PAN);
        assertThat(changed.ruleFor(64)).isEqualTo(MaskRule.CLEAR);
        assertThat(changed.ruleFor(2)).isEqualTo(MaskRule.PAN);
        assertThat(MaskingRules.ISO87_DEFAULT.ruleFor(14)).isEqualTo(MaskRule.CLEAR);
        assertThat(MaskingRules.ISO87_DEFAULT.ruleFor(64)).isEqualTo(MaskRule.MAC);
    }

    @Test
    void withValidatesItsArguments() {
        assertThatThrownBy(() -> MaskingRules.ISO87_DEFAULT.with(1, MaskRule.PAN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaskingRules.ISO87_DEFAULT.with(129, MaskRule.PAN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MaskingRules.ISO87_DEFAULT.with(14, null))
                .isInstanceOf(NullPointerException.class);
    }
}
