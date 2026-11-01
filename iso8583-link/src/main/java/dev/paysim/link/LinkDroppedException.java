package dev.paysim.link;

/**
 * The link went down (or was closed) while a request was in flight. The request
 * may or may not have reached the far side: the outcome is unknown, which is
 * why Phase 3 follows it with a reversal.
 */
public final class LinkDroppedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public LinkDroppedException(String message) {
        super(message);
    }
}
