package dev.paysim.transport.netty;

import dev.paysim.transport.FramingException;
import dev.paysim.transport.LengthHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Turns the TCP byte stream into whole frames, one {@code byte[]} per frame.
 *
 * <p>Same wire rules as the blocking {@link dev.paysim.transport.FrameCodec}
 * (both use {@link LengthHeader}), but written for Netty's model: bytes arrive
 * in arbitrary chunks, so the decoder looks at what it has and, if the frame
 * is not complete yet, returns without consuming anything and waits for more.
 *
 * <p>The payload is copied into its own exact-size array, so downstream code
 * never touches pooled {@link ByteBuf}s (and cannot leak or double-release one).
 *
 * <p>A length above {@code maxFrameLength} is rejected as soon as the header
 * is read, before any payload is buffered. After any framing error the decoder
 * discards all further input: the stream is out of sync and the only safe
 * reaction is to close the connection. Errors are fired down the pipeline as a
 * plain {@link FramingException} (not wrapped in a {@code DecoderException}).
 */
public final class LengthHeaderFrameDecoder extends ByteToMessageDecoder {

    private final LengthHeader header;
    private final int maxFrameLength;
    private boolean failed;

    public LengthHeaderFrameDecoder(LengthHeader header, int maxFrameLength) {
        if (maxFrameLength < 0 || maxFrameLength > header.maxLength()) {
            throw new IllegalArgumentException("maxFrameLength must be between 0 and " + header.maxLength());
        }
        this.header = header;
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (failed) {
            in.skipBytes(in.readableBytes());
            return;
        }
        while (true) {
            int headerSize = header.size();
            if (in.readableBytes() < headerSize) {
                return;
            }
            byte[] head = new byte[headerSize];
            in.getBytes(in.readerIndex(), head);

            int length;
            try {
                length = header.decode(head);
            } catch (FramingException e) {
                fail(ctx, in, e);
                return;
            }
            if (length > maxFrameLength) {
                fail(ctx, in, new FramingException(
                        "Frame of %d bytes exceeds max frame length %d".formatted(length, maxFrameLength)));
                return;
            }
            if (in.readableBytes() < headerSize + length) {
                return; // wait for the rest; nothing consumed
            }
            in.skipBytes(headerSize);
            byte[] payload = new byte[length];
            in.readBytes(payload);
            out.add(payload);
        }
    }

    @Override
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        decode(ctx, in, out);
        if (failed || !in.isReadable()) {
            return;
        }
        int have = in.readableBytes();
        String message;
        if (have < header.size()) {
            message = "connection closed mid-frame (%d of %d header bytes)".formatted(have, header.size());
        } else {
            byte[] head = new byte[header.size()];
            in.getBytes(in.readerIndex(), head);
            int total = header.size() + header.decode(head);
            message = "connection closed mid-frame (%d of %d bytes)".formatted(have, total);
        }
        fail(ctx, in, new FramingException(message));
    }

    private void fail(ChannelHandlerContext ctx, ByteBuf in, FramingException cause) {
        failed = true;
        in.skipBytes(in.readableBytes());
        ctx.fireExceptionCaught(cause);
    }
}
