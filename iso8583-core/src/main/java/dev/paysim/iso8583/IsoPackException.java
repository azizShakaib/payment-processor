package dev.paysim.iso8583;

/**
 * A value cannot be encoded under its field spec. The message never contains
 * field content.
 */
public class IsoPackException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int field;

    public IsoPackException(int field, String reason) {
        super("field " + field + ": " + reason);
        this.field = field;
    }

    public int field() {
        return field;
    }
}
