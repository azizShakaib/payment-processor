package dev.paysim.iso8583.codec;

import dev.paysim.common.Hex;
import dev.paysim.iso8583.FieldValue;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Shared helpers for the field codec tests. */
final class CodecSupport {

    /** Non-zero start so tests prove offsets are absolute. */
    static final int START = 20;
    static final int FIELD = 7;

    private CodecSupport() {
    }

    static FieldSpec spec(FieldType type, LengthKind kind, int max, Encoding encoding) {
        return FieldSpec.of(FIELD, "test field", type, kind, max, encoding);
    }

    static byte[] pack(FieldSpec spec, FieldValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FieldCodec.pack(spec, value, out);
        return out.toByteArray();
    }

    static String packHex(FieldSpec spec, FieldValue value) {
        return Hex.encode(pack(spec, value));
    }

    /** Places {@code wire} at {@link #START} in a buffer and unpacks from there. */
    static Unpacked unpackAt(FieldSpec spec, String wireHex) {
        return FieldCodec.unpack(spec, buffer(wireHex), START);
    }

    static byte[] buffer(String wireHex) {
        byte[] wire = Hex.decode(wireHex);
        byte[] buffer = new byte[START + wire.length];
        Arrays.fill(buffer, (byte) 0xEE);
        System.arraycopy(wire, 0, buffer, START, wire.length);
        return buffer;
    }

    static String hexOf(String ascii) {
        return Hex.encode(ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
