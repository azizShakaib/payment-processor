package dev.paysim.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SerialExecutorTest {

    @Test
    void runsTasksInOrderAndNeverConcurrently() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            SerialExecutor serial = new SerialExecutor(pool);
            List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger inside = new AtomicInteger();
            AtomicInteger maxInside = new AtomicInteger();
            int n = 2_000;
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                int id = i;
                serial.execute(() -> {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    seen.add(id);
                    inside.decrementAndGet();
                    done.countDown();
                });
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maxInside.get()).isEqualTo(1);
            assertThat(seen).hasSize(n).isSorted();
        }
    }

    @Test
    void aThrowingTaskDoesNotStopLaterTasks() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            SerialExecutor serial = new SerialExecutor(pool);
            CountDownLatch done = new CountDownLatch(1);
            serial.execute(() -> {
                throw new IllegalStateException("boom");
            });
            serial.execute(done::countDown);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
