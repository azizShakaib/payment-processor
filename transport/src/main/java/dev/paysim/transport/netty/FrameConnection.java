package dev.paysim.transport.netty;

import io.netty.channel.Channel;
import io.netty.handler.codec.EncoderException;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * One live TCP link, as seen by application code. Wraps a Netty channel
 * without exposing Netty types. All methods are thread-safe.
 */
public final class FrameConnection {

    private final Channel channel;

    FrameConnection(Channel channel) {
        this.channel = channel;
    }

    /**
     * Frames and sends one payload. The future completes when the bytes have
     * been handed to the operating system, or fails (closed channel, payload
     * above the max frame length). Do not modify the array after calling.
     */
    public CompletableFuture<Void> send(byte[] frame) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        channel.writeAndFlush(frame).addListener(f -> {
            if (f.isSuccess()) {
                result.complete(null);
            } else {
                Throwable cause = f.cause();
                if (cause instanceof EncoderException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    public boolean isActive() {
        return channel.isActive();
    }

    public SocketAddress remote() {
        return channel.remoteAddress();
    }

    /** Closes the link. Asynchronous; {@code onDisconnected} follows. */
    public void close() {
        channel.close();
    }

    @Override
    public String toString() {
        return "FrameConnection[" + channel.remoteAddress() + "]";
    }
}
