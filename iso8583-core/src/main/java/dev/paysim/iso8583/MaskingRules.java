package dev.paysim.iso8583;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which {@link MaskRule} applies to which field number. Immutable; {@link #with}
 * returns a new instance. Fields without an entry are {@link MaskRule#CLEAR}.
 *
 * <p>The rules are keyed by field number, not by field name or value, so that
 * a dialect where field 48 carries a card number can be handled by one extra
 * {@code with(48, MaskRule.PAN)} and no printer change.
 */
public final class MaskingRules {

    /** The standard ISO 8583:1987 sensitive fields: 2, 35, 45, 52, 55, 64 and 128. */
    public static final MaskingRules ISO87_DEFAULT = new MaskingRules(Map.of(
            2, MaskRule.PAN,
            35, MaskRule.TRACK2,
            45, MaskRule.TRACK1,
            52, MaskRule.PIN_BLOCK,
            55, MaskRule.ICC,
            64, MaskRule.MAC,
            128, MaskRule.MAC));

    private final Map<Integer, MaskRule> rules;

    private MaskingRules(Map<Integer, MaskRule> rules) {
        this.rules = Map.copyOf(rules);
    }

    /** A copy of these rules with {@code field} set to {@code rule}. */
    public MaskingRules with(int field, MaskRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        if (field < 2 || field > 128) {
            throw new IllegalArgumentException("field " + field + " must be in 2..128");
        }
        Map<Integer, MaskRule> copy = new HashMap<>(rules);
        copy.put(field, rule);
        return new MaskingRules(copy);
    }

    public MaskRule ruleFor(int field) {
        return rules.getOrDefault(field, MaskRule.CLEAR);
    }
}
