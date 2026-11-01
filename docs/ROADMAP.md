# Roadmap

Read only the section for the phase in progress. Each phase lists modules, features, lesson topics, exercises and the done criteria.

## Modules (final layout)

| Module | Type | Phase | Purpose |
| --- | --- | --- | --- |
| `common` | lib | 0 | Hex, masking, money, clock, IDs |
| `transport` | lib | 0–1 | Framing, blocking and Netty servers and clients, connection management |
| `iso8583-core` | lib | 1 | Field specs, packager, bitmaps, message model, response codes |
| `iso8583-link` | lib | 1 | ISO 8583 over `transport`: network management, keep-alive, request/response matching |
| `issuer` | Spring Boot | 2 | Authorization host, cards, accounts, double-entry ledger, holds |
| `switch` | Spring Boot | 3 | BIN routing, connection pools, timeouts, STIP, reversals |
| `acquirer` | Spring Boot | 3 | Merchant-side gateway, terminal registry, forwards to switch |
| `terminal-sim` | app | 4 | POS traffic generator, scenarios, latency reporting |
| `clearing` | Spring Boot + Batch | 5 | Advices, EOD files, net settlement, reconciliation |
| `hsm-sim` | Spring Boot | 6 | PIN block translation, key hierarchy, MAC |
| `admin` (optional) | Spring Boot | 7 | Dashboards, BIN table and card admin |

Message flow: terminal → acquirer → switch → issuer, response back the same path. Clearing reads advices and events. Issuer and switch call `hsm-sim` for PIN work.

## Phase 0: Foundations (done)
- Maven multi-module (migrated from Gradle), Java 21, CI, Docker Compose with Postgres.
- Length-prefixed framing: 2-byte binary and 4-digit ASCII headers; max frame size; clean vs broken close.
- Blocking echo server (virtual threads) and client.
- Lesson: TCP byte streams, framing, Nagle, ISO 8583 overview (MTI, bitmaps, field types).
- Done when: split, glued and broken-frame tests pass; CI green.

## Phase 1: ISO 8583 library and connectivity (3 weeks)
Features:
- Field spec model: number, name, type (n, an, ans, b, z), length kind (FIXED, LLVAR, LLLVAR), max length, encoding (ASCII, BCD, binary), padding.
- Spec defined as data (a Java class or YAML), one default "paysim-ascii-87" spec.
- Primary and secondary bitmaps (hex ASCII and binary).
- Packer and unpacker; immutable `IsoMessage` with builder; `MTI` value type; typed accessors for common fields (2, 3, 4, 7, 11, 12, 13, 37, 38, 39, 41, 42, 49, 70).
- Clear errors naming the field and offset on bad input.
- Pretty-printer that masks field 2 and 35.
- Netty server and client in `transport`: length-field decoder and prepender, idle detection, reconnect with backoff.
- Network management: 0800/0810 sign-on (001), sign-off (002), echo (301); periodic echo keep-alive.
Lesson: spec-driven parsing, BCD vs ASCII, Netty pipeline vs blocking model, why links stay open.
Exercises: add a field definition; property-based round-trip tests; decode a message by hand from a hex dump; compare output with jPOS.
Done when: round-trip tests pass for every field, JMH benchmark recorded, client signs on and echoes over Netty.

