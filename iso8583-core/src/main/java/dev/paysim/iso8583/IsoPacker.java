package dev.paysim.iso8583;

import dev.paysim.iso8583.codec.BitmapCodec;
import dev.paysim.iso8583.codec.FieldCodec;
import dev.paysim.iso8583.codec.MtiCodec;
import dev.paysim.iso8583.codec.Unpacked;
import dev.paysim.iso8583.codec.UnpackedBitmap;
import dev.paysim.iso8583.spec.MessageSpec;
import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * Turns a whole {@link IsoMessage} into bytes and back: MTI, bitmap, then each
 * present data element in ascending order, all encoded as the
 * {@link MessageSpec} says. Static and stateless, so it is safe from any
 * thread. Lives next to {@link IsoMessage} (not in the codec package) so
 * unpacking can use the message's non-public trusted factory.
 *
 * <p>Parsing is sequential: the bitmap says which fields are present but not
 * how long each one is, so every field is read with the length rules of its
 * spec entry. A wrong definition for one field shifts every field after it.
 * Errors name the field (0 is the MTI, 1 the bitmap) and the absolute byte
 * offset, never content.
 */
public final class IsoPacker {

    private static final int MAX_FIELD = 128;
    private static final int INITIAL_CAPACITY = 256;

    private IsoPacker() {
    }

    /**
     * Encodes the message. The result is built in a local buffer, so a failure
     * leaves nothing half-written.
     *
     * @throws IsoPackException if a field cannot be encoded (values are
     *         checked when set, so this signals a spec/message mismatch)
     */
    public static byte[] pack(IsoMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        MessageSpec spec = message.spec();
        ByteArrayOutputStream out = new ByteArrayOutputStream(INITIAL_CAPACITY);
        MtiCodec.pack(message.mti(), spec.mtiEncoding(), out);
        BitmapCodec.pack(message.bitmap(), spec.bitmapEncoding(), out);
        for (int field : message.fields()) {
            FieldCodec.pack(spec.field(field), message.get(field).orElseThrow(), out);
        }
        return out.toByteArray();
    }

    /** Parses the whole buffer as one message. */
    public static IsoMessage unpack(MessageSpec spec, byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        return unpack(spec, buffer, 0, buffer.length);
    }

    /**
     * Parses exactly {@code buffer[offset, offset + length)} as one message.
     * Error offsets are absolute positions in {@code buffer}. A slice that
     * ends before the buffer does costs nothing extra: the end is passed to the
     * codecs as an exclusive limit, so they cannot read past the slice and
     * offsets stay absolute.
     *
     * @throws IsoParseException if the bytes do not match the spec, including
     *         a field set in the bitmap but not defined in the spec and bytes
     *         left over after the last field
     * @throws IllegalArgumentException if the slice lies outside the buffer
     */
    public static IsoMessage unpack(MessageSpec spec, byte[] buffer, int offset, int length) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (offset < 0 || length < 0 || length > buffer.length - offset) {
            throw new IllegalArgumentException("slice offset " + offset + " length " + length
                    + " is outside a buffer of " + buffer.length + " bytes");
        }
        int end = offset + length;

        MtiCodec.UnpackedMti mti = MtiCodec.unpack(spec.mtiEncoding(), buffer, offset, end);
        UnpackedBitmap bitmap = BitmapCodec.unpack(spec.bitmapEncoding(), buffer, mti.nextOffset(), end);
        FieldValue[] values = new FieldValue[MAX_FIELD + 1];
        int position = bitmap.nextOffset();
        int last = 1;
        for (int field : bitmap.bitmap().fields()) {
            if (!spec.has(field)) {
                throw new IsoParseException(field, position,
                        "present in bitmap but not defined in spec " + spec.name());
            }
            Unpacked unpacked = FieldCodec.unpack(spec.field(field), buffer, position, end);
            values[field] = unpacked.value();
            position = unpacked.nextOffset();
            last = field;
        }
        if (position != end) {
            throw new IsoParseException(last, position, (end - position) + " trailing bytes after the last field");
        }
        return IsoMessage.ofTrusted(spec, mti.mti(), values);
    }
}
