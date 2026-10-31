package dev.paysim.iso8583.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.common.Hex;
import dev.paysim.iso8583.Bitmap;
import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.spec.Encoding;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class BitmapCodecTest {

    /** Non-zero start so tests prove offsets are absolute. */
    private static final int START = 20;

    private static byte[] pack(Bitmap bitmap, Encoding encoding) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BitmapCodec.pack(bitmap, encoding, out);
        return out.toByteArray();
    }

    private static String packHex(Bitmap bitmap) {
        return Hex.encode(pack(bitmap, Encoding.BINARY));
    }

    private static String packText(Bitmap bitmap) {
        return new String(pack(bitmap, Encoding.ASCII), StandardCharsets.US_ASCII);
    }

    /** Puts {@code wire} at {@link #START} in a buffer of filler bytes (plus a tail). */
    private static byte[] at(byte[] wire) {
        byte[] buffer = new byte[START + wire.length + 3];
        Arrays.fill(buffer, (byte) 0xEE);
        System.arraycopy(wire, 0, buffer, START, wire.length);
        return buffer;
    }

    private static byte[] atText(String text) {
        return at(text.getBytes(StandardCharsets.US_ASCII));
    }

    static IntStream allFields() {
        return IntStream.rangeClosed(2, 128);
    }

    // ---- exact bytes ----

    @Test
    void field2PutsBit2OfTheFirstByte() {
        assertThat(packHex(Bitmap.of(2))).isEqualTo("4000000000000000");
        assertThat(pack(Bitmap.of(2), Encoding.BINARY)[0]).isEqualTo((byte) 0x40);
    }

    @Test
    void field64PutsTheLastBitOfThe8thByte() {
        byte[] bytes = pack(Bitmap.of(64), Encoding.BINARY);

        assertThat(bytes).hasSize(8);
        assertThat(bytes[7]).isEqualTo((byte) 0x01);
        assertThat(Arrays.copyOf(bytes, 7)).containsOnly(0);
    }

    @Test
    void field65FlagsASecondaryAndSetsItsFirstBit() {
        byte[] bytes = pack(Bitmap.of(65), Encoding.BINARY);

        assertThat(bytes).hasSize(16);
        assertThat(bytes[0]).isEqualTo((byte) 0x80);
        assertThat(bytes[8]).isEqualTo((byte) 0x80);
        assertThat(Hex.encode(bytes)).isEqualTo("8000000000000000" + "8000000000000000");
    }

    @Test
    void field128IsTheLastBitOfTheSecondary() {
        byte[] bytes = pack(Bitmap.of(128), Encoding.BINARY);

        assertThat(bytes).hasSize(16);
        assertThat(bytes[0]).isEqualTo((byte) 0x80);
        assertThat(bytes[15]).isEqualTo((byte) 0x01);
    }

    @Test
    void echoMessageFields7And11And70InAscii() {
        assertThat(packText(Bitmap.of(7, 11, 70))).isEqualTo("8220000000000000" + "0400000000000000");
    }

    @Test
    void echoMessageFields7And11And70InBinary() {
        assertThat(packHex(Bitmap.of(7, 11, 70))).isEqualTo("8220000000000000" + "0400000000000000");
        assertThat(pack(Bitmap.of(7, 11, 70), Encoding.BINARY))
                .containsExactly(0x82, 0x20, 0, 0, 0, 0, 0, 0, 0x04, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void typicalAuthorizationRequest0100() {
        Bitmap bitmap = Bitmap.of(2, 3, 4, 7, 11, 12, 13, 14, 22, 25, 35, 41, 42, 49, 52);

        assertThat(bitmap.hasSecondary()).isFalse();
        assertThat(packHex(bitmap)).isEqualTo("723C048020C09000");
        assertThat(packText(bitmap)).isEqualTo("723C048020C09000");
    }

    @Test
    void asciiPacksUppercaseHexAndSizesMatch() {
        assertThat(pack(Bitmap.of(2), Encoding.ASCII)).hasSize(16);
        assertThat(pack(Bitmap.of(70), Encoding.ASCII)).hasSize(32);
        assertThat(pack(Bitmap.of(2), Encoding.BINARY)).hasSize(8);
        assertThat(pack(Bitmap.of(70), Encoding.BINARY)).hasSize(16);
        assertThat(packText(Bitmap.of(2, 3, 4, 7, 11, 12, 13, 14, 22, 25, 35, 41, 42, 49, 52)))
                .isEqualTo("723C048020C09000");
    }

    @Test
    void emptyBitmapPacksAsAllZeros() {
        assertThat(packHex(Bitmap.empty())).isEqualTo("0000000000000000");
    }

    @Test
    void packAppendsAfterExistingContent() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {1, 2});
        BitmapCodec.pack(Bitmap.of(2), Encoding.BINARY, out);

        assertThat(out.toByteArray()).hasSize(10).startsWith(1, 2, 0x40);
    }

    // ---- round trips ----

    @ParameterizedTest
    @EnumSource(value = Encoding.class, names = {"ASCII", "BINARY"})
    void everySingleFieldRoundTrips(Encoding encoding) {
        allFields().forEach(field -> {
            Bitmap bitmap = Bitmap.of(field);
            byte[] wire = pack(bitmap, encoding);

            UnpackedBitmap result = BitmapCodec.unpack(encoding, at(wire), START);

            assertThat(result.bitmap()).as("field %d", field).isEqualTo(bitmap);
            assertThat(result.nextOffset()).isEqualTo(START + wire.length);
        });
    }

    static Object[][] multiFieldSets() {
        return new Object[][] {
            {new int[] {7, 11, 70}},
            {new int[] {2, 3, 4, 7, 11, 12, 13, 14, 22, 25, 35, 41, 42, 49, 52}},
            {new int[] {2, 64}},
            {new int[] {64, 65}},
            {new int[] {2, 128}},
            {new int[] {7, 11, 39, 41, 90}},
            {IntStream.rangeClosed(2, 128).toArray()},
            {new int[] {}},
        };
    }

    @ParameterizedTest
    @MethodSource("multiFieldSets")
    void multiFieldSetsRoundTripInBothEncodings(int[] fields) {
        Bitmap bitmap = Bitmap.of(fields);
        for (Encoding encoding : new Encoding[] {Encoding.ASCII, Encoding.BINARY}) {
            byte[] wire = pack(bitmap, encoding);

            UnpackedBitmap result = BitmapCodec.unpack(encoding, at(wire), START);

            assertThat(result.bitmap()).isEqualTo(bitmap);
            assertThat(result.bitmap().fields()).containsExactly(bitmap.fields());
            assertThat(result.nextOffset()).isEqualTo(START + wire.length);
            assertThat(pack(result.bitmap(), encoding)).isEqualTo(wire);
        }
    }

    @Test
    void unpackStopsAfterThePrimaryWhenBitOneIsClear() {
        byte[] buffer = atText("723C048020C09000" + "FFFFFFFFFFFFFFFF");

        UnpackedBitmap result = BitmapCodec.unpack(Encoding.ASCII, buffer, START);

        assertThat(result.nextOffset()).isEqualTo(START + 16);
        assertThat(result.bitmap().hasSecondary()).isFalse();
    }

    @Test
    void asciiHexAcceptsLowerCase() {
        byte[] buffer = atText("723c048020c09000");

        assertThat(BitmapCodec.unpack(Encoding.ASCII, buffer, START).bitmap())
                .isEqualTo(Bitmap.of(2, 3, 4, 7, 11, 12, 13, 14, 22, 25, 35, 41, 42, 49, 52));
    }

    @Test
    void asciiSecondaryHexAcceptsMixedCase() {
        byte[] buffer = atText("8220000000000000" + "04000000000000aB");

        Bitmap bitmap = BitmapCodec.unpack(Encoding.ASCII, buffer, START).bitmap();

        assertThat(bitmap.has(70)).isTrue();
        assertThat(bitmap.has(121)).isTrue();
        assertThat(bitmap.has(128)).isTrue();
        assertThat(bitmap.has(122)).isFalse();
    }

    @Test
    void unpackedBinaryBytesAreReadFromTheRightOffset() {
        byte[] wire = Hex.decode("82200000000000000400000000000000");

        UnpackedBitmap result = BitmapCodec.unpack(Encoding.BINARY, at(wire), START);

        assertThat(result.bitmap().fields()).containsExactly(7, 11, 70);
        assertThat(result.nextOffset()).isEqualTo(START + 16);
    }

    @Test
    void emptyBitmapUnpacksToEmpty() {
        assertThat(BitmapCodec.unpack(Encoding.BINARY, at(new byte[8]), START).bitmap())
                .isEqualTo(Bitmap.empty());
    }

    @Test
    void exactlyEnoughBytesAtTheEndOfTheBufferIsFine() {
        byte[] buffer = new byte[START + 8];
        buffer[START] = 0x40;

        assertThat(BitmapCodec.unpack(Encoding.BINARY, buffer, START).bitmap()).isEqualTo(Bitmap.of(2));
    }

    // ---- errors ----

    @Test
    void truncatedPrimaryReportsItsStart() {
        byte[] buffer = Arrays.copyOf(at(new byte[8]), START + 7);

        assertParseError(() -> BitmapCodec.unpack(Encoding.BINARY, buffer, START), START, "truncated primary");
    }

    @Test
    void truncatedAsciiPrimaryReportsItsStart() {
        byte[] buffer = Arrays.copyOf(atText("723C048020C09000"), START + 15);

        assertParseError(() -> BitmapCodec.unpack(Encoding.ASCII, buffer, START), START, "truncated primary");
    }

    @Test
    void offsetPastTheEndIsTruncation() {
        assertParseError(() -> BitmapCodec.unpack(Encoding.BINARY, new byte[4], 10), 10, "truncated primary");
    }

    @Test
    void truncatedSecondaryReportsItsStart() {
        byte[] buffer = Arrays.copyOf(atText("8220000000000000" + "0400000000000000"), START + 16 + 15);

        assertParseError(() -> BitmapCodec.unpack(Encoding.ASCII, buffer, START), START + 16, "truncated secondary");
    }

    @Test
    void missingSecondaryInBinaryReportsItsStart() {
        byte[] buffer = Arrays.copyOf(at(Hex.decode("8220000000000000")), START + 8);

        assertParseError(() -> BitmapCodec.unpack(Encoding.BINARY, buffer, START), START + 8, "truncated secondary");
    }

    @Test
    void invalidHexInPrimaryReportsTheExactByte() {
        byte[] buffer = atText("723C04G020C09000");

        assertParseError(() -> BitmapCodec.unpack(Encoding.ASCII, buffer, START), START + 6, "invalid hex");
    }

    @Test
    void invalidHexInSecondaryReportsTheExactByte() {
        byte[] buffer = atText("8220000000000000" + "0400000000000" + "00Z");

        assertParseError(() -> BitmapCodec.unpack(Encoding.ASCII, buffer, START), START + 16 + 15, "invalid hex");
    }

    @Test
    void nonAsciiByteIsInvalidHex() {
        byte[] buffer = atText("723C048020C09000");
        buffer[START + 3] = (byte) 0xC3;

        assertParseError(() -> BitmapCodec.unpack(Encoding.ASCII, buffer, START), START + 3, "invalid hex");
    }

    @Test
    void flaggedButEmptySecondaryIsRejectedInAscii() {
        byte[] buffer = atText("8220000000000000" + "0000000000000000");

        assertParseError(() -> BitmapCodec.unpack(Encoding.ASCII, buffer, START), START + 16,
                "secondary bitmap flagged but empty");
    }

    @Test
    void flaggedButEmptySecondaryIsRejectedInBinary() {
        byte[] buffer = at(Hex.decode("82200000000000000000000000000000"));

        assertParseError(() -> BitmapCodec.unpack(Encoding.BINARY, buffer, START), START + 8,
                "secondary bitmap flagged but empty");
    }

    @Test
    void errorMessagesNameFieldOneAndOffset() {
        byte[] buffer = atText("723C04G020C09000");

        assertThatThrownBy(() -> BitmapCodec.unpack(Encoding.ASCII, buffer, START))
                .hasMessageStartingWith("field 1 at offset " + (START + 6));
    }

    @Test
    void unsupportedEncodingIsRejectedOnPackAndWritesNothing() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatThrownBy(() -> BitmapCodec.pack(Bitmap.of(2), Encoding.BCD, out))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BCD");
        assertThat(out.size()).isZero();
    }

    @Test
    void unsupportedEncodingIsRejectedOnUnpack() {
        assertThatThrownBy(() -> BitmapCodec.unpack(Encoding.BCD, new byte[16], 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BCD");
    }

    @Test
    void nullArgumentsAreRejected() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatThrownBy(() -> BitmapCodec.pack(null, Encoding.ASCII, out))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BitmapCodec.pack(Bitmap.empty(), null, out))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BitmapCodec.pack(Bitmap.empty(), Encoding.ASCII, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BitmapCodec.unpack(null, new byte[8], 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> BitmapCodec.unpack(Encoding.ASCII, null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    private static void assertParseError(Runnable action, int offset, String reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IsoParseException.class, e -> {
                    assertThat(e.field()).isEqualTo(1);
                    assertThat(e.offset()).isEqualTo(offset);
                    assertThat(e.getMessage()).contains(reason);
                });
    }
}
