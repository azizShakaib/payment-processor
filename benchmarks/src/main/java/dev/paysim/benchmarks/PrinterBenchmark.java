package dev.paysim.benchmarks;

import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.IsoPacker;
import dev.paysim.iso8583.IsoPrinter;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** What it costs to log a message: format alone, and parse then format. */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class PrinterBenchmark {

    IsoMessage message = Messages.auth0100(Messages.ASCII);
    byte[] wire = IsoPacker.pack(message);

    @Benchmark
    public String formatAscii0100() {
        return IsoPrinter.format(message);
    }

    @Benchmark
    public String unpackAndFormatAscii0100() {
        return IsoPrinter.format(IsoPacker.unpack(Messages.ASCII, wire));
    }
}
