package dev.paysim.iso8583;

import static dev.paysim.iso8583.PackerSupport.ALL_MTIS;
import static dev.paysim.iso8583.PackerSupport.ASCII;
import static dev.paysim.iso8583.PackerSupport.COMPACT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.MessageSpec;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every field of the spec at maximum length, once per MTI, in both dialects. */
class IsoPackerSweepTest {

    static Stream<MessageSpec> specs() {
        return Stream.of(ASCII, PackerSupport.COMPACT);
    }

    @ParameterizedTest
    @MethodSource("specs")
    void everyFieldAtMaxLengthSurvivesARoundTrip(MessageSpec spec) {
        for (Mti mti : ALL_MTIS) {
            IsoMessage message = PackerSupport.fullMessage(spec, mti);
            assertThat(message.fields()).hasSize(spec.fields().size());

            byte[] wire = IsoPacker.pack(message);
            IsoMessage back = IsoPacker.unpack(spec, wire);

            assertThat(back).as("message %s", mti).isEqualTo(message);
            assertThat(IsoPacker.pack(back)).as("bytes %s", mti).isEqualTo(wire);
        }
    }

    @ParameterizedTest
    @MethodSource("specs")
    void everySingleFieldAloneSurvivesARoundTrip(MessageSpec spec) {
        for (FieldSpec f : spec.fields()) {
            IsoMessage message = PackerSupport.setAtMaxLength(
                    IsoMessage.builder(spec, Mti.NETWORK_REQUEST), f).build();

            byte[] wire = IsoPacker.pack(message);

            assertThat(IsoPacker.unpack(spec, wire)).as("field %d", f.number()).isEqualTo(message);
        }
    }

    @Test
    void messageWithNoFieldsIsMtiPlusEmptyBitmap() {
        IsoMessage empty = IsoMessage.builder(ASCII, Mti.NETWORK_REQUEST).build();

        byte[] wire = IsoPacker.pack(empty);

        assertThat(wire).hasSize(4 + 16);
        assertThat(IsoPacker.unpack(ASCII, wire)).isEqualTo(empty);
        assertThat(IsoPacker.pack(IsoMessage.builder(COMPACT, Mti.NETWORK_REQUEST).build())).hasSize(2 + 8);
    }

    @Test
    void packerUsesTheSpecOfTheMessageNotAGlobalOne() {
        IsoMessage message = PackerSupport.echo0800(COMPACT);

        assertThat(IsoPacker.unpack(COMPACT, IsoPacker.pack(message)).spec()).isSameAs(COMPACT);
    }

    // The trusted path: unpack must not need the validating builder to produce equal messages.

    @Test
    void trustedConstructionEqualsBuilderConstruction() {
        IsoMessage built = PackerSupport.sample0100(ASCII).build();
        FieldValue[] values = new FieldValue[129];
        for (int field : built.fields()) {
            values[field] = built.get(field).orElseThrow();
        }

        IsoMessage trusted = IsoMessage.ofTrusted(ASCII, Mti.AUTH_REQUEST, values);

        assertThat(trusted).isEqualTo(built).hasSameHashCodeAs(built);
        assertThat(trusted.toBuilder().build()).isEqualTo(built);
    }

    @Test
    void unpackedMessageIsAnIndependentCopyOfTheInputBuffer() {
        byte[] wire = IsoPacker.pack(PackerSupport.sample0100(PackerSupport.COMPACT).build());
        IsoMessage message = IsoPacker.unpack(PackerSupport.COMPACT, wire);

        java.util.Arrays.fill(wire, (byte) 0);

        assertThat(message.bytes(52).orElseThrow()).isEqualTo(PackerSupport.PIN_BLOCK);
        assertThat(message.pan()).contains("4111111111111111");
    }

    @Test
    void sliceOutsideTheBufferIsRejected() {
        byte[] buffer = new byte[10];

        for (int[] bad : List.of(new int[] {-1, 2}, new int[] {0, -1}, new int[] {8, 3}, new int[] {11, 0})) {
            assertThatThrownBy(() -> IsoPacker.unpack(ASCII, buffer, bad[0], bad[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
