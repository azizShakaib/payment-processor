# Progress

Current phase: **1 (step 6 of 10)**; Phase 0 exercises run in parallel.
Next: Phase 1 step 6 (`IsoPrinter` + `Pan.maskLenient`). Note for step 7: `IsoPacker.unpack` slice overload copies buffer[0..end). Agreed plan in `docs/ROADMAP.md`, Phase 1.

| Phase | Status | Notes |
| --- | --- | --- |
| 0 Foundations | Code done, exercises pending | `common`, `transport`, Docker Compose, CI, lesson written |
| 1 ISO 8583 library | In progress | Plan agreed 2026-10-31 |
| 2 Issuer host | Not started | |
| 3 Switch + acquirer | Not started | |
| 4 Terminal sim + load | Not started | |
| 5 Clearing + settlement | Not started | |
| 6 Security + HSM | Not started | |
| 7 Risk + observability | Not started | |
| 8 Portfolio packaging | Not started | |

## What exists
- `common`: `Hex` (encode, decode, hex dump), `Pan` (mask 6+4, Luhn; no digits in errors), `Stan` (lock-free, 000001–999999 wrap).
- `iso8583-core` (spec model): `FieldType`, `LengthKind`, `Encoding`, `Padding`, `FieldSpec` (self-validating), `MessageSpec` (builder, O(1) lookup), `Specs.PAYSIM_ASCII_87` (31 fields). B fields: length in bytes, hex on the wire in ASCII specs.
- `iso8583-core` (codecs): `FieldValue` (Text/Binary, toString never shows content), `FieldCodec` pack/unpack for ASCII, BCD and binary, LL/LLL prefixes, padding kept on unpack; `IsoParseException` (field + absolute offset), `IsoPackException`. Errors never contain field values.
- `iso8583-core` (bitmap): immutable `Bitmap` (bit 1 automatic), `BitmapCodec` hex or binary; strict: flagged-but-empty secondary rejected. `FieldSpec` padding now type-checked.
- `iso8583-core` (model): `Mti` (+ class/function/origin enums, `responseMti`), immutable `IsoMessage` (builder validates at set time via `FieldCodec.validate`, typed accessors, `responseBuilder` copies echo fields), `ResponseCode`, `NetworkManagementCode`, `IsoDateTimes` (field 7 in UTC). `common`: `Money`.
- `iso8583-core` (packer): `IsoPacker` pack/unpack (MTI field 0 in errors, trailing bytes rejected, trusted construction path), `MtiCodec`. 0800 matches Phase 0 EchoDemo byte for byte.
- `transport`: `LengthHeader` (BINARY_2, ASCII_4), `FrameCodec` (readFully framing, max frame size, clean vs broken close), `EchoServer` (virtual thread per connection, TCP_NODELAY), `FrameClient`, `EchoDemo`.
- Tests: split, glued, one-byte-at-a-time, truncated header and payload, bad ASCII header, oversize frame, 50 concurrent clients, client dying mid-frame.
- `docker-compose.yml` (Postgres 16), GitHub Actions build, README, `docs/lessons/phase-0.md`.

## Decisions
- Portfolio first; ISO 8583 card payments only (ISO 20022 out of scope for now); 20 h/week.
- Spring Boot per service with Netty for TCP; plain-Java shared libraries; Spring Batch for clearing.
- Own ISO 8583 packer first, compared against jPOS later.
- 2026-10-31: Maven multi-module with Maven Wrapper (migrated from Gradle).
- `iso8583-link` module holds network management (sign-on, echo, keep-alive, STAN matching); `transport` stays message-agnostic.
- ISO 8583 specs are Java code (`MessageSpec.builder`), no YAML.
- Printer masks fields 2, 35, 45, 52 and truncates 55, not just 2 and 35.
- Local Windows builds need `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (Avast HTTPS scanning breaks Java TLS).

## LinkedIn
| Due | Post | Status |
| --- | --- | --- |
| 2026-10-20 | Kickoff: what I'm building and why | ? |
| after Phase 0 | TCP is a byte stream: the framing bug every beginner hits | Planned |
(Full schedule in `docs/ROADMAP.md`, section LinkedIn.)
