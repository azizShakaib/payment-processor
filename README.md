# Payment Processor & Network Simulator

An ISO 8583 card payment network built from the socket layer up in Java 21: terminal simulator, acquirer gateway, network switch, issuer processor, clearing and settlement, and a simulated HSM. Each party runs as its own service and talks to the others over length-prefixed TCP, the way real card networks do.

This is a learning and portfolio project. Real-money concerns (PCI-DSS certification, scheme membership, sponsor banks) are simulated, not pursued.

## Status

| Phase | Focus | Status |
| --- | --- | --- |
| 0 | TCP framing, repo setup | Done |
| 1 | ISO 8583 library, Netty server, 0800 sign-on and echo | In progress: steps 0–2 of 10 done |
| 2 | Issuer authorization host, double-entry ledger | Planned |
| 3 | Network switch: BIN routing, STIP, reversals | Planned |
| 4 | Terminal simulator, load generator, latency | Planned |
| 5 | Clearing and settlement (Spring Batch) | Planned |
| 6 | Simulated HSM, PIN blocks, tokenization | Planned |
| 7 | Fraud rules, observability, chaos tests | Planned |
| 8 | Docs, demo | Planned |

## Architecture

```
[Terminal sim] <-> [Acquirer] <-> [Network switch] <-> [Issuer processor]
                                        |                     |
                              [Clearing & settlement]    [HSM sim]
```

Every arrow is ISO 8583 over TCP with a length header. Services are Spring Boot apps hosting a Netty TCP server; the shared libraries (`common`, `transport`, `iso8583-core`, `iso8583-link`) are plain Java with no framework.

### Phase 1 progress

| Step | What | Status |
| --- | --- | --- |
| 0 | `common`: PAN masking + Luhn, STAN generator | Done |
| 1 | `iso8583-core`: field spec model, `paysim-ascii-87` spec (31 fields) | Done |
| 2 | `iso8583-core`: field codecs (ASCII, BCD, binary, LL/LLL prefixes) | Done |
| 3 | Bitmap (primary/secondary) | Next |
| 4–6 | Message model, packer, masked pretty-printer | Planned |
| 7 | JMH benchmarks | Planned |
| 8–9 | Netty transport, `iso8583-link` sign-on/echo/keep-alive | Planned |

## Modules

| Module | Purpose |
| --- | --- |
| `common` | Hex dumps, PAN masking and Luhn check, STAN generator |
| `transport` | TCP framing (`FrameCodec`), echo server and client |
| `iso8583-core` | Field specs, field codecs; bitmap, message model and packer next |

## Run it

Requires Java 21. The Maven Wrapper (`mvnw`) downloads Maven on first run.

On Windows with antivirus HTTPS scanning (Avast, Kaspersky, corporate proxies), Java may reject the certificate. Point it at the Windows store for that shell: `$env:MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`.

```bash
./mvnw verify                                                    # compile and run all tests
./mvnw -q -pl iso8583-core -am test                              # just the ISO 8583 library
./mvnw -q -pl transport -am compile exec:java                    # echo an ISO 8583 0800 message with a 2-byte binary header
./mvnw -q -pl transport -am compile exec:java -Dexec.args=ASCII_4 # same, with a 4-digit ASCII header
docker compose up -d                     # Postgres for later phases
```

## Learning guide

Every build step has a doc explaining the payments concept, a byte-by-byte message walk-through and how to try it yourself. Start at the [docs index](docs/README.md).

- [Phase 0: TCP framing and ISO 8583 basics](docs/lessons/phase-0.md)
