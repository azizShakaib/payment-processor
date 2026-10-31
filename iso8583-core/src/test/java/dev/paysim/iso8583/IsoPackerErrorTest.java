package dev.paysim.iso8583;

import static dev.paysim.iso8583.PackerSupport.ASCII;
import static dev.paysim.iso8583.PackerSupport.COMPACT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.paysim.common.Hex;
import dev.paysim.iso8583.spec.MessageSpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Failures report the field (0 = MTI, 1 = bitmap) and the absolute offset, never content. */
class IsoPackerErrorTest {

    /** Filler bytes in front of the message, so offsets prove to be absolute. */
    private static final int LEAD = 10;

    private final List<String> seenMessages = new ArrayList<>();

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** Unpacks {@code wire} placed after {@link #LEAD} filler bytes (and 5 filler bytes behind it). */
    private IsoParseException failAt10(MessageSpec spec, String wire) {
        return failAt10(spec, ascii(wire));
    }

    private IsoParseException failAt10(MessageSpec spec, byte[] wire) {
        byte[] buffer = PackerSupport.framed(wire, LEAD, 5);
        Throwable thrown = catchThrowable(() -> IsoPacker.unpack(spec, buffer, LEAD, wire.length));
        assertThat(thrown).isInstanceOf(IsoParseException.class);
        IsoParseException e = (IsoParseException) thrown;
        seenMessages.add(e.getMessage());
        return e;
    }

    @Test
    void truncatedMti() {
        IsoParseException e = failAt10(ASCII, "010");

        assertThat(e.field()).isEqualTo(0);
        assertThat(e.offset()).isEqualTo(LEAD);
        assertThat(e).hasMessage("field 0 at offset 10: truncated MTI, need 4 bytes, have 3");
    }

    @Test
    void nonDigitMti() {
        IsoParseException e = failAt10(ASCII, "01A0" + "0000000000000000");

        assertThat(e.field()).isEqualTo(0);
        assertThat(e.offset()).isEqualTo(LEAD + 2);
        assertThat(e).hasMessage("field 0 at offset 12: non-digit in MTI at index 2");
    }

    @Test
    void mtiThatMtiRejects() {
        IsoParseException e = failAt10(ASCII, "1100" + "0000000000000000");

        assertThat(e.field()).isEqualTo(0);
        assertThat(e.offset()).isEqualTo(LEAD);
        assertThat(e.getMessage()).startsWith("field 0 at offset 10: MTI rejected: ");
    }

    @Test
    void compactInvalidBcdNibbleInMti() {
        IsoParseException e = failAt10(COMPACT, Hex.decode("01F0"));

        assertThat(e.field()).isEqualTo(0);
        assertThat(e.offset()).isEqualTo(LEAD + 1);
        assertThat(e).hasMessage("field 0 at offset 11: invalid BCD nibble in MTI at index 2");
    }

    @Test
    void compactTruncatedMti() {
        IsoParseException e = failAt10(COMPACT, Hex.decode("08"));

        assertThat(e).hasMessage("field 0 at offset 10: truncated MTI, need 2 bytes, have 1");
    }

    @Test
    void truncatedBitmap() {
        IsoParseException e = failAt10(ASCII, "0100" + "723C");

        assertThat(e.field()).isEqualTo(1);
        assertThat(e.offset()).isEqualTo(LEAD + 4);
    }

    @Test
    void fieldInBitmapButNotInSpec() {
        // bit 5 is not defined in paysim-ascii-87: bitmap byte 0 = 0x08
        IsoParseException e = failAt10(ASCII, "0100" + "0800000000000000" + "whatever");

        assertThat(e.field()).isEqualTo(5);
        assertThat(e.offset()).isEqualTo(LEAD + 4 + 16);
        assertThat(e).hasMessage("field 5 at offset 30: present in bitmap but not defined in spec paysim-ascii-87");
    }

    @Test
    void truncatedMidField() {
        // field 3 needs 6 bytes, only 4 are inside the slice
        IsoParseException e = failAt10(ASCII, "0100" + "2000000000000000" + "0000");

        assertThat(e.field()).isEqualTo(3);
        assertThat(e.offset()).isEqualTo(LEAD + 4 + 16);
        assertThat(e).hasMessage("field 3 at offset 30: needs 6 bytes, only 4 left");
    }

    @Test
    void bytesAfterTheSliceAreNotReadAsPartOfTheMessage() {
        // the 5 filler bytes behind the slice would complete field 3 if the slice end were ignored
        IsoParseException e = failAt10(ASCII, "0100" + "2000000000000000" + "0000");

        assertThat(e.field()).isEqualTo(3);
    }

    @Test
    void truncatedMidPanReportsFieldTwoAtItsLengthPrefix() {
        IsoParseException e = failAt10(ASCII, "0100" + "4000000000000000" + "164111");

        assertThat(e.field()).isEqualTo(2);
        assertThat(e.offset()).isEqualTo(LEAD + 4 + 16 + 2);
    }

    @Test
    void trailingBytes() {
        IsoParseException e = failAt10(ASCII, "0800" + "8220000000000000" + "0400000000000000"
                + "1015042100" + "000001" + "301" + "XYZZY");

        assertThat(e.field()).isEqualTo(70);
        assertThat(e.offset()).isEqualTo(LEAD + 55);
        assertThat(e).hasMessage("field 70 at offset 65: 5 trailing bytes after the last field");
    }

    @Test
    void trailingBytesWithNoFieldsAreReportedAgainstTheBitmap() {
        IsoParseException e = failAt10(ASCII, "0800" + "0000000000000000" + "X");

        assertThat(e.field()).isEqualTo(1);
        assertThat(e.offset()).isEqualTo(LEAD + 20);
        assertThat(e).hasMessage("field 1 at offset 30: 1 trailing bytes after the last field");
    }

    @Test
    void specMismatchCompactBytesReadAsAscii() {
        byte[] compact = IsoPacker.pack(PackerSupport.echo0800(COMPACT));

        assertThatThrownBy(() -> IsoPacker.unpack(ASCII, compact)).isInstanceOf(IsoParseException.class);
    }

    @Test
    void noErrorMessageEchoesAnyFourDigitsOfThePan() {
        // the PAN is 4111111111111111; none of the failures above may echo any of it
        failAt10(ASCII, "0100" + "4000000000000000" + "164111");
        failAt10(ASCII, "0100" + "4000000000000000" + "1X4111111111111111");
        failAt10(ASCII, "0100" + "4000000000000000" + "16411111111111111");
        failAt10(ASCII, "0100" + "6000000000000000" + "164111111111111111" + "00");
        failAt10(ASCII, "0100" + "0800000000000000" + "4111111111111111");
        failAt10(ASCII, "0800" + "0000000000000000" + "4111111111111111");
        failAt10(ASCII, "4111111111111111");
        failAt10(ASCII, "0100" + "4111111111111111");
        failAt10(COMPACT, Hex.decode("0100" + "4000000000000000" + "16" + "41111111"));

        assertThat(seenMessages).hasSizeGreaterThanOrEqualTo(9);
        Pattern run = Pattern.compile("4111|1111");
        for (String message : seenMessages) {
            assertThat(run.matcher(message).find()).as(message).isFalse();
        }
    }
}
