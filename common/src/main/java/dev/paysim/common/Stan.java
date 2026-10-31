package dev.paysim.common;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * System Trace Audit Number generator (ISO 8583 field 11). The STAN is six
 * digits, 000001 to 999999, and wraps back to 000001 (000000 is never issued).
 * Lock-free so many connection threads can share one instance.
 */
public final class Stan {

    private static final int MIN = 1;
    private static final int MAX = 999_999;

    private final AtomicInteger counter;

    public Stan() {
        this(MIN);
    }

    /** The first value returned by {@link #next()} will be {@code start}. Mainly for tests. */
    public Stan(int start) {
        if (start < MIN || start > MAX) {
            throw new IllegalArgumentException("STAN start must be 1-999999, got " + start);
        }
        this.counter = new AtomicInteger(start);
    }

    public int next() {
        return counter.getAndUpdate(current -> current == MAX ? MIN : current + 1);
    }

    public String nextFormatted() {
        return String.format("%06d", next());
    }
}
