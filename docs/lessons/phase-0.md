# Phase 0 lesson: TCP framing and ISO 8583 basics

Read this before the exercises at the end. It explains the problem Phase 0 solves, then walks through the code that solves it.

## 1. TCP has no messages

When you call `out.write(bytes)` on a socket, TCP promises only that the same bytes arrive in the same order. It does not promise they arrive in the same chunks. The receiver's `in.read(buf)` can return:

- **Part of a message.** You sent 55 bytes; the first read returns 20.
- **More than one message.** You sent two messages quickly; one read returns both, glued together.
- **One message.** Common on localhost, which is why the bug hides during development and appears in production.

```
What you sent:   [msg A          ][msg B    ]
What read() may return:
  case 1:        [msg A   ]
                          [   ][msg B    ]       <- A split in two
  case 2:        [msg A          ][msg B    ]    <- A and B glued
```

So the receiver needs a rule for where one message ends. There are three common answers: a delimiter (like `\n` in HTTP headers), fixed-size messages, or a length prefix. ISO 8583 links use a length prefix, because messages contain binary data where any byte value, including a newline, can appear.

## 2. Length headers

Before each message, the sender writes its length. The receiver reads the header, then reads exactly that many bytes.

| Style | 55 bytes on the wire | Max payload | Where you see it |
| --- | --- | --- | --- |
| 2-byte binary, big-endian | `00 37` | 65,535 | Most switch and host links |
| 4-digit ASCII | `30 30 35 35` ("0055") | 9,999 | Older hosts, links that are read by humans |

"Big-endian" means the most significant byte comes first. 55 is `0x0037`, so the bytes are `0x00` then `0x37`. This is also called network byte order.

Which style a link uses is agreed in the interface spec between the two parties. Getting it wrong is the classic first-day integration bug: the receiver reads "08" from the MTI as a length and waits forever.

## 3. The code

### `LengthHeader`

An enum with one constant per header style. Each knows its size, its maximum, and how to encode and decode a length. Look at `BINARY_2.encode`:

```java
return new byte[] {(byte) (length >>> 8), (byte) length};
```

`length >>> 8` shifts the high byte down; the cast keeps the low 8 bits. Decoding reverses it, and the `& 0xFF` matters: Java bytes are signed, so `(byte) 0xC8` is -56, and without the mask a length of 200 would decode wrong.

### `FrameCodec.read`, the heart of Phase 0

```java
int first = data.read();
if (first == -1) return null;          // peer closed cleanly between messages
data.readFully(head, 1, head.length - 1);
int length = header.decode(head);
if (length > maxFrameLength) throw ...; // protect memory
byte[] payload = new byte[length];
data.readFully(payload);               // loops until all bytes arrive
```

Three ideas to take from this:

1. **`readFully` instead of `read`.** `readFully` keeps calling `read` until the buffer is full, or throws `EOFException` if the stream ends first. This one call solves the split-message problem.
2. **Clean close versus broken close.** Reading one byte first lets us tell "the peer hung up between messages" (normal, return `null`) from "the peer hung up in the middle of a message" (broken, throw `FramingException`).
3. **A maximum frame size.** Without it, a corrupt header saying 65,535 would make every connection allocate 64 KB, and a hostile peer could exhaust memory. Real systems always cap it.

### `FrameCodec.write`

Header and payload are copied into one array and written in one call. Two writes would also be correct, but cost an extra system call, and combined with Nagle's algorithm (below) can add delay.

### `EchoServer`

- **Virtual thread per connection.** Each connection runs a simple blocking loop: read a frame, write it back. Virtual threads make this cheap even with thousands of connections. Phase 1 replaces it with Netty so you can compare the two models.
- **Long-lived connections.** The loop runs until the peer disconnects. Payment links connect once at sign-on and then carry thousands of messages; opening a connection per message would add a TCP handshake to every authorization.
- **`setTcpNoDelay(true)`** disables Nagle's algorithm, which holds back small writes hoping to batch them. Payment messages are small and latency-sensitive, so every processor turns it off.
- **On `FramingException`, drop the connection.** Once framing is broken, the reader cannot know where the next message starts. Closing and letting the peer reconnect is the only safe recovery.

