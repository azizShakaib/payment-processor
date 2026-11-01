package dev.paysim.benchmarks;

import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.IsoPacker;
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

/** Pack and unpack cost per message, ASCII dialect versus compact dialect. */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class PackerBenchmark {

    // Non-final so the JIT cannot constant-fold the inputs.
    IsoMessage ascii0100 = Messages.auth0100(Messages.ASCII);
    IsoMessage compact0100 = Messages.auth0100(Messages.COMPACT);
    IsoMessage ascii0800 = Messages.echo0800(Messages.ASCII);
    byte[] ascii0100Bytes = IsoPacker.pack(ascii0100);
    byte[] compact0100Bytes = IsoPacker.pack(compact0100);
    byte[] ascii0800Bytes = IsoPacker.pack(ascii0800);

    @Benchmark
    public byte[] packAscii0100() {
        return IsoPacker.pack(ascii0100);
    }

    @Benchmark
    public IsoMessage unpackAscii0100() {
        return IsoPacker.unpack(Messages.ASCII, ascii0100Bytes);
    }

    @Benchmark
    public byte[] packCompact0100() {
        return IsoPacker.pack(compact0100);
    }

    @Benchmark
    public IsoMessage unpackCompact0100() {
        return IsoPacker.unpack(Messages.COMPACT, compact0100Bytes);
    }

    @Benchmark
    public byte[] packAscii0800() {
        return IsoPacker.pack(ascii0800);
    }

    @Benchmark
    public IsoMessage unpackAscii0800() {
        return IsoPacker.unpack(Messages.ASCII, ascii0800Bytes);
    }

    @Benchmark
    public IsoMessage roundTripAscii0100() {
        return IsoPacker.unpack(Messages.ASCII, IsoPacker.pack(ascii0100));
    }
}
