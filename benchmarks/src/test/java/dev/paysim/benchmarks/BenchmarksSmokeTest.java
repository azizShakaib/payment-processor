package dev.paysim.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.paysim.iso8583.Mti;
import org.junit.jupiter.api.Test;

/** Runs every benchmark method once so the fixtures cannot rot between benchmark runs. */
class BenchmarksSmokeTest {

    @Test
    void packerBenchmarksProduceSaneResults() {
        PackerBenchmark b = new PackerBenchmark();

        assertThat(b.packAscii0100()).hasSize(159);
        assertThat(b.packCompact0100()).hasSize(92);
        assertThat(b.packAscii0800()).isNotEmpty().hasSizeLessThan(159);
        assertThat(b.unpackAscii0100()).isEqualTo(b.ascii0100);
        assertThat(b.unpackCompact0100()).isEqualTo(b.compact0100);
        assertThat(b.unpackAscii0800()).isEqualTo(b.ascii0800);
        assertThat(b.unpackAscii0100().mti()).isEqualTo(Mti.AUTH_REQUEST);
        assertThat(b.roundTripAscii0100()).isEqualTo(b.ascii0100);
    }

    @Test
    void sliceBenchmarksUnpackTheSameMessage() {
        SliceBenchmark b = new SliceBenchmark();

        assertThat(b.unpackSliceAtStart()).isEqualTo(b.unpackSliceNearEnd());
        assertThat(b.unpackSliceAtStart()).isEqualTo(b.unpackSliceFlushWithEnd());
        assertThat(b.unpackSliceAtStart()).isEqualTo(Messages.auth0100(Messages.ASCII));
    }

    @Test
    void printerBenchmarksMaskTheCardNumber() {
        PrinterBenchmark b = new PrinterBenchmark();

        assertThat(b.formatAscii0100()).contains("411111******1111").doesNotContain("4111111111111111");
        assertThat(b.unpackAndFormatAscii0100()).isEqualTo(b.formatAscii0100());
    }
}
