package dev.paysim.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.common.Hex;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Codec behaviour against in-memory streams, no sockets involved. */
class FrameCodecTest {

    // The 0800 echo test from the Phase 0 lesson: MTI, two bitmaps, fields 7, 11 and 70.
    private static final byte[] ECHO_REQUEST =
            ("0800" + "8220000000000000" + "0400000000000000" + "1015042100" + "000001" + "301")
                    .getBytes(StandardCharsets.US_ASCII);

    @Test
    void binaryHeaderIsTwoBigEndianBytes() {
        assertThat(Hex.encode(LengthHeader.BINARY_2.encode(47))).isEqualTo("002F");
        assertThat(Hex.encode(LengthHeader.BINARY_2.encode(300))).isEqualTo("012C");
        assertThat(LengthHeader.BINARY_2.decode(Hex.decode("FFFF"))).isEqualTo(65_535);
    }

    @Test
    void asciiHeaderIsFourZeroPaddedDigits() {
        assertThat(new String(LengthHeader.ASCII_4.encode(47), StandardCharsets.US_ASCII)).isEqualTo("0047");
        assertThat(LengthHeader.ASCII_4.decode("1234".getBytes(StandardCharsets.US_ASCII))).isEqualTo(1234);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void roundTripsOneFrame(LengthHeader header) throws IOException {
        FrameCodec codec = new FrameCodec(header);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();

        codec.write(wire, ECHO_REQUEST);

        assertThat(wire.size()).isEqualTo(header.size() + ECHO_REQUEST.length);
        InputStream in = new ByteArrayInputStream(wire.toByteArray());
        assertThat(codec.read(in)).isEqualTo(ECHO_REQUEST);
        assertThat(codec.read(in)).as("clean end of stream").isNull();
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void splitsGluedFramesApart(LengthHeader header) throws IOException {
        FrameCodec codec = new FrameCodec(header);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        codec.write(wire, bytes("first"));
        codec.write(wire, bytes("second message"));
        codec.write(wire, new byte[0]);

        InputStream in = new ByteArrayInputStream(wire.toByteArray());

        assertThat(codec.read(in)).isEqualTo(bytes("first"));
        assertThat(codec.read(in)).isEqualTo(bytes("second message"));
        assertThat(codec.read(in)).as("empty frame is legal").isEmpty();
        assertThat(codec.read(in)).isNull();
    }

    @Test
    void reassemblesAFrameDeliveredOneByteAtATime() throws IOException {
        FrameCodec codec = new FrameCodec(LengthHeader.BINARY_2);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        codec.write(wire, ECHO_REQUEST);

        // Every read() returns at most one byte: the worst case TCP is allowed to give us.
        InputStream trickle = new OneByteAtATime(wire.toByteArray());

        assertThat(codec.read(trickle)).isEqualTo(ECHO_REQUEST);
    }

    @Test
    void rejectsConnectionClosedInsideHeader() {
        FrameCodec codec = new FrameCodec(LengthHeader.ASCII_4);
        InputStream in = new ByteArrayInputStream(bytes("00"));

        assertThatThrownBy(() -> codec.read(in))
                .isInstanceOf(FramingException.class)
                .hasMessageContaining("length header");
    }

    @Test
    void rejectsConnectionClosedInsidePayload() {
        FrameCodec codec = new FrameCodec(LengthHeader.ASCII_4);
        InputStream in = new ByteArrayInputStream(bytes("0010abc"));

        assertThatThrownBy(() -> codec.read(in))
                .isInstanceOf(FramingException.class)
                .hasMessageContaining("10-byte payload");
    }

    @Test
    void rejectsNonDigitAsciiHeader() {
        FrameCodec codec = new FrameCodec(LengthHeader.ASCII_4);
        InputStream in = new ByteArrayInputStream(bytes("00A7hello"));

        assertThatThrownBy(() -> codec.read(in))
                .isInstanceOf(FramingException.class)
                .hasMessageContaining("0x41");
    }

    @Test
    void rejectsFramesAboveTheConfiguredMaximum() {
        FrameCodec codec = new FrameCodec(LengthHeader.BINARY_2, 1024);
        InputStream in = new ByteArrayInputStream(Hex.decode("FFFF"));

        assertThatThrownBy(() -> codec.read(in))
                .isInstanceOf(FramingException.class)
                .hasMessageContaining("65535");
        assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(), new byte[1025]))
                .isInstanceOf(FramingException.class);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** An InputStream that never hands out more than one byte per read call. */
    private static final class OneByteAtATime extends InputStream {
        private final byte[] data;
        private int pos;

        OneByteAtATime(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            if (len == 0) {
                return 0;
            }
            int b = read();
            if (b == -1) {
                return -1;
            }
            buf[off] = (byte) b;
            return 1;
        }
    }
}
