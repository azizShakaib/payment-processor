package dev.paysim.iso8583;

import dev.paysim.common.Hex;
import dev.paysim.common.Pan;
import dev.paysim.iso8583.spec.MessageSpec;
import java.util.Objects;

/**
 * Human-readable rendering of an {@link IsoMessage} for logs and debugging.
 * Every value passes through a {@link MaskRule}, so card numbers, track data,
 * PIN blocks and chip data never reach the output.
 *
 * <pre>
 * MTI    0100  AUTHORIZATION REQUEST from ACQUIRER
 * BITMAP 723C048020C09000
 * [002]  Primary account number ......... 411111******1111
 * [003]  Processing code ................ "000000"
 * </pre>
 *
 * <p>Layout: the label (field name, a space, dots) is padded to
 * {@value #LABEL_WIDTH} characters, which fits the longest name in
 * {@code PAYSIM_ASCII_87} (35 characters, "Network management information
 * code") plus a space and two dots. A longer name in another spec still prints,
 * just without alignment. Lines are joined with a newline character, with no
 * trailing newline. The MTI description is upper case: class, function,
 * "from" origin.
 */
public final class IsoPrinter {

    private static final int LABEL_WIDTH = 38;

    private IsoPrinter() {
    }

    /** Formats with {@link MaskingRules#ISO87_DEFAULT}. */
    public static String format(IsoMessage message) {
        return format(message, MaskingRules.ISO87_DEFAULT);
    }

    public static String format(IsoMessage message, MaskingRules rules) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        MessageSpec spec = message.spec();
        Mti mti = message.mti();
        Bitmap bitmap = message.bitmap();
        StringBuilder out = new StringBuilder();
        out.append("MTI    ").append(mti).append("  ").append(describe(mti)).append('\n');
        out.append("BITMAP ").append(String.format("%016X", bitmap.primary()));
        if (bitmap.hasSecondary()) {
            out.append(' ').append(String.format("%016X", bitmap.secondary()));
        }
        for (int field : message.fields()) {
            String name = spec.field(field).name();
            out.append('\n').append(String.format("[%03d]  ", field)).append(name).append(' ')
                    .append(".".repeat(Math.max(1, LABEL_WIDTH - name.length() - 1))).append(' ')
                    .append(render(message.get(field).orElseThrow(), rules.ruleFor(field)));
        }
        return out.toString();
    }

    /**
     * For logging a raw frame you did not build yourself. Parses it and
     * prints the masked message; if it does not parse, reports only the size
     * and the parser's message (field number, offset and reason, never
     * content). It never falls back to hex: a raw frame may contain a PAN.
     * {@code Hex.dump} of a frame is for hand-built test messages only.
     */
    public static String safeDump(MessageSpec spec, byte[] frame) {
        if (frame == null) {
            return "unparseable frame: null";
        }
        try {
            return format(IsoPacker.unpack(spec, frame));
        } catch (IsoParseException e) {
            return "unparseable frame: " + frame.length + " bytes; " + e.getMessage();
        } catch (RuntimeException e) {
            // The message of an unexpected exception is not known to be free of content.
            return "unparseable frame: " + frame.length + " bytes; " + e.getClass().getSimpleName();
        }
    }

    private static String describe(Mti mti) {
        String origin = mti.origin().base().name().replace('_', ' ');
        return (mti.messageClass().name() + " " + mti.function().name()).replace('_', ' ')
                + " from " + origin + (mti.isRepeat() ? " (repeat)" : "");
    }

    private static String render(FieldValue value, MaskRule rule) {
        return switch (rule) {
            case CLEAR -> value instanceof FieldValue.Text text
                    ? "\"" + text.value() + "\"" : hex(value);
            case MAC -> value instanceof FieldValue.Text text ? text.value() : hex(value);
            case PAN -> value instanceof FieldValue.Text text
                    ? Pan.maskLenient(text.value()) : hidden(value, "PAN");
            case TRACK2 -> value instanceof FieldValue.Text text
                    ? track2(text.value()) : hidden(value, "track 2");
            case TRACK1 -> hidden(value, "track 1");
            case PIN_BLOCK -> hidden(value, "PIN block");
            case ICC -> hidden(value, "ICC data");
        };
    }

    private static String hex(FieldValue value) {
        return Hex.encode(((FieldValue.Binary) value).value());
    }

    private static String hidden(FieldValue value, String what) {
        return "[" + size(value) + ", " + what + " hidden]";
    }

    private static String size(FieldValue value) {
        return value instanceof FieldValue.Text text
                ? text.value().length() + " chars"
                : ((FieldValue.Binary) value).value().length + " bytes";
    }

    private static String track2(String track) {
        int separator = -1;
        for (int i = 0; i < track.length() && separator < 0; i++) {
            if (track.charAt(i) == '=' || track.charAt(i) == 'D') {
                separator = i;
            }
        }
        if (separator < 0) {
            return "[" + track.length() + " chars]";
        }
        return Pan.maskLenient(track.substring(0, separator)) + "="
                + "*".repeat(track.length() - separator - 1) + " (" + track.length() + " chars)";
    }
}
