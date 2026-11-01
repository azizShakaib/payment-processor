package dev.paysim.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.transport.FrameCodec;
import dev.paysim.transport.FramingException;
import dev.paysim.transport.LengthHeader;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FrameCodecNettyTest {

    private static final byte[] ECHO = ("0800" + "8220000000000000" + "0400000000000000" + "1015042100"
            + "000001" + "301").getBytes(StandardCharsets.US_ASCII);

    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    /** Remembers FramingExceptions that reach the end of the pipeline. */
    private static final class ErrorCatcher extends ChannelInboundHandlerAdapter {
        final List<Throwable> errors = new ArrayList<>();

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            errors.add(cause);
        }
    }

    private ErrorCatcher errors;

    private EmbeddedChannel decoder(LengthHeader header, int max) {
        errors = new ErrorCatcher();
        channel = new EmbeddedChannel(new LengthHeaderFrameDecoder(header, max), errors);
        return channel;
    }

    private static byte[] wire(LengthHeader header, byte[]... payloads) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameCodec codec = new FrameCodec(header);
        for (byte[] p : payloads) {
            codec.write(out, p);
        }
        return out.toByteArray();
    }

    private static ByteBuf buf(byte[] bytes, int from, int to) {
        return Unpooled.wrappedBuffer(bytes, from, to - from);
    }

    private byte[] readFrame() {
        return channel.readInbound();
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void wholeFrame(LengthHeader header) throws IOException {
        decoder(header, header.maxLength());
        channel.writeInbound(Unpooled.wrappedBuffer(wire(header, ECHO)));
        assertThat(readFrame()).isEqualTo(ECHO);
        assertThat((Object) readFrame()).isNull();
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void splitInsideHeader(LengthHeader header) throws IOException {
        byte[] w = wire(header, ECHO);
        decoder(header, header.maxLength());
        channel.writeInbound(buf(w, 0, 1));
        assertThat((Object) readFrame()).isNull();
        channel.writeInbound(buf(w, 1, w.length));
        assertThat(readFrame()).isEqualTo(ECHO);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void splitInsidePayload(LengthHeader header) throws IOException {
        byte[] w = wire(header, ECHO);
        int cut = header.size() + 20;
        decoder(header, header.maxLength());
        channel.writeInbound(buf(w, 0, cut));
        assertThat((Object) readFrame()).isNull();
        channel.writeInbound(buf(w, cut, w.length));
        assertThat(readFrame()).isEqualTo(ECHO);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void twoFramesGlued(LengthHeader header) throws IOException {
        byte[] other = "second".getBytes(StandardCharsets.US_ASCII);
        decoder(header, header.maxLength());
        channel.writeInbound(Unpooled.wrappedBuffer(wire(header, ECHO, other)));
        assertThat(readFrame()).isEqualTo(ECHO);
        assertThat(readFrame()).isEqualTo(other);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void oneByteAtATime(LengthHeader header) throws IOException {
        byte[] other = "x".getBytes(StandardCharsets.US_ASCII);
        byte[] w = wire(header, ECHO, other);
        decoder(header, header.maxLength());
        for (int i = 0; i < w.length; i++) {
            channel.writeInbound(buf(w, i, i + 1));
        }
        assertThat(readFrame()).isEqualTo(ECHO);
        assertThat(readFrame()).isEqualTo(other);
        assertThat((Object) readFrame()).isNull();
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void zeroLengthFrame(LengthHeader header) throws IOException {
        decoder(header, header.maxLength());
        channel.writeInbound(Unpooled.wrappedBuffer(wire(header, new byte[0])));
        assertThat(readFrame()).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void badAsciiHeaderIsAFramingError() {
        decoder(LengthHeader.ASCII_4, 9_999);
        channel.writeInbound(Unpooled.wrappedBuffer("00x5hello".getBytes(StandardCharsets.US_ASCII)));
        assertThat(errors.errors).singleElement().isInstanceOf(FramingException.class);
        assertThat((Object) readFrame()).isNull();
        // The stream is out of sync: later bytes are discarded, not decoded.
        channel.writeInbound(Unpooled.wrappedBuffer("0001a".getBytes(StandardCharsets.US_ASCII)));
        assertThat((Object) readFrame()).isNull();
        assertThat(errors.errors).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void oversizeLengthRejectedBeforePayloadArrives(LengthHeader header) {
        decoder(header, 100);
        // Only the header of a 101-byte frame; no payload byte has arrived.
        channel.writeInbound(Unpooled.wrappedBuffer(header.encode(101)));
        assertThat(errors.errors).singleElement().isInstanceOf(FramingException.class);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void frameExactlyAtMaxIsAccepted(LengthHeader header) throws IOException {
        decoder(header, ECHO.length);
        channel.writeInbound(Unpooled.wrappedBuffer(wire(header, ECHO)));
        assertThat(readFrame()).isEqualTo(ECHO);
        assertThat(errors.errors).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void partialPayloadAtCloseIsReported(LengthHeader header) throws IOException {
        byte[] w = wire(header, ECHO);
        decoder(header, header.maxLength());
        channel.writeInbound(buf(w, 0, header.size() + 10));
        channel.close();
        assertThat(errors.errors).singleElement().isInstanceOf(FramingException.class)
                .extracting(Throwable::getMessage)
                .asString()
                .contains("mid-frame")
                .contains((header.size() + 10) + " of " + (header.size() + ECHO.length) + " bytes");
    }

    @org.junit.jupiter.api.Test
    void partialHeaderAtCloseIsReported() {
        decoder(LengthHeader.ASCII_4, 9_999);
        channel.writeInbound(Unpooled.wrappedBuffer("00".getBytes(StandardCharsets.US_ASCII)));
        channel.close();
        assertThat(errors.errors).singleElement().isInstanceOf(FramingException.class)
                .extracting(Throwable::getMessage).asString().contains("mid-frame").contains("2 of 4");
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void cleanCloseBetweenFramesIsNotAnError(LengthHeader header) throws IOException {
        decoder(header, header.maxLength());
        channel.writeInbound(Unpooled.wrappedBuffer(wire(header, ECHO)));
        channel.close();
        assertThat(errors.errors).isEmpty();
        assertThat(readFrame()).isEqualTo(ECHO);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void encoderMatchesBlockingFrameCodecByteForByte(LengthHeader header) throws IOException {
        channel = new EmbeddedChannel(new LengthHeaderFrameEncoder(header, header.maxLength()));
        channel.writeOutbound((Object) ECHO);
        ByteBuf out = channel.readOutbound();
        byte[] netty = new byte[out.readableBytes()];
        out.readBytes(netty);
        out.release();
        assertThat(netty).isEqualTo(wire(header, ECHO));
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void encoderOutputDecodesBack(LengthHeader header) {
        channel = new EmbeddedChannel(new LengthHeaderFrameEncoder(header, header.maxLength()),
                new LengthHeaderFrameDecoder(header, header.maxLength()));
        channel.writeOutbound((Object) ECHO);
        ByteBuf out = channel.readOutbound();
        channel.writeInbound(out);
        assertThat(readFrame()).isEqualTo(ECHO);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void encoderRejectsOversizePayload(LengthHeader header) {
        channel = new EmbeddedChannel(new LengthHeaderFrameEncoder(header, 10));
        assertThatThrownBy(() -> channel.writeOutbound((Object) new byte[11]))
                .hasRootCauseInstanceOf(FramingException.class);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void encoderHandlesEmptyPayload(LengthHeader header) throws IOException {
        channel = new EmbeddedChannel(new LengthHeaderFrameEncoder(header, header.maxLength()));
        channel.writeOutbound((Object) new byte[0]);
        ByteBuf out = channel.readOutbound();
        byte[] netty = new byte[out.readableBytes()];
        out.readBytes(netty);
        out.release();
        assertThat(netty).isEqualTo(header.encode(0));
    }
}
