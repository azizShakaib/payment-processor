package dev.paysim.link;

import dev.paysim.iso8583.IsoMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Requests sent and not yet answered ("in flight"), keyed by {@link MatchKey}.
 * Thread-safe; every operation is a single {@link ConcurrentHashMap} step.
 *
 * <p><b>Timers.</b> Each request gets a timeout task on a
 * {@link ScheduledThreadPoolExecutor} (by default one shared daemon thread;
 * injectable for tests). When it fires it removes the entry <em>on the timer
 * thread</em> (so the key is free again at once) and then completes the future
 * with {@link TimeoutException} on a fresh virtual thread, so a caller's
 * {@code thenApply} chain never runs on, or blocks, the shared timer.
 * Completion by {@link #complete} and {@link #failAll} runs dependents on the
 * calling thread, as usual for {@link CompletableFuture}.
 *
 * <p>A timed-out entry is gone: a late response matches nothing
 * ({@link #complete} returns false). Whether the issuer approved is unknown;
 * that is the reversal problem of Phase 3.
 */
public final class PendingRequests {

    private static final class SharedTimer {
        static final ScheduledThreadPoolExecutor INSTANCE = create();

        private static ScheduledThreadPoolExecutor create() {
            var executor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "pending-requests-timer");
                t.setDaemon(true);
                return t;
            });
            executor.setRemoveOnCancelPolicy(true);
            return executor;
        }
    }

    private final Map<MatchKey, CompletableFuture<IsoMessage>> inFlight = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor timer;

    public PendingRequests() {
        this(SharedTimer.INSTANCE);
    }

    public PendingRequests(ScheduledThreadPoolExecutor timer) {
        this.timer = Objects.requireNonNull(timer);
    }

    /**
     * Registers a request that is about to be sent. The returned future
     * completes with the matching response, or exceptionally with
     * {@link TimeoutException}, or with the cause given to {@link #failAll}.
     * Completing it by other means (cancel, completeExceptionally) also frees the key.
     *
     * @throws IllegalStateException if a request with the same key is in flight
     */
    public CompletableFuture<IsoMessage> register(IsoMessage request, Duration timeout) {
        Objects.requireNonNull(request);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        MatchKey key = MatchKey.of(request);
        CompletableFuture<IsoMessage> future = new CompletableFuture<>();
        if (inFlight.putIfAbsent(key, future) != null) {
            throw new IllegalStateException("duplicate STAN in flight: " + key);
        }
        ScheduledFuture<?> task = timer.schedule(() -> {
            if (inFlight.remove(key, future)) {
                Thread.startVirtualThread(() -> future.completeExceptionally(
                        new TimeoutException("no response within " + timeout.toMillis() + " ms for " + key)));
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        future.whenComplete((response, error) -> {
            inFlight.remove(key, future);
            task.cancel(false);
        });
        return future;
    }

    /** Hands a response to the request it answers. False when nothing matches (late or unsolicited). */
    public boolean complete(IsoMessage response) {
        CompletableFuture<IsoMessage> future = inFlight.remove(MatchKey.of(response));
        if (future == null) {
            return false;
        }
        future.complete(response);
        return true;
    }

    /** Fails every request in flight, for example because the link dropped. */
    public void failAll(Throwable cause) {
        List<CompletableFuture<IsoMessage>> all = new ArrayList<>();
        for (MatchKey key : inFlight.keySet()) {
            CompletableFuture<IsoMessage> f = inFlight.remove(key);
            if (f != null) {
                all.add(f);
            }
        }
        all.forEach(f -> f.completeExceptionally(cause));
    }

    public int inFlight() {
        return inFlight.size();
    }
}
