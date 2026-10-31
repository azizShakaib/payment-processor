package dev.paysim.iso8583;

/**
 * Inbound bytes do not match the spec. Carries the field and the absolute
 * byte offset so a wire trace can be matched to the failure. The message never
 * contains field content.
 */
public class IsoParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int field;
    private final int offset;

    public IsoParseException(int field, int offset, String reason) {
        super("field " + field + " at offset " + offset + ": " + reason);
        this.field = field;
        this.offset = offset;
    }

    public int field() {
        return field;
    }

    public int offset() {
        return offset;
    }
}
