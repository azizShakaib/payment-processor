# Phase 1 benchmarks: ISO 8583 packer and printer

Run date: 2026-11-01. Produced by the `benchmarks` module (step 7, `docs/steps/phase-1-step-7.md`). This is **one run on one desktop machine**. Treat the numbers as relative (A is about twice B), not absolute (a production switch will see different numbers).

## Machine and settings

| Item | Value |
| --- | --- |
| CPU | Intel Core i5-10400F @ 2.90 GHz, 6 cores / 12 threads |
| RAM | about 16 GB |
| OS | Windows 10 Pro (10.0.19045) |
| JVM | Java HotSpot 64-Bit Server VM 21.0.5+9-LTS, default flags (no heap or GC options, so G1) |
| JMH | 1.37, `-prof gc`, single benchmark thread |
| Per benchmark | 1 fork, 3 warmup iterations x 1 s, 5 measurement iterations x 1 s |
| Modes | `AverageTime` (ns/op) and `Throughput` (shown here as ops/ms) |
| Total wall time | 3 min 21 s (second run, after fixing the slice benchmark; the first full run took 3 min 5 s) |

The machine was a normal desktop (browser, IDE and antivirus running), not an isolated benchmark box. Error columns are JMH's 99.9% confidence half-width over 5 iterations, so they are wide.

## Results

`alloc` is `gc.alloc.rate.norm`: bytes allocated per operation, as measured in the AverageTime run.

| Benchmark | ns/op (avg time) | ops/ms (throughput) | alloc bytes/op |
| --- | ---: | ---: | ---: |
| `PackerBenchmark.packAscii0100` | 2,112 ± 412 | 475 ± 34 | 6,096 |
| `PackerBenchmark.unpackAscii0100` | 1,068 ± 151 | 982 ± 24 | 4,616 |
| `PackerBenchmark.packCompact0100` | 2,237 ± 125 | 447 ± 35 | 6,024 |
| `PackerBenchmark.unpackCompact0100` | 1,226 ± 91 | 756 ± 210 | 3,480 |
| `PackerBenchmark.packAscii0800` | 478 ± 85 | 2,120 ± 25 | 1,600 |
| `PackerBenchmark.unpackAscii0800` | 502 ± 98 | 2,106 ± 46 | 1,888 |
| `PackerBenchmark.roundTripAscii0100` | 3,202 ± 167 | 308 ± 22 | 11,072 |
| `SliceBenchmark.unpackSliceAtStart` | 1,027 ± 26 | 956 ± 15 | 5,152 |
| `SliceBenchmark.unpackSliceFlushWithEnd` | 1,057 ± 224 | 973 ± 8 | 4,616 |
| `SliceBenchmark.unpackSliceNearEnd` | 7,127 ± 355 | 140 ± 1 | 70,528 |
| `PrinterBenchmark.formatAscii0100` | 9,624 ± 451 | 106 ± 10 | 18,504 |
| `PrinterBenchmark.unpackAndFormatAscii0100` | 10,759 ± 409 | 92 ± 10 | 22,472 |

Wire sizes for reference: ASCII 0100 is 159 bytes, compact 0100 is 92 bytes.

The two JMH modes are separate runs, so allocation per op can differ slightly between them (for example `formatAscii0100` measured 17,856 B/op in the first full run and 18,504 in this one; the JIT's escape analysis varies run to run). Differences of a few percent in bytes/op are noise; differences of 2x or more are real.

## After the slice fix

The `SliceNearEnd` row above is the "before". The packer no longer copies: `IsoPacker.unpack(spec, buffer, offset, length)` passes `offset + length` down to `MtiCodec`, `BitmapCodec` and `FieldCodec` as an exclusive `limit`, and every bounds check counts to that limit instead of `buffer.length`. Same machine and settings, re-run after the change (`SliceBenchmark` and `PackerBenchmark.unpackAscii0100`, AverageTime with `-prof gc`):

| Benchmark | Before ns/op | After ns/op | Before B/op | After B/op |
| --- | ---: | ---: | ---: | ---: |
| `SliceBenchmark.unpackSliceAtStart` | 1,027 | 992 | 5,152 | 4,976 |
| `SliceBenchmark.unpackSliceFlushWithEnd` | 1,057 | 1,046 | 4,616 | 4,616 |
| `SliceBenchmark.unpackSliceNearEnd` | 7,127 | 1,029 | 70,528 | 4,616 |
| `PackerBenchmark.unpackAscii0100` | 1,068 | 1,022 | 4,616 | 4,616 |

The worst slice position now costs the same as any other (about 1 microsecond, 4.6 KB), and the normal whole-buffer path is unchanged.

## Interpretation

1. A 0100 unpacks in about 1.1 microseconds and packs in about 2.1 microseconds on this machine. A single thread could parse roughly 900,000 such messages per second; the network, not the parser, will be the limit long before that.
2. Pack costs about twice unpack and allocates about 30% more. `pack` builds through a `ByteArrayOutputStream` (initial 256 bytes, grows by copying) plus a final `toByteArray()` copy, which unpack does not need.
3. ASCII versus compact is close: compact pack is about 6% slower than ASCII (within the error bars), compact unpack is about 15% slower but allocates about 25% less (3,480 versus 4,616 B/op). The BCD decode does more arithmetic per field, the ASCII decode builds more intermediate strings. Compact's gain is 67 fewer bytes on the wire (92 versus 159), not CPU time.
4. The 0800 echo (3 fields) costs about 0.5 microseconds either way: cost grows with the number of fields, not with message length alone.
5. Unpack allocates 4.6 KB for a 159-byte message, about 29 bytes of garbage per wire byte. The fixed `FieldValue[129]` array alone is about 530 bytes; the rest is per-field strings and value objects. This is the number to watch in Phase 4 (load generator) and Phase 7 (GC pressure).
6. The slice overload is cheap at the start of a buffer (copy of about 160 bytes, 1.03 microseconds) and free when the slice ends exactly at the buffer end (1.06 microseconds), but a slice that ends 1 byte before the end of a 64 KiB buffer copies about 64 KiB: 7.1 microseconds and 70.5 KB per message, about 7x slower and 15x the garbage. A link that reads frames into a large reusable buffer would hit this on every frame. This confirms the cost documented in step 5 (since removed, see "After the slice fix"); the benchmark first drew the wrong picture (see step 7 doc, "A benchmark bug worth keeping").
7. Formatting a message for a log costs about 9.6 microseconds, roughly 9x an unpack, and allocates about 18.5 KB (4x the unpack). Unpack plus format is 10.8 microseconds, so on a hot path a DEBUG log line costs about ten times the work of parsing the message. Guard it behind a level check (`if (log.isDebugEnabled())`), never format eagerly.
8. Round trip (pack then unpack) is 3.2 microseconds, close to the sum of the parts (2.1 + 1.1), as expected.

Not measured here: multi-threaded scaling, GC choice or heap size effects, the Netty transport, and cold-start latency (a fresh JVM runs these paths in the interpreter for the first thousands of calls, which is far slower than the numbers above).
