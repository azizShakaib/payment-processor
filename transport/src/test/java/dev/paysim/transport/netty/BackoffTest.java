package dev.paysim.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void ceilingDoublesThenCaps() {
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(30), new Random(1));
        assertThat(b.ceiling(0)).isEqualTo(Duration.ofMillis(100));
        assertThat(b.ceiling(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(b.ceiling(2)).isEqualTo(Duration.ofMillis(400));
        assertThat(b.ceiling(8)).isEqualTo(Duration.ofMillis(25_600));
        assertThat(b.ceiling(9)).isEqualTo(Duration.ofSeconds(30));
        assertThat(b.ceiling(30)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void delaysStayWithinZeroAndCeiling() {
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(30), new Random(42));
        for (int attempt = 0; attempt < 40; attempt++) {
            for (int i = 0; i < 50; i++) {
                assertThat(b.delay(attempt)).isBetween(Duration.ZERO, b.ceiling(attempt));
            }
        }
    }

    @Test
    void jitterActuallySpreadsTheDelays() {
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(30), new Random(7));
        assertThat(java.util.stream.IntStream.range(0, 50).mapToObj(i -> b.delay(5)).distinct().count())
                .isGreaterThan(10);
    }

    @Test
    void sameSeedGivesSameSequence() {
        Backoff a = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(30), new Random(5));
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(30), new Random(5));
        for (int i = 0; i < 10; i++) {
            assertThat(a.next()).isEqualTo(b.next());
        }
    }

    @Test
    void hugeAttemptsDoNotOverflow() {
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(30), new Random(3));
        for (int attempt : new int[] {62, 63, 64, 100, 1_000_000, Integer.MAX_VALUE}) {
            assertThat(b.ceiling(attempt)).isEqualTo(Duration.ofSeconds(30));
            assertThat(b.delay(attempt)).isBetween(Duration.ZERO, Duration.ofSeconds(30));
        }
    }

    @Test
    void resetStartsOverFromTheSmallCeiling() {
        Backoff b = new Backoff(Duration.ofMillis(100), Duration.ofSeconds(30), new Random(9));
        for (int i = 0; i < 20; i++) {
            b.next();
        }
        b.reset();
        assertThat(b.next()).isLessThanOrEqualTo(Duration.ofMillis(100));
    }

    @Test
    void defaultsAreHundredMillisAndThirtySeconds() {
        Backoff b = new Backoff();
        assertThat(b.ceiling(0)).isEqualTo(Duration.ofMillis(100));
        assertThat(b.ceiling(60)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsBadConfiguration() {
        assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff().delay(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
