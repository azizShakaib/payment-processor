package dev.paysim.iso8583.codec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.paysim.iso8583.FieldValue;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import dev.paysim.iso8583.spec.Specs;
import java.io.ByteArrayOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every field of the shipped dialect, plus a BCD/BINARY variant of it, round-trips. */
class FieldCodecSpecSweepTest {

    private static final int START = 20;

    static Stream<Arguments> specs() {
        return Specs.PAYSIM_ASCII_87.fields().stream().flatMap(spec -> {
            FieldSpec compact = compact(spec);
            return Stream.of(Arguments.of(spec), Arguments.of(compact));
        }).distinct();
    }

    /** Same field with numeric and track data in BCD and binary data raw. */
    private static FieldSpec compact(FieldSpec spec) {
        Encoding encoding = switch (spec.type()) {
            case N, Z -> Encoding.BCD;
            case B -> Encoding.BINARY;
            default -> Encoding.ASCII;
        };
        return new FieldSpec(spec.number(), spec.name(), spec.type(), spec.lengthKind(), spec.maxLength(),
                encoding, spec.padding());
    }

    @ParameterizedTest
    @MethodSource("specs")
    void maxLengthValueRoundTrips(FieldSpec spec) {
        assertRoundTrip(spec, spec.maxLength());
    }

    @ParameterizedTest
    @MethodSource("specs")
    void shortVariableValueRoundTrips(FieldSpec spec) {
        if (spec.lengthKind() == LengthKind.FIXED) {
            return;
        }
        assertRoundTrip(spec, 1);
        assertRoundTrip(spec, 0);
    }

    private static void assertRoundTrip(FieldSpec spec, int length) {
        FieldValue value = valueOf(spec, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[START]);
        FieldCodec.pack(spec, value, out);
        byte[] buffer = out.toByteArray();

        Unpacked result = FieldCodec.unpack(spec, buffer, START);

        assertThat(result.value()).isEqualTo(value);
        assertThat(result.nextOffset()).isEqualTo(buffer.length);
    }

    private static FieldValue valueOf(FieldSpec spec, int length) {
        if (spec.type() == FieldType.B) {
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) (i * 37 + 11);
            }
            return FieldValue.binary(bytes);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(charFor(spec.type(), i));
        }
        return FieldValue.text(sb.toString());
    }

    private static char charFor(FieldType type, int i) {
        return switch (type) {
            case N -> (char) ('0' + (i + 1) % 10);
            case AN -> "AbC1dE2fG3".charAt(i % 10);
            case ANS -> (char) (0x20 + i % 95);
            case Z -> i == 4 ? '=' : (char) ('0' + (i + 1) % 10);
            case XN -> i == 0 ? 'C' : (char) ('0' + i % 10);
            case B -> throw new IllegalArgumentException("binary has no characters");
        };
    }
}
