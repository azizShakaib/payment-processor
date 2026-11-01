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

/**
 * Cost of the slice overload in a 64 KiB buffer. The overload copies the buffer up to the slice's end
 * unless the slice ends exactly at the buffer's end, so three positions are measured:
 * start (copies about 160 bytes), one byte short of the end (copies about 64 KiB) and flush with the end (no copy).
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class SliceBenchmark {

    static final int BUFFER_SIZE = 64 * 1024;

    byte[] wire = IsoPacker.pack(Messages.auth0100(Messages.ASCII));
    byte[] atStart = new byte[BUFFER_SIZE];
    byte[] nearEnd = new byte[BUFFER_SIZE];
    byte[] flushEnd = new byte[BUFFER_SIZE];
    int nearEndOffset;
    int flushEndOffset;

    public SliceBenchmark() {
        nearEndOffset = BUFFER_SIZE - wire.length - 1;
        flushEndOffset = BUFFER_SIZE - wire.length;
        System.arraycopy(wire, 0, atStart, 0, wire.length);
        System.arraycopy(wire, 0, nearEnd, nearEndOffset, wire.length);
        System.arraycopy(wire, 0, flushEnd, flushEndOffset, wire.length);
    }

    @Benchmark
    public IsoMessage unpackSliceAtStart() {
        return IsoPacker.unpack(Messages.ASCII, atStart, 0, wire.length);
    }

    @Benchmark
    public IsoMessage unpackSliceNearEnd() {
        return IsoPacker.unpack(Messages.ASCII, nearEnd, nearEndOffset, wire.length);
    }

    @Benchmark
    public IsoMessage unpackSliceFlushWithEnd() {
        return IsoPacker.unpack(Messages.ASCII, flushEnd, flushEndOffset, wire.length);
    }
}
