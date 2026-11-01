package dev.paysim.transport.netty;

/**
 * Application code that receives whole frames.
 *
 * <p>Threading: never called on a Netty event-loop thread. Frames of one
 * connection are delivered one at a time, in arrival order, on a thread from
 * the configured executor (virtual threads by default), so blocking here
 * (database call, waiting for a reply) is fine and delays only that
 * connection. Different connections run in parallel. The array is owned by the
 * handler; it is not reused.
 */
@FunctionalInterface
public interface FrameHandler {

    void onFrame(FrameConnection connection, byte[] frame);
}
