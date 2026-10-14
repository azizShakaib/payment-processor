# Payment Processor & Network Simulator

An ISO 8583 card payment network built from the socket layer up in Java 21: terminal simulator, acquirer gateway, network switch, issuer processor, clearing and settlement, and a simulated HSM. Each party runs as its own service and talks to the others over length-prefixed TCP, the way real card networks do.

This is a learning and portfolio project. Real-money concerns (PCI-DSS certification, scheme membership, sponsor banks) are simulated, not pursued.

## Status

| Phase | Focus | Status |
| --- | --- | --- |
| 0 | TCP framing, repo setup | Done |
| 1 | ISO 8583 library, Netty server, 0800 sign-on and echo | Next |
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

Every arrow is ISO 8583 over TCP with a length header. Services are Spring Boot apps hosting a Netty TCP server; the shared libraries (`transport`, `iso8583-core`) are plain Java with no framework.

## Modules

| Module | Purpose |
| --- | --- |
| `common` | Shared utilities (hex dumps) |
| `transport` | TCP framing (`FrameCodec`), echo server and client |

## Run it

Requires Java 21. The Gradle wrapper downloads Gradle on first run.

```bash
./gradlew build                          # compile and run all tests
./gradlew :transport:run                 # echo an ISO 8583 0800 message with a 2-byte binary header
./gradlew :transport:run --args=ASCII_4  # same, with a 4-digit ASCII header
docker compose up -d                     # Postgres for later phases
```

## Lessons

Each phase has a write-up explaining the concepts and the code:

- [Phase 0: TCP framing and ISO 8583 basics](docs/lessons/phase-0.md)
