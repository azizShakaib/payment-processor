package dev.paysim.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class StanTest {

    @Test
    void startsAtOne() {
        Stan stan = new Stan();
        assertThat(stan.nextFormatted()).isEqualTo("000001");
        assertThat(stan.next()).isEqualTo(2);
    }

    @Test
    void formatsWithZeroPadding() {
        assertThat(new Stan(42).nextFormatted()).isEqualTo("000042");
        assertThat(new Stan(123_456).nextFormatted()).isEqualTo("123456");
    }

    @Test
    void wrapsFromMaxToOneNeverZero() {
        Stan stan = new Stan(999_998);
        assertThat(stan.next()).isEqualTo(999_998);
        assertThat(stan.next()).isEqualTo(999_999);
        assertThat(stan.next()).isEqualTo(1);
    }

    @Test
    void constructorRejectsOutOfRange() {
        assertThatThrownBy(() -> new Stan(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Stan(1_000_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentCallersGetDistinctValues() throws Exception {
        Stan stan = new Stan();
        Set<Integer> seen = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 10_000; i++) {
                        seen.add(stan.next());
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(seen).hasSize(80_000);
    }
}
