# Phase 1 lesson: an ISO 8583 library and a live link

Index of all guides: [docs/README.md](../README.md). This lesson is the big picture across steps 0 to 9. Each section points to the step doc where the depth (and the byte-by-byte dumps) live. Read the step docs for the details, this page for how they fit together, then do the exercises at the end.

## 1. What Phase 1 built

Phase 0 gave us frames: a length header, a payload, and a blocking echo server. Phase 1 puts a real message model and a real link on top of that.

```
  +---------------------------------------------------------------+
  | iso8583-link   IsoLinkClient / IsoLinkServer                  |
  |                sign-on, echo, STAN matching, request futures  |
  +---------------------------------------------------------------+
  | transport      LengthHeader, FrameCodec (Phase 0, blocking)   |
  |                transport.netty: decoder, encoder, client,     |
  |                server, reconnect + backoff, idle detection    |
  +---------------------------------------------------------------+
  | iso8583-core   MessageSpec -> IsoPacker -> IsoMessage         |
  |                Bitmap, field codecs, IsoPrinter (masked)      |
  +---------------------------------------------------------------+
  | common         Pan (mask, Luhn), Stan, Money, Hex             |
  +---------------------------------------------------------------+
```

Dependencies point downwards only. `iso8583-core` knows nothing about sockets and `transport` knows nothing about ISO 8583: it moves opaque `byte[]` frames. `iso8583-link` is the only place that knows both, which is why it is the glue layer and the place where "a link has a life cycle" lives.

Step map: step 0 `common`; steps 1 to 6 `iso8583-core` (spec, codecs, bitmap, message model, packer, printer); step 7 benchmarks; step 8 Netty transport; step 9 the link. Later phases (issuer, switch, acquirer) are all consumers of this stack: none of them will touch bytes again.

## 2. The lesson topics

### 2.1 Spec-driven parsing

An ISO 8583 message has no self-describing structure. The bytes `0100F238...` only mean something because both sides agreed, out of band, that field 2 is "LLVAR, numeric, up to 19 digits" and field 4 is "12 digits, fixed". That agreement is the **spec**, and in this project it is data: `Specs.PAYSIM_ASCII_87` is a `MessageSpec` built from `FieldSpec` records (number, name, type, length kind, max length, encoding, padding). The packer contains no field-specific code at all; it walks the bitmap and asks the spec what comes next.

The consequence worth feeling in your gut: parsing is **sequential**. Field 3 starts where field 2 ended, and field 2's end depends on its length prefix. One wrong length kind or width in a spec shifts every later field, and the error shows up far from the cause (a "bad digit" in field 39 that is really a mistake in field 4). That is why `IsoParseException` carries the field number and the absolute offset, and why it never includes the value.

Depth: [step 1](../steps/phase-1-step-1.md) (spec model), [step 5](../steps/phase-1-step-5.md) (packer, spec mismatch error).

### 2.2 BCD versus ASCII

The same number can travel as text or as packed digits. Field 4 amount `000000001500` is 12 bytes in ASCII and 6 bytes in BCD (two digits per byte). Whole dialects differ: ASCII MTI and hex-text bitmaps (easy to read in a log) against BCD MTI and binary bitmaps (the "compact" form, 92 bytes against 159 for our sample 0100). Odd lengths in BCD need padding, and a LLVAR length prefix counts digits, not bytes; both are classic bug sources. Track 2 (field 35) uses the `D` separator and an `F` pad nibble.

The benchmark result is a useful counterweight: compact saves about 40% of the bytes but is not faster to parse. You choose BCD because a partner's interface spec says so, or because bytes cost money on a leased line, not because the CPU cares.

Depth: [step 2](../steps/phase-1-step-2.md) (codecs, side-by-side dumps), [step 3](../steps/phase-1-step-3.md) (bitmap, hex text or raw bytes).

### 2.3 The message model

`IsoMessage` is immutable, built through a builder, and bound to a spec: setting a field the spec does not define, or a value that breaks its type or length, fails at `set`, not at pack time. `Mti` is a value type that answers "is this a response?" and "which class?". Money is `long` minor units plus a currency, never a floating-point number. `responseBuilder()` encodes the echo rule: a 0110 repeats the request's fields 7, 11, 41 and so on so the sender can match it.

Depth: [step 4](../steps/phase-1-step-4.md).

### 2.4 Masking and PCI in practice

Phase 1 is where "never log a PAN" became code. `IsoPrinter.format` masks fields 2 and 35, hides 45 and 52, and prints only the length of field 55 because EMV data can contain the PAN and the Track 2 equivalent in tags the printer would otherwise have to parse. `IsoPrinter.safeDump` handles a frame that will not even unpack, and `Pan.maskLenient` never throws, so logging a malformed message can neither crash the handler nor leak. Note that 6 + 4 on a 12-digit PAN hides only two digits: the masking rule has limits you should know.

