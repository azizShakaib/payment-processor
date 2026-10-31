# Learning guide

Read in order. Each step doc starts with where the step sits in the whole network, explains the payments concept, then walks a real message through the code byte by byte.

```
Terminal ──> Acquirer ──> Switch ──> Issuer          (services, Phases 2–6)
                 \            |          /
                  iso8583-link  (sign-on, echo, keep-alive)   Phase 1 step 9
                  iso8583-core  (spec, codecs, bitmap, packer) Phase 1 steps 1–7
                  transport     (TCP framing, Netty)           Phase 0, Phase 1 step 8
                  common        (hex, PAN masking, STAN)       Phase 0, Phase 1 step 0
```

## Phase 0: Foundations
- [Lesson: TCP framing and ISO 8583 basics](lessons/phase-0.md)

## Phase 1: ISO 8583 library and connectivity
| Step | Doc | Topic |
| --- | --- | --- |
| 0 | [phase-1-step-0](steps/phase-1-step-0.md) | Card numbers (PAN), masking, Luhn, trace numbers (STAN) |
| 1 | [phase-1-step-1](steps/phase-1-step-1.md) | Message specs: why ISO 8583 is a framework, not a format |
| 2 | [phase-1-step-2](steps/phase-1-step-2.md) | Field encodings: ASCII, BCD, binary, length prefixes |
| 3 | [phase-1-step-3](steps/phase-1-step-3.md) | Bitmaps: how a message says which fields it carries |
| 4 | [phase-1-step-4](steps/phase-1-step-4.md) | MTI, message lifecycle, response codes, money |
| 5 | _next_ | Packing whole messages |

## Reference
- [Roadmap](ROADMAP.md) · [Progress](PROGRESS.md)