Agreed build plan (2026-10-31). Commit after each green step; stop for review after each step.
0. `common`: `Pan.mask` (first 6 + last 4) and `Pan.isLuhnValid`, `Stan` (thread-safe, 000001–999999 wrap). Tests: masking edge cases, no PAN digits in errors, Stan wrap and concurrency. (Field 7/12/13 time formatting from an injected `Clock` moves to step 4.)
1. `iso8583-core` field model: `FieldType` (n, an, ans, b, z, x+n), `LengthKind`, `Encoding`, `FieldSpec` record, `MessageSpec` (Java builder), `Specs.PAYSIM_ASCII_87`. Tests: spec validation (2–128, no duplicates).
2. Field codecs per encoding (ASCII, BCD, binary) with padding; `IsoParseException(field, offset)`. Tests: every type x length kind round trip, too long, bad chars, odd-length BCD.
3. `Bitmap`: primary/secondary, hex ASCII and binary. Tests: bits 1/64/65/128, secondary auto-set.
4. `Mti` record, immutable `IsoMessage` + builder, typed accessors (2, 3, 4, 7, 11, 12, 13, 37, 38, 39, 41, 42, 49, 70), `ResponseCode` enum. Tests: unknown field rejected, 0100->0110.
5. `IsoPacker` pack/unpack. Tests: hand-checked hex for 0100/0110/0800, round trip every spec field, errors name field + offset.
6. `IsoPrinter`: masks 2, 35, 45, 52; truncates 55. Add `Pan.maskLenient` (never throws; malformed input prints only its length) so logging a bad message can't fail or leak. Test: output never contains a clear PAN, including malformed field 2. Lesson note: 6+4 on a 12-digit PAN hides only 2 digits; 8-digit BINs since 2022.
7. `benchmarks` module (JMH), outside the default build. Record pack/unpack throughput.
8. Netty in `transport`: decoder/encoder for BINARY_2 and ASCII_4 (custom), `NettyServer`, `NettyClient` (reconnect, backoff + jitter), `IdleStateHandler`. Tests: Phase 0 framing cases via `EmbeddedChannel`, reconnect. Never block the event loop. Transport logs frame lengths only, never payload hex (fix `EchoServer`'s FINER `Hex.dump` too); message-level logging goes through `IsoPrinter.safeDump`.
9. `iso8583-link`: sign-on/off/echo builders and responder, keep-alive, `PendingRequests` (STAN matching). Integration test: sign on, echo, server restart, reconnect, sign on again.
10. Lesson, LinkedIn draft, PROGRESS update.

## Phase 2: Issuer authorization host (3 weeks)
Features:
- Spring Boot app hosting the Netty server from Phase 1.
- Postgres schema (Flyway): card, account, card_account, ledger_entry, hold, auth_log.
- 0100/0110 handling: processing codes for purchase (00), cash (01), balance inquiry (31), refund (20).
- Rule chain: card exists, status, expiry, currency, available balance, per-transaction and daily limits, CVV/PIN placeholders.
- Response codes 00, 05, 14, 51, 54, 57, 61, 91, 96.
- Double-entry ledger; authorization creates a hold, never moves money.
- Idempotency key = STAN + RRN + terminal ID + transmission time; duplicates return the original response.
- Field 38 auth code generation; RRN handling.
- Admin REST: create card and account, view holds and ledger.
Lesson: auth vs capture, holds, double-entry, idempotency under retries, response codes.
Exercises: add a decline rule; prove a duplicate never creates a second hold; write a ledger balance invariant test.
Done when: approvals and declines correct, ledger always balances, duplicate test passes under concurrency.

## Phase 3: Network switch and acquirer (3 weeks)
Features:
- Switch: BIN table (Postgres + Redis cache), route by PAN prefix to an issuer endpoint.
- Connection pool per issuer, multiplexed in-flight requests matched by STAN + RRN.
- Per-request timeout; on timeout send 0400 reversal to the issuer and reply 91 or STIP.
- Stand-in processing (STIP) with simple limits when the issuer is down; 0120 advice to issuer when it returns.
- Reversals 0400/0410 and repeats 0401; store-and-forward queue for unacknowledged reversals.
- Acquirer: terminal and merchant registry, fields 41/42/43, forwards to switch, handles reversals from terminals.
- Message log with masked PANs for every hop.
Lesson: how card networks route, timeouts and the reversal problem, STIP, store-and-forward.
Exercises: add a new issuer and BIN range; kill an issuer mid-traffic and trace reversals; explain one orphaned-hold scenario.
Done when: issuer failure gives correct STIP responses and reversals with no orphaned holds.

## Phase 4: Terminal simulator and load generator (1–2 weeks)
Features:
- CLI and config-driven scenarios: purchase, refund, balance inquiry, decline, duplicate, timeout, reversal.
- Configurable rate, mix and duration; open-model load (fixed arrival rate) to avoid coordinated omission.
- HdrHistogram latency (p50, p99, p99.9) per hop and end to end; CSV export and a chart.
Lesson: latency measurement, coordinated omission, throughput vs latency.
Exercises: find and fix one bottleneck, with before and after numbers.
Done when: published latency graphs at a stated throughput.

## Phase 5: Clearing and settlement (2–3 weeks)
Features:
- 0220/0230 advices; completion of holds into ledger postings.
- Events to Kafka from switch and issuer.
- Spring Batch jobs: EOD clearing file per participant (fixed-width), net settlement per participant, interchange and fee calculation (simple flat model).
- Reconciliation job: auth log vs clearing vs ledger; mismatch report.
- Hold expiry job.
Lesson: auth vs clearing vs settlement, interchange, why reconciliation finds money.
Exercises: inject a mismatch and make reconciliation flag it.
Done when: a full day's traffic settles and reconciles to zero difference.

## Phase 6: Security and HSM simulator (2–3 weeks)
Features:
- `hsm-sim`: key hierarchy (LMK, ZMK, ZPK, PVK), TDES/AES, key check values.
- PIN block ISO format 0 and 4; translate PIN block between zones; PIN verify.
- MAC on messages (field 64/128).
- PAN tokenization or encryption at rest in the issuer; masked logs everywhere.
- mTLS between services.
- `docs/pci-mapping.md`: design mapped to PCI-DSS requirements.
Lesson: PIN blocks, key management, MACs, PCI-DSS scope reduction.
Exercises: translate a PIN block between two zone keys by hand.
Done when: no clear PAN or PIN stored or logged anywhere (enforced by a test that greps logs).

## Phase 7: Risk, observability, resilience (2 weeks)
Features:
- Fraud rules: velocity (count and amount per card per window, Redis), MCC blocks, country mismatch; score and decline code 59.
- Micrometer metrics, Prometheus, Grafana dashboards (TPS, approval rate, latency, STIP rate).
- OpenTelemetry tracing across all hops.
- Chaos tests: kill issuer, slow network, drop connections; Resilience4j where useful.
Lesson: rule engines, distributed tracing, designing for failure.
Exercises: add a velocity rule and test it under load.
Done when: a chaos run shows STIP and reversals working on the dashboard.

## Phase 8: Portfolio packaging (1 week)
- README with architecture diagram, run instructions, latency results, screenshots.
- ADRs in `docs/adr/` for key decisions (own packer, Netty in Spring, ledger design, STIP).
- Demo video script and recording; one-command `docker compose up` for the whole network.
- 5-minute interview walkthrough notes.
Done when: repo public, pinned, with demo link.

## LinkedIn schedule
| When | Topic | Format |
| --- | --- | --- |
| Kickoff | What I'm building and why | Text + architecture diagram |
| Phase 0 done | TCP is a byte stream: the framing bug every beginner hits | Text + code snippet |
| Phase 1 done | I wrote an ISO 8583 parser from scratch: bitmaps explained | Carousel |
| Phase 2 done | Double-entry ledgers and idempotency: why a retry must never charge twice | Text + diagram |
| Phase 3 done | I killed an issuer mid-traffic: STIP and reversals | Demo GIF |
| Phase 4 done | p99 latency of my processor and what moved it | Text + chart |
| Phase 5 done | Auth vs clearing vs settlement, with my own system | Carousel |
| Phase 6 done | PIN blocks and HSMs: how PINs stay secret in transit | Text + diagram |
| Phase 7 done | Chaos testing a payment network | Screenshot or GIF |
| Phase 8 done | Launch: repo, demo, lessons learned | Video + repo link |
