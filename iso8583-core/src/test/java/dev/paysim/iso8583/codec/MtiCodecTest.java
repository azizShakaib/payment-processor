package dev.paysim.iso8583.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.common.Hex;
import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.Mti;
import dev.paysim.iso8583.spec.Encoding;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class MtiCodecTest {

    private static String pack(Mti mti, Encoding encoding) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MtiCodec.pack(mti, encoding, out);
        return Hex.encode(out.toByteArray());
    }

    @Test
    void asciiIsFourBytes() {
        assertThat(pack(Mti.AUTH_REQUEST, Encoding.ASCII)).isEqualTo("30313030");
        assertThat(MtiCodec.wireLength(Encoding.ASCII)).isEqualTo(4);
    }

    @Test
    void bcdIsTwoBytes() {
        assertThat(pack(Mti.AUTH_REQUEST, Encoding.BCD)).isEqualTo("0100");
        assertThat(pack(Mti.NETWORK_RESPONSE, Encoding.BCD)).isEqualTo("0810");
        assertThat(MtiCodec.wireLength(Encoding.BCD)).isEqualTo(2);
    }

    @Test
    void roundTripsAtANonZeroOffset() {
        for (Encoding encoding : new Encoding[] {Encoding.ASCII, Encoding.BCD}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(new byte[3]);
            MtiCodec.pack(Mti.REVERSAL_ADVICE, encoding, out);
            byte[] buffer = out.toByteArray();

            MtiCodec.UnpackedMti result = MtiCodec.unpack(encoding, buffer, 3);

            assertThat(result.mti()).isEqualTo(Mti.REVERSAL_ADVICE);
            assertThat(result.nextOffset()).isEqualTo(buffer.length);
        }
    }

    @Test
    void truncatedReportsHowManyBytesWereLeft() {
        assertThatThrownBy(() -> MtiCodec.unpack(Encoding.ASCII, new byte[12], 10))
                .isInstanceOf(IsoParseException.class)
                .hasMessage("field 0 at offset 10: truncated MTI, need 4 bytes, have 2");
        assertThatThrownBy(() -> MtiCodec.unpack(Encoding.ASCII, new byte[3], 10))
                .hasMessage("field 0 at offset 10: truncated MTI, need 4 bytes, have 0");
    }

    @Test
    void highBitBytesAreNonDigits() {
        byte[] buffer = {'0', (byte) 0xB1, '0', '0'};

        assertThatThrownBy(() -> MtiCodec.unpack(Encoding.ASCII, buffer, 0))
                .hasMessage("field 0 at offset 1: non-digit in MTI at index 1");
    }

    @Test
    void bcdNibbleAboveNineIsRejectedAtTheByteOffset() {
        byte[] buffer = {0x01, 0x0A};

        assertThatThrownBy(() -> MtiCodec.unpack(Encoding.BCD, buffer, 0))
                .hasMessage("field 0 at offset 1: invalid BCD nibble in MTI at index 3");
    }

    @Test
    void digitsThatMtiRejectsBecomeParseErrors() {
        assertThatThrownBy(() -> MtiCodec.unpack(Encoding.ASCII, new byte[] {'0', '9', '0', '0'}, 0))
                .isInstanceOf(IsoParseException.class)
                .hasMessageStartingWith("field 0 at offset 0: MTI rejected: ");
        assertThatThrownBy(() -> MtiCodec.unpack(Encoding.BCD, new byte[] {0x11, 0x00}, 0))
                .hasMessageStartingWith("field 0 at offset 0: MTI rejected: ");
    }

    @Test
    void binaryEncodingIsNotAnMtiEncoding() {
        assertThatThrownBy(() -> MtiCodec.pack(Mti.AUTH_REQUEST, Encoding.BINARY, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MtiCodec.unpack(Encoding.BINARY, new byte[4], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
