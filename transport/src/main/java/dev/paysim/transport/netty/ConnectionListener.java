package dev.paysim.transport.netty;

import dev.paysim.transport.FramingException;

/**
 * Optional connection life-cycle callbacks. All default to no-ops.
 *
 * <p>Same threading as {@link FrameHandler}: dispatched to the executor, on the
 * same per-connection serial queue as frames, so for one connection
 * {@code onConnected} runs before any frame and {@code onDisconnected} after
 * the last one. They are never called on an event-loop thread.
 */
public interface ConnectionListener {

    default void onConnected(FrameConnection connection) {
    }

    default void onDisconnected(FrameConnection connection) {
    }

    /** No data was read (READER) or written (WRITER) for the configured idle time. */
    default void onIdle(FrameConnection connection, IdleKind kind) {
    }

    /**
     * The stream broke the framing contract (bad header, oversize frame, or a
     * close mid-frame). The transport closes the connection after this.
     */
    default void onFramingError(FrameConnection connection, FramingException error) {
    }
}
