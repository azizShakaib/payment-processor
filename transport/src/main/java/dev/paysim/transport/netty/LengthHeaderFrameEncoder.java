package dev.paysim.transport.netty;

import dev.paysim.transport.FramingException;
import dev.paysim.transport.LengthHeader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Writes {@code [length header][payload]} for each outgoing {@code byte[]}.
 * Header and payload go into one buffer, so they leave in one write, exactly
 * like {@link dev.paysim.transport.FrameCodec#write}. A payload above
 * {@code maxFrameLength} fails the write with a {@link FramingException}.
 */
public final class LengthHeaderFrameEncoder extends MessageToByteEncoder<byte[]> {

    private final LengthHeader header;
    private final int maxFrameLength;

    public LengthHeaderFrameEncoder(LengthHeader header, int maxFrameLength) {
        if (maxFrameLength < 0 || maxFrameLength > header.maxLength()) {
            throw new IllegalArgumentException("maxFrameLength must be between 0 and " + header.maxLength());
        }
        this.header = header;
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] payload, ByteBuf out) {
        if (payload.length > maxFrameLength) {
            throw new FramingException("Payload of %d bytes exceeds max frame length %d"
                    .formatted(payload.length, maxFrameLength));
        }
        out.writeBytes(header.encode(payload.length));
        out.writeBytes(payload);
    }
}
