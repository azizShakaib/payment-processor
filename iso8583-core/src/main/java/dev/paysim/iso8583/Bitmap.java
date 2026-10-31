package dev.paysim.iso8583;

import java.util.Arrays;

/**
 * The set of data elements present in a message. Bit numbering is ISO 8583's:
 * bit 1 is the most significant bit of the first byte. Bit 1 itself means "a
 * secondary bitmap follows" and is managed here, never by the caller: it is
 * set exactly when some field in 65..128 is present. Bits 2..64 are fields
 * 2..64 and the secondary bitmap holds fields 65..128. Switches route and
 * validate on this set before they parse a single field. Immutable.
 */
public final class Bitmap {

    private static final Bitmap EMPTY = new Bitmap(0L, 0L);
    private static final long BIT_1 = 1L << 63;

    /** Fields 2..64; bit 1 is never stored here. */
    private final long primaryData;
    /** Fields 65..128; field 65 is the most significant bit. */
    private final long secondary;

    private Bitmap(long primaryData, long secondary) {
        this.primaryData = primaryData;
        this.secondary = secondary;
    }

    public static Bitmap empty() {
        return EMPTY;
    }

    public static Bitmap of(int... fields) {
        Bitmap result = EMPTY;
        for (int field : fields) {
            result = result.with(field);
        }
        return result;
    }

    /**
     * Rebuilds a bitmap from the two raw words as read off the wire. Bit 1 of
     * {@code primary} is ignored because it is derived from the secondary.
     */
    public static Bitmap fromWords(long primary, long secondary) {
        return new Bitmap(primary & ~BIT_1, secondary);
    }

    /** Returns a new bitmap with {@code field} (2..128) added. */
    public Bitmap with(int field) {
        checkField(field);
        if (field <= 64) {
            return new Bitmap(primaryData | bit(field), secondary);
        }
        return new Bitmap(primaryData, secondary | bit(field - 64));
    }

    public boolean has(int field) {
        if (field < 2 || field > 128) {
            return false;
        }
        return field <= 64 ? (primaryData & bit(field)) != 0 : (secondary & bit(field - 64)) != 0;
    }

    /** Present data fields in ascending order. Never includes 1. */
    public int[] fields() {
        int[] result = new int[Long.bitCount(primaryData) + Long.bitCount(secondary)];
        int n = 0;
        for (int field = 2; field <= 128; field++) {
            if (has(field)) {
                result[n++] = field;
            }
        }
        return result;
    }

    public boolean hasSecondary() {
        return secondary != 0;
    }

    /** First 64 bits as sent; bit 1 is set when a secondary bitmap is needed. */
    public long primary() {
        return hasSecondary() ? primaryData | BIT_1 : primaryData;
    }

    /** Bits for fields 65..128 as sent; zero when there is no secondary. */
    public long secondary() {
        return secondary;
    }

    private static void checkField(int field) {
        if (field == 1) {
            throw new IllegalArgumentException(
                    "field 1 is the secondary bitmap flag and is set automatically");
        }
        if (field < 2 || field > 128) {
            throw new IllegalArgumentException("field " + field + " must be in 2..128");
        }
    }

    /** Mask for bit {@code n} (1..64) of a word, where bit 1 is the most significant. */
    private static long bit(int n) {
        return 1L << (64 - n);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bitmap other
                && primaryData == other.primaryData && secondary == other.secondary;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(primaryData) + Long.hashCode(secondary);
    }

    /** Field numbers are not sensitive, so they are shown. */
    @Override
    public String toString() {
        return "Bitmap" + Arrays.toString(fields());
    }
}
