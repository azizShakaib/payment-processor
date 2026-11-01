package dev.paysim.iso8583.codec;

import static dev.paysim.iso8583.codec.CodecSupport.spec;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.common.Hex;
import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.LengthKind;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The 4-argument readers stop at an exclusive limit: bytes at or past it are
 * never read, even when they would complete the value. Every buffer here keeps
 * valid-looking bytes after the limit to prove it.
 */
class LimitedReadTest {

    private static final int START = 3;

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Wire bytes at START, then valid-looking digits after them. */
    private static byte[] buffer(byte[] wire) {
        byte[] out = new byte[START + wire.length + 40];
        Arrays.fill(out, (byte) '7');
        System.arraycopy(wire, 0, out, START, wire.length);
        return out;
    }

    private static void assertParse(Runnable action, int field, int offset, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IsoParseException.class, e -> {
                    assertThat(e.field()).isEqualTo(field);
                    assertThat(e.offset()).isEqualTo(offset);
                    assertThat(e).hasMessage("field " + field + " at offset " + offset + ": " + message);
                });
    }

    // ---- FieldCodec ----

    @Test
    void fixedAsciiTruncatedByLimit() {
        FieldSpec s = spec(FieldType.AN, LengthKind.FIXED, 6, Encoding.ASCII);
        byte[] b = buffer(ascii("ABCDEF"));
        assertParse(() -> FieldCodec.unpack(s, b, START, START + 4), 7, START, "needs 6 bytes, only 4 left");
    }

    @Test
    void fieldEndingExactlyAtLimitIsRead() {
        FieldSpec s = spec(FieldType.AN, LengthKind.FIXED, 6, Encoding.ASCII);
        byte[] b = buffer(ascii("ABCDEF"));
        Unpacked u = FieldCodec.unpack(s, b, START, START + 6);
        assertThat(u.nextOffset()).isEqualTo(START + 6);
    }

    @Test
    void llvarPrefixFitsButDataCrossesLimit() {
        FieldSpec s = spec(FieldType.AN, LengthKind.LLVAR, 20, Encoding.ASCII);
        byte[] b = buffer(ascii("05ABCDE"));
        assertParse(() -> FieldCodec.unpack(s, b, START, START + 5), 7, START + 2, "needs 5 bytes, only 3 left");
    }

    @Test
    void llvarPrefixItselfCrossesLimit() {
        FieldSpec s = spec(FieldType.AN, LengthKind.LLVAR, 20, Encoding.ASCII);
        byte[] b = buffer(ascii("05ABCDE"));
        assertParse(() -> FieldCodec.unpack(s, b, START, START + 1), 7, START, "needs 2 bytes, only 1 left");
    }

    @Test
    void bcdFieldTruncatedByLimit() {
        FieldSpec s = spec(FieldType.N, LengthKind.FIXED, 6, Encoding.BCD);
        byte[] b = buffer(Hex.decode("123456"));
        assertParse(() -> FieldCodec.unpack(s, b, START, START + 2), 7, START, "needs 3 bytes, only 2 left");
    }

    @Test
    void bcdLlvarPrefixCrossesLimit() {
        FieldSpec s = spec(FieldType.N, LengthKind.LLVAR, 19, Encoding.BCD);
        byte[] b = buffer(Hex.decode("041234"));
        assertParse(() -> FieldCodec.unpack(s, b, START, START), 7, START, "needs 1 bytes, only 0 left");
    }

    @Test
    void hexFieldTruncatedByLimit() {
        FieldSpec s = spec(FieldType.B, LengthKind.FIXED, 4, Encoding.ASCII);
        byte[] b = buffer(ascii("01020304"));
        assertParse(() -> FieldCodec.unpack(s, b, START, START + 7), 7, START, "needs 8 bytes, only 7 left");
    }

    @Test
    void binaryFieldTruncatedByLimit() {
        FieldSpec s = spec(FieldType.B, LengthKind.FIXED, 8, Encoding.BINARY);
        byte[] b = buffer(Hex.decode("0102030405060708"));
        assertParse(() -> FieldCodec.unpack(s, b, START, START + 7), 7, START, "needs 8 bytes, only 7 left");
    }

    @Test
    void offsetPastLimitMeansZeroBytesLeft() {
        FieldSpec s = spec(FieldType.AN, LengthKind.FIXED, 2, Encoding.ASCII);
        byte[] b = buffer(ascii("AB"));
        assertParse(() -> FieldCodec.unpack(s, b, START + 5, START + 1), 7, START + 5, "needs 2 bytes, only 0 left");
    }

    @Test
    void fieldLimitIsValidated() {
        FieldSpec s = spec(FieldType.AN, LengthKind.FIXED, 2, Encoding.ASCII);
        byte[] b = buffer(ascii("AB"));
        assertThatThrownBy(() -> FieldCodec.unpack(s, b, START, b.length + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldCodec.unpack(s, b, -1, b.length))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- BitmapCodec ----

    @Test
    void binaryPrimaryTruncatedByLimit() {
        byte[] b = buffer(Hex.decode("0000000000000000"));
        assertParse(() -> BitmapCodec.unpack(Encoding.BINARY, b, START, START + 5), 1, START,
                "truncated primary bitmap, need 8 bytes, have 5");
    }

    @Test
    void binarySecondaryCrossesLimit() {
        byte[] b = buffer(Hex.decode("80000000000000004000000000000000"));
        assertParse(() -> BitmapCodec.unpack(Encoding.BINARY, b, START, START + 12), 1, START + 8,
                "truncated secondary bitmap, need 8 bytes, have 4");
    }

    @Test
    void asciiSecondaryCrossesLimit() {
        byte[] b = buffer(ascii("80000000000000004000000000000000"));
        assertParse(() -> BitmapCodec.unpack(Encoding.ASCII, b, START, START + 20), 1, START + 16,
                "truncated secondary bitmap, need 16 bytes, have 4");
    }

    @Test
    void bitmapEndingExactlyAtLimitIsRead() {
        byte[] b = buffer(Hex.decode("80000000000000004000000000000000"));
        assertThat(BitmapCodec.unpack(Encoding.BINARY, b, START, START + 16).nextOffset()).isEqualTo(START + 16);
    }

    @Test
    void bitmapLimitIsValidated() {
        byte[] b = buffer(Hex.decode("0000000000000000"));
        assertThatThrownBy(() -> BitmapCodec.unpack(Encoding.BINARY, b, START, b.length + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BitmapCodec.unpack(Encoding.BINARY, b, -1, b.length))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- MtiCodec ----

    @Test
    void asciiMtiTruncatedByLimit() {
        byte[] b = buffer(ascii("0100"));
        assertParse(() -> MtiCodec.unpack(Encoding.ASCII, b, START, START + 3), 0, START,
                "truncated MTI, need 4 bytes, have 3");
    }

    @Test
    void bcdMtiTruncatedByLimit() {
        byte[] b = buffer(Hex.decode("0100"));
        assertParse(() -> MtiCodec.unpack(Encoding.BCD, b, START, START + 1), 0, START,
                "truncated MTI, need 2 bytes, have 1");
    }

    @Test
    void mtiOffsetPastLimitMeansZeroLeft() {
        byte[] b = buffer(ascii("0100"));
        assertParse(() -> MtiCodec.unpack(Encoding.ASCII, b, START + 2, START), 0, START + 2,
                "truncated MTI, need 4 bytes, have 0");
    }

    @Test
    void mtiLimitIsValidated() {
        byte[] b = buffer(ascii("0100"));
        assertThatThrownBy(() -> MtiCodec.unpack(Encoding.ASCII, b, START, b.length + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MtiCodec.unpack(Encoding.ASCII, b, -1, b.length))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