### Tests worth reading

- `FrameCodecTest.reassemblesAFrameDeliveredOneByteAtATime` uses a stream that returns one byte per `read` call: the worst case TCP is allowed to produce.
- `EchoServerTest.reassemblesAFrameSentInDelayedPieces` does the same over a real socket, with sleeps so the pieces really arrive separately.
- `EchoServerTest.survivesAClientThatDiesMidFrame` sends a header promising 100 bytes, sends 3, and hangs up. The server must log it, close that connection, and keep serving others.

## 4. ISO 8583 in one page

An ISO 8583 message is the payload inside the frame. It has three parts:

```
[MTI][bitmap(s)][data elements, in field-number order]
```

**MTI**, four digits:

| Digit | Meaning | Example values |
| --- | --- | --- |
| 1 | Version | 0 = 1987, 1 = 1993 |
| 2 | Class | 1 = authorization, 2 = financial, 4 = reversal, 8 = network management |
| 3 | Function | 0 = request, 1 = response, 2 = advice, 3 = advice response |
| 4 | Origin | 0 = acquirer, 1 = acquirer repeat |

So 0100 is an authorization request, 0110 its response, 0800 a network management request.

**Bitmap.** 64 bits, one per field. Bit 1 is the leftmost bit of the first byte. If bit 1 is set, a secondary bitmap follows for fields 65 to 128. In ASCII encoding the 8 bitmap bytes are written as 16 hex characters.

**Field types:**

| Type | Meaning | Example |
| --- | --- | --- |
| Fixed | Always the same length | Field 11 (STAN): 6 digits |
| LLVAR | 2-digit length, then data | Field 2 (PAN): `16` then 16 digits |
| LLLVAR | 3-digit length, then data | Field 48: `012` then 12 characters |

Only fields whose bit is set appear, in field-number order, with no separators. The parser knows where each ends from the field's type and length, which is why Phase 1 is built around a field-spec table.

**Worked example** (the message `EchoDemo` sends):

```
0800 8220000000000000 0400000000000000 1015042100 000001 301
```

- `0800` network management request
- `82` = `1000 0010`: bit 1 (secondary bitmap present), bit 7
- `20` = `0010 0000`: bit 11
- secondary `04` = `0000 0100`: bit 70
- field 7 `1015042100` transmission date and time, MMDDhhmmss
- field 11 `000001` STAN
- field 70 `301` network management code: echo test

## 5. Exercises

Do these in order. Send me your answers and code for review.

1. **Write the 0810 response by hand.** It echoes fields 7, 11 and 70 and adds field 39 = `00`. Work out the new primary bitmap. Hint: field 39 lives in the fifth byte of the bitmap.
2. **Run the demo with both headers.** `./mvnw -q -pl transport -am compile exec:java` and the same with `-Dexec.args=ASCII_4`. Explain why the length header shows `0037` in one case and `30303535` in the other.
3. **Break it on purpose.** In `FrameCodec.read`, replace `data.readFully(payload)` with `data.read(payload)`. Run the tests. Which fail, and why? Revert afterwards.
4. **Mismatched headers.** Start an `EchoServer` with `BINARY_2` and connect a `FrameClient` using `ASCII_4`. Predict what happens before running it, then explain what you see.
5. **Add a test.** Write a test showing that a frame of exactly `maxFrameLength` bytes is accepted and one byte more is rejected.

## Key terms

- **Framing:** splitting a byte stream into messages.
- **Length prefix:** a header giving the size of the message that follows.
- **Network byte order:** big-endian, most significant byte first.
- **Nagle's algorithm:** TCP batching of small writes; turned off with `TCP_NODELAY`.
- **MTI:** message type indicator, the first four digits of an ISO 8583 message.
- **STAN:** system trace audit number, field 11; identifies a message on a link.
