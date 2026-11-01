package dev.paysim.transport.netty;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter, used to space out reconnect attempts.
 *
 * <p>{@code delay(attempt) = random(0, min(max, initial * 2^attempt))}. The
 * ceiling doubles each attempt up to {@code max}; the actual wait is a random
 * point below the ceiling, so many clients that lost the same server do not
 * all retry at the same instant (the "thundering herd").
 *
 * <p>{@link #delay(int)} is pure given the random source. {@link #next()} and
 * {@link #reset()} add an attempt counter on top. Methods are synchronized.
 */
public final class Backoff {

    public static final Duration DEFAULT_INITIAL = Duration.ofMillis(100);
    public static final Duration DEFAULT_MAX = Duration.ofSeconds(30);

    private final long initialMillis;
    private final long maxMillis;
    private final RandomGenerator random;
    private int attempt;

    public Backoff() {
        this(DEFAULT_INITIAL, DEFAULT_MAX, RandomGenerator.getDefault());
    }

    public Backoff(Duration initial, Duration max) {
        this(initial, max, RandomGenerator.getDefault());
    }

    public Backoff(Duration initial, Duration max, RandomGenerator random) {
        if (initial.isNegative() || initial.isZero() || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("need 0 < initial <= max");
        }
        this.initialMillis = initial.toMillis();
        this.maxMillis = max.toMillis();
        this.random = random;
    }

    /** The ceiling for an attempt (0-based), without jitter. Never overflows. */
    public Duration ceiling(int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be >= 0");
        }
        if (attempt >= 63 || initialMillis > (maxMillis >> attempt)) {
            return Duration.ofMillis(maxMillis);
        }
        return Duration.ofMillis(initialMillis << attempt);
    }

    /** A random delay between zero and the ceiling for this attempt. */
    public Duration delay(int attempt) {
        long ceiling = ceiling(attempt).toMillis();
        return Duration.ofMillis(random.nextLong(ceiling + 1));
    }

    /** The delay for the current attempt, then advances the counter. */
    public synchronized Duration next() {
        Duration d = delay(attempt);
        if (attempt < Integer.MAX_VALUE) {
            attempt++;
        }
        return d;
    }

    /** Back to attempt zero, after a successful connection. */
    public synchronized void reset() {
        attempt = 0;
    }
}