Depth: [step 0](../steps/phase-1-step-0.md), [step 6](../steps/phase-1-step-6.md).

### 2.5 Measuring instead of guessing

A 0100 unpacks in about 1.1 microseconds and packs in about 2.1 on the benchmark machine; `docs/benchmarks/phase-1.md` has the table and the machine description. The more instructive result is the one that went wrong: unpacking a message from the middle of a larger buffer copied the buffer (7.1 microseconds, 70 KB per operation) until the benchmark exposed it and we passed an explicit `limit` down instead. A benchmark finding led to a code change and a second measurement; that loop is the skill.

Depth: [step 7](../steps/phase-1-step-7.md), [benchmarks](../benchmarks/phase-1.md).

### 2.6 Netty pipeline versus the blocking model

Phase 0's `EchoServer` is one virtual thread per connection running `read, write` in a loop: easy to reason about, and with virtual threads it scales further than you would expect. The Netty version inverts it: a few event-loop threads own all sockets, and your code is a pipeline of handlers fed by events. The price is a rule you must never break: **never block the event loop**. The reward is the transport features this phase needed: `IdleStateHandler` for idle detection, a decoder that copes with arbitrary chunking, reconnect with exponential backoff and jitter (`Backoff`).

This project uses both on purpose. Netty owns the sockets and the framing; each connection's frames are handed to a `SerialExecutor` that runs them one at a time, in order, on virtual threads. Handler code can then be plain blocking code without stalling the event loop or other connections.

Depth: [step 8](../steps/phase-1-step-8.md).

### 2.7 Why links stay open: sign-on, echo, matching

A card network link is not request-per-connection. It is one long-lived TCP connection carrying thousands of multiplexed requests, with its own life cycle: connect, **sign on** (0800, code 001), carry traffic, **echo** (0800, code 301) during silence, sign off or drop, reconnect, sign on again. Echo exists because TCP "connected" only proves the far kernel answers, not that the application does. Matching exists because responses are not ordered: a response finds its request through `MatchKey` (message class, STAN, field 7, institution, terminal), and a timeout is an *unknown outcome*, not a decline.

Depth: [step 9](../steps/phase-1-step-9.md).

## 3. Code walkthrough: one 0100, there and back

Follow one authorization request through the stack. Open each file as you read. Everything below is a real class or method; use the step docs for the bytes.

**1. The caller.** `IsoLinkClient.request(IsoMessage)` (with a timeout overload). It refuses unless the link state is `SIGNED_ON`, failing the returned future with `LinkNotReadyException`: nothing was sent, so retrying is safe.

**2. Registering to hear the answer.** `PendingRequests.register(request, timeout)` computes `MatchKey.of(request)`, stores a `CompletableFuture` in a `ConcurrentHashMap` with `putIfAbsent` (a duplicate key in flight throws, never overwrites), and schedules a timeout task. Registration happens *before* sending: otherwise a fast response could arrive before anyone is waiting for it.

**3. Packing.** `IsoLinkClient.sendOrFail` calls `LinkSupport.send`, which calls `IsoPacker.pack(message)`: MTI, derived bitmap, then each present field through the spec's codec, in field order. The result is a `byte[]` payload, still unframed.

**4. Framing and writing.** `LinkSupport.send` hands the bytes to `FrameConnection.send`, which writes them into the Netty channel. `LengthHeaderFrameEncoder` (a `MessageToByteEncoder`) puts the length header and payload in one buffer, so they leave in one write.

**5. Server side, decoding.** On the other end, `LengthHeaderFrameDecoder` accumulates whatever chunks TCP delivers and emits a frame only when a full length-prefixed payload is present. Then `FrameChannelHandler.channelRead` does *not* run the application code: it submits `handler.onFrame(...)` to the connection's `SerialExecutor`.

**6. Off the event loop.** `SerialExecutor.execute` queues the task and keeps at most one drain loop running on the delegate executor (virtual threads), so frames of one connection are handled strictly in order while the event loop is already free.

**7. Unpacking.** `IsoLinkServer.onFrame` calls `LinkSupport.unpack`, which wraps `IsoPacker.unpack(spec, frame)` and turns a parse failure into a logged, masked event rather than an exception.

**8. Routing.** `m.mti().isResponse()` is false; the message class is not network management; so `IsoLinkServer.business` runs. If the link is not signed on it answers 39=91 itself. Otherwise it submits to `handlerExecutor` (a virtual-thread-per-task executor), so a slow `IsoRequestHandler.handle` delays neither echo answers nor other requests.

