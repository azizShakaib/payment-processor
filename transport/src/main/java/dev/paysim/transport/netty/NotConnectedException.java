package dev.paysim.transport.netty;

/** {@link NettyFrameClient#send} was called while there is no live connection. Nothing was queued. */
public final class NotConnectedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public NotConnectedException(String message) {
        super(message);
    }
}
