package dev.paysim.iso8583;

import static dev.paysim.iso8583.PackerSupport.ASCII;
import static dev.paysim.iso8583.PackerSupport.COMPACT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.iso8583.spec.MessageSpec;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Slice unpacking reads only {@code [offset, offset + length)} and keeps absolute error offsets. */
class IsoPackerSliceTest {

    private static final int LEAD = 7;

    private static byte[] wire(MessageSpec spec) {
        return IsoPacker.pack(PackerSupport.sample0100(spec).build());
    }

    /** Junk before; the wire; the given extra bytes; then valid-looking ASCII digits. */
    private static byte[] around(byte[] wire, byte[] inside, int tail) {
        byte[] out = new byte[LEAD + wire.length + inside.length + tail];
        Arrays.fill(out, (byte) 0xEE);
        System.arraycopy(wire, 0, out, LEAD, wire.length);
        System.arraycopy(inside, 0, out, LEAD + wire.length, inside.length);
        Arrays.fill(out, LEAD + wire.length + inside.length, out.length, (byte) '7');
        return out;
    }

    @Test
    void sliceInTheMiddleEqualsStandaloneUnpack() {
        for (MessageSpec spec : new MessageSpec[] {ASCII, COMPACT}) {
            byte[] wire = wire(spec);
            byte[] buffer = around(wire, new byte[0], 50);
            IsoMessage sliced = IsoPacker.unpack(spec, buffer, LEAD, wire.length);
            IsoMessage alone = IsoPacker.unpack(spec, wire);
            assertThat(sliced).isEqualTo(alone);
            assertThat(IsoPacker.pack(sliced)).isEqualTo(wire);
        }
    }

    @Test
    void sliceCutOneByteShortFailsEvenThoughNextByteCompletesIt() {
        byte[] wire = wire(ASCII);
        byte[] buffer = around(wire, new byte[0], 50);
        // Field 52 is last: 16 hex characters. The 16th sits just past the slice.
        assertThatThrownBy(() -> IsoPacker.unpack(ASCII, buffer, LEAD, wire.length - 1))
                .isInstanceOfSatisfying(IsoParseException.class, e -> {
                    assertThat(e.field()).isEqualTo(52);
                    assertThat(e.offset()).isEqualTo(LEAD + wire.length - 16);
                    assertThat(e).hasMessage("field 52 at offset " + (LEAD + wire.length - 16)
                            + ": needs 16 bytes, only 15 left");
                });
    }

    @Test
    void sliceCutInsideTheMtiFails() {
        byte[] wire = wire(ASCII);
        byte[] buffer = around(wire, new byte[0], 50);
        assertThatThrownBy(() -> IsoPacker.unpack(ASCII, buffer, LEAD, 3))
                .isInstanceOfSatisfying(IsoParseException.class, e -> {
                    assertThat(e.field()).isEqualTo(0);
                    assertThat(e).hasMessage("field 0 at offset " + LEAD + ": truncated MTI, need 4 bytes, have 3");
                });
    }

    @Test
    void trailingBytesInsideTheSliceAreRejected() {
        byte[] wire = wire(ASCII);
        byte[] buffer = around(wire, new byte[] {'1', '2'}, 50);
        assertThatThrownBy(() -> IsoPacker.unpack(ASCII, buffer, LEAD, wire.length + 2))
                .isInstanceOfSatisfying(IsoParseException.class, e -> {
                    assertThat(e.field()).isEqualTo(52);
                    assertThat(e.offset()).isEqualTo(LEAD + wire.length);
                    assertThat(e.getMessage()).endsWith("2 trailing bytes after the last field");
                });
    }

    @Test
    void bytesAfterTheSliceAreIgnored() {
        byte[] wire = wire(ASCII);
        byte[] buffer = around(wire, new byte[0], 50);
        assertThat(IsoPacker.unpack(ASCII, buffer, LEAD, wire.length)).isEqualTo(IsoPacker.unpack(ASCII, wire));
    }

    @Test
    void sliceArgumentsAreValidated() {
        byte[] buffer = new byte[20];
        assertThatThrownBy(() -> IsoPacker.unpack(ASCII, buffer, -1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoPacker.unpack(ASCII, buffer, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoPacker.unpack(ASCII, buffer, 10, 11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoPacker.unpack(ASCII, buffer, 10, Integer.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoPacker.unpack(ASCII, buffer, Integer.MAX_VALUE, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