**9. The reply.** The handler returns an `IsoMessage`, typically built with `request.responseBuilder().responseCode(...)`. `LinkSupport.runHandler` converts a thrown exception into 39=96 and logs only the exception class. `LinkSupport.sendQuietly` packs and sends it back through steps 3 and 4 in the other direction.

**10. Client side, completing.** The client's `LengthHeaderFrameDecoder` and `SerialExecutor` deliver the frame to `IsoLinkClient.onFrame`, which unpacks it; `m.mti().isResponse()` is true, so `PendingRequests.complete(response)` removes the entry by `MatchKey.of(response)` and completes the caller's future. If nothing matches (late or unsolicited), `LinkListener.onUnmatchedResponse` is told instead of the response being dropped.

Questions to answer while you read: which of these steps can fail without the other side ever knowing? Where would a wrong spec on one side first show up? What happens to the entry in step 2 if the connection drops before step 10?

## 4. What we would do differently, and known limits

Honest list; some are Phase 1 simplifications, some are real design questions.

- **One spec dialect per link, and only two exercised.** `PAYSIM_ASCII_87` and the BCD/binary variant built in tests. No per-network dialect files, no tertiary bitmap, no composite fields (field 48, 55 and 127 are opaque to the library).
- **No EBCDIC.** Common on mainframe-hosted interfaces; we deliberately did not implement it.
- **Unbounded per-connection queue.** `SerialExecutor` buffers whatever arrives. A slow handler plus a fast sender grows memory; a real switch needs back-pressure or a bounded queue with load shedding.
- **Echo is client-side only.** The server answers echoes but never starts them, so a server cannot notice a dead client on a quiet link except through idle detection. Real links usually have both sides probing.
- **Sign-on is minimal.** No key exchange, no cutover, no partner authentication, no enforcement of one live connection per partner.
- **STAN matching is best effort.** `MatchKey` makes collisions unlikely, not impossible; production switches add far more state (see step 9, section 2.3).
- **No TLS or tunnel.** Real card-network traffic runs over private lines or IPsec/TLS. Everything here is clear TCP on localhost.
- **Windows burst-connect quirk.** On this Windows machine, many clients connecting at the same instant to a loopback listener are occasionally refused with a `setsockopt` error. Tests connect sequentially and then run traffic concurrently. It is an environment quirk, not a protocol property, but test code that hides an environment quirk deserves a comment (it has one).
- **Benchmarks are one run on one desktop.** Relative numbers only.
- **Timeout handling stops at the future.** The link tells you "unknown outcome"; deciding to reverse (04xx) is Phase 3.

## 5. Exercises

Do these in order, or pick by interest. Send me your code, test output and a short written explanation for each; I review, I do not solve. Run `./mvnw -q verify` before sending.

### Exercise 1: add a field definition

**Goal.** Add field 15 (settlement date) to the default spec and carry it through the whole stack.

**Instructions.**
1. Look up what field 15 is in ISO 8583:1987 (type, length, format). Add it to `Specs.PAYSIM_ASCII_87` with the right `FieldSpec`.
2. Add a typed accessor and a builder setter on `IsoMessage` in the style of the existing ones (for example the way field 13 is handled), including the validation you think a date field deserves.
3. Write a round-trip test for it in both the ASCII and the compact dialect, and a golden-bytes test with hex you worked out *before* running the code.
4. Decide what `IsoPrinter` should do with it, implement it, and test it.

**Send for review.** The diff, your golden hex with your reasoning, and a sentence on what the existing sweep tests did or did not catch about your change.

### Exercise 2: property-based round trips

**Goal.** Replace "a few examples" with "thousands of generated cases".

**Instructions.**
1. Add `net.jqwik:jqwik` as a test dependency of `iso8583-core` yourself (pick a current version; the parent pom manages versions for other test libraries, follow that convention).
2. Write generators for valid `IsoMessage`s over `PAYSIM_ASCII_87` and over the compact spec: random subset of fields, random valid values respecting type and length.
3. State and test these properties: (a) unpacking a packed message gives an equal message; (b) packing an unpacked valid frame gives the same bytes; (c) unpacking arbitrary random bytes never throws anything except `IsoParseException`; (d) the printer output for any generated message never contains the generated clear PAN.
4. If a property fails, jqwik will shrink the counterexample. Do not "fix" the test first.

**Send for review.** The tests, how many tries you ran, and anything jqwik found (including bugs in your generators).

### Exercise 3: decode a message by hand

**Goal.** Read the wire format with your own eyes, no code.

Below is a frame payload (no length header) packed with the **compact** dialect: BCD MTI, 8-byte (or 16-byte) binary bitmap, BCD for numeric and Track 2 fields, raw bytes for binary fields, ASCII for the rest. The field definitions are those of `PAYSIM_ASCII_87`; only the encodings differ (the helper that builds the compact spec is `PackerSupport.compact` in the `iso8583-core` tests).

