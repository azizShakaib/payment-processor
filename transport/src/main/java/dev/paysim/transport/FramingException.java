package dev.paysim.transport;

/**
 * The byte stream broke the framing contract: a bad header, a frame that is
 * too large, or a connection that closed in the middle of a message. The only
 * safe reaction is to drop the connection, because the reader no longer knows
 * where the next message starts.
 */
public class FramingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FramingException(String message) {
        super(message);
    }
}
