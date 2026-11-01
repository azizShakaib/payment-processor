package dev.paysim.link;

import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.ResponseCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.paysim.link.TestMessages.auth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingRequestsTest {

    private static final Duration LONG = Duration.ofSeconds(30);

    private static IsoMessage approve(IsoMessage request) {
        return request.responseBuilder().responseCode(ResponseCode.APPROVED).build();
    }

    @Test
    void completeDeliversTheMatchingResponse() throws Exception {
        PendingRequests pending = new PendingRequests();
        IsoMessage request = auth(1, 100);
        CompletableFuture<IsoMessage> future = pending.register(request, LONG);
        assertThat(pending.inFlight()).isEqualTo(1);

        assertThat(pending.complete(approve(request))).isTrue();

        assertThat(future.get(1, TimeUnit.SECONDS).responseCode()).contains(ResponseCode.APPROVED);
        assertThat(pending.inFlight()).isZero();
    }

    @Test
    void unmatchedResponseReturnsFalse() {
        PendingRequests pending = new PendingRequests();
        pending.register(auth(1, 100), LONG);
        assertThat(pending.complete(approve(auth(2, 100)))).isFalse();
        assertThat(pending.inFlight()).isEqualTo(1);
    }

    @Test
    void responseTwiceMatchesOnlyOnce() {
        PendingRequests pending = new PendingRequests();
        IsoMessage request = auth(1, 100);
        pending.register(request, LONG);
        assertThat(pending.complete(approve(request))).isTrue();
        assertThat(pending.complete(approve(request))).isFalse();
    }

    @Test
    void duplicateKeyInFlightIsRejected() {
        PendingRequests pending = new PendingRequests();
        pending.register(auth(1, 100), LONG);
        assertThatThrownBy(() -> pending.register(auth(1, 999), LONG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate STAN in flight");
        assertThat(pending.inFlight()).isEqualTo(1);
    }

    @Test
    void timeoutCompletesExceptionallyAndFreesTheKey() {
        PendingRequests pending = new PendingRequests();
        IsoMessage request = auth(1, 100);
        CompletableFuture<IsoMessage> future = pending.register(request, Duration.ofMillis(50));

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        assertThat(pending.inFlight()).isZero();
        // a late response matches nothing
        assertThat(pending.complete(approve(request))).isFalse();
        // and the key can be used again
        CompletableFuture<IsoMessage> again = pending.register(request, LONG);
        assertThat(pending.complete(approve(request))).isTrue();
        assertThat(again).isCompleted();
    }

    @Test
    void failAllFailsEveryFuture() {
        PendingRequests pending = new PendingRequests();
        List<CompletableFuture<IsoMessage>> futures = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            futures.add(pending.register(auth(i, 100), LONG));
        }
        pending.failAll(new LinkDroppedException("down"));

        assertThat(pending.inFlight()).isZero();
        for (CompletableFuture<IsoMessage> f : futures) {
            assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(LinkDroppedException.class);
        }
    }

    @Test
    void cancellingTheFutureFreesTheKey() {
        PendingRequests pending = new PendingRequests();
        IsoMessage request = auth(1, 100);
        pending.register(request, LONG).cancel(false);
        assertThat(pending.inFlight()).isZero();
        pending.register(request, LONG); // does not throw
    }

    @Test
    void concurrentRegisterAndCompleteFromManyThreads() throws Exception {
        PendingRequests pending = new PendingRequests();
        int threads = 16;
        int perThread = 500;
        AtomicInteger matched = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads);
             ExecutorService completers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        IsoMessage request = auth(base + i + 1, base + i + 1);
                        CompletableFuture<IsoMessage> f = pending.register(request, LONG);
                        // another thread's worth of contention: complete from a different pool thread
                        CompletableFuture.runAsync(() -> pending.complete(approve(request)), completers).join();
                        IsoMessage response = f.get(5, TimeUnit.SECONDS);
                        if (response.stan().getAsInt() == base + i + 1) {
                            matched.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(matched.get()).isEqualTo(threads * perThread);
        assertThat(pending.inFlight()).isZero();
    }
}