```
0420F238000108808000000000400000000016550000555555555900000000000002375011010930450482131030451101070432109836333035303030343832313341544D30303034320978020004821311010930450000432109800000000000
```

**Instructions.**
1. On paper (or a text file), split it into MTI, bitmap(s) and fields. Work out which fields are present from the bitmap bits.
2. For each field, apply the spec's length kind and encoding, and write down name, raw bytes and decoded value.
3. Note anything that surprised you: length prefixes, padding, anything you had to reason about twice.
4. Only after you have written your answer, unpack it with the library and compare.

**Send for review.** Your hand decode (before running anything), then the library's output, and a list of every difference.

### Exercise 4: compare with jPOS

**Goal.** Find out whether our bytes agree with the most widely used Java ISO 8583 library, and explain every difference.

**Instructions.**
1. Add jPOS as a test-scope dependency of a scratch module or of `iso8583-core` (your choice; justify it).
2. Configure a jPOS packager equivalent to our ASCII dialect for the fields in the sample 0100 from `PackerSupport.sample0100` (a handful is enough: 2, 3, 4, 7, 11, 41 for example). Build the same message in jPOS.
3. Compare packed bytes with `IsoPacker.pack` for the same values, field by field and then whole message.
4. For each difference (there will probably be some, perhaps in the bitmap, the MTI, the padding or the length prefixes), find out *why* and whether it is a configuration choice, a default, or a bug on one side.

**Send for review.** The comparison test or script, a table of every difference with its cause, and your view on which differences matter on a real link.

### Exercise 5: watch reconnect and backoff

**Goal.** See the link life cycle under stress and describe it precisely.

**Instructions.**
1. Run `LinkDemo` (or write a short main) with an `IsoLinkServer` and an `IsoLinkClient` using a short `echoInterval` and a custom `Backoff`.
2. Add a `LinkListener` that prints a timestamped line for every state change.
3. Stop the server, wait several seconds, start it again. Record the timestamps of each reconnect attempt.
4. Repeat with `answerEchoes(false)` on the server instead of stopping it.
5. Compare the observed delays with `Backoff.ceiling(attempt)`; explain the jitter.

**Send for review.** The raw timeline for both experiments, your prediction before running each, and an explanation of any gap between prediction and result.

### Exercise 6: server-initiated echo, test first

**Goal.** Close one of the limits in section 4 using failing-test-first.

**Instructions.**
1. In `IsoLinkIntegrationTest` style, write a failing test that states the behaviour: a server configured with an echo interval sends a 0800/301 to a signed-on client on a quiet link, the client answers it, and a client that stops answering is dropped by the server after a configured number of misses. Show the test failing for the right reason before you write any main code.
2. Implement it in `IsoLinkServer`. Think about which STAN source the server uses, how its pending echoes are matched, and how it avoids blocking the callback thread.
3. Check that the existing tests still pass and decide whether your change needs a new test for the client side.

**Send for review.** The failing test output, the diff, and a paragraph on the concurrency choices you made.

## Key terms

- **Spec (`MessageSpec`):** the table of field definitions both sides agree on; see [step 1](../steps/phase-1-step-1.md).
- **Field type (n, an, ans, b, z, x+n):** which characters or bytes a field may contain.
- **LLVAR / LLLVAR:** variable-length field with a 2 or 3 digit length prefix.
- **BCD:** binary-coded decimal, two digits per byte; see [step 2](../steps/phase-1-step-2.md).
- **Dialect:** a spec's encoding choices (ASCII or BCD MTI, hex-text or binary bitmap, and so on).
- **Bitmap (primary, secondary):** bits saying which fields are present; see [step 3](../steps/phase-1-step-3.md).
- **MTI:** message type indicator, four digits; see [step 4](../steps/phase-1-step-4.md).
- **STAN:** system trace audit number, field 11; unique per sender, wraps at 999999.
- **PAN / masking:** card number; shown as first 6 + last 4 only; see [step 0](../steps/phase-1-step-0.md) and [step 6](../steps/phase-1-step-6.md).
- **Golden message:** a hand-checked byte sequence used as a test fixture.
- **JMH:** the Java Microbenchmark Harness; see [step 7](../steps/phase-1-step-7.md).
- **Event loop:** the few Netty threads that own all sockets; never block them. See [step 8](../steps/phase-1-step-8.md).
- **Backoff and jitter:** growing, randomised delays between reconnect attempts so clients do not stampede.
- **Idle detection:** acting when a connection has been silent for too long.
- **Sign-on / sign-off / echo test:** 0800 network management with codes 001, 002, 301; see [step 9](../steps/phase-1-step-9.md).
- **Match key:** the fields that tie a response to its request on a multiplexed link.
- **Unknown outcome:** what a timeout really means; the follow-up is a reversal.
