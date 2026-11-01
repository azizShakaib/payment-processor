package dev.paysim.link;

/** A request was refused because the link is not signed on. Nothing was sent. */
public final class LinkNotReadyException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public LinkNotReadyException(String message) {
        super(message);
    }
}
