# SarthiFlow Implementation Plan

## Product boundary

SarthiFlow is a generic, blueprint-driven pipeline. ISO8583 is delivered as one built-in reader blueprint; message formats, field names, response markers, success rules, dimensions, and timeouts belong to configuration rather than the shared processing core.

The runtime consists of two independently deployable programs that may run in parallel:

1. **Reader**: watches configured sources, parses with a blueprint, correlates events, and durably stores normalized raw events and completed transactions. It does not export metrics.
2. **Aggregator/Sender**: reads durable completed transactions, aggregates by configured dimensions and time windows, persists export progress, and sends metrics to an OTLP-compatible endpoint. It can be scaled independently from readers.

Both programs initially share a local durable database contract. A later fleet/control-plane UI is explicitly out of scope for this phase; configuration and versioning should leave room for it.

## Feature roadmap

| Iteration | Feature | Acceptance criteria | Status |
|---|---|---|---|
| 1 | Generic blueprint and correlation core | Validate blueprint fields; normalize events; correlate by configured key/type/timestamp; reject orphan, expired, and negative-latency pairs; classify outcomes only by configured rules. | Complete |
| 2 | Blueprint-driven parsers | Support JSON events and regex/text extraction through one parser contract; ship ISO8583 as a built-in blueprint/adapter; validate malformed input without stopping a file. | Complete |
| 3 | Durable shared event store | Persist raw events, pending correlation state, completed transactions, and reader checkpoints; make writes idempotent across restarts. | Complete |
| 4 | Standalone Reader program | Add reader entry point, bounded file-worker pool, tail/batch modes, rotation/truncation handling, and safe checkpoint commits. | Planned |
| 5 | Metric model and aggregation | Define histogram, counter, and rate semantics; aggregate latency samples and outcomes by configured dimensions/window; test boundaries, empty groups, and high cardinality. | Planned |
| 6 | Standalone Aggregator/Sender program | Independently poll durable completed events, persist bucket/export state, retry transient failures, and avoid duplicate exports where possible. | Planned |
| 7 | OTLP delivery and security | Emit correctly typed OTLP metrics, configure endpoint/auth/TLS through environment or secret providers, and validate with a local collector. | Planned |
| 8 | Operations and integration validation | Add health/status reporting, shutdown behavior, deployment examples, repeatable end-to-end tests, and Solaris/JVM compatibility checks. | Planned |
| 9 | Fleet configuration foundation | Document versioned, reloadable local configuration and compatibility boundaries for the future central UI; do not build the UI in phase 1. | Deferred (Phase 2) |

## Iteration rule

Each iteration should add a small production slice, focused unit tests, and a runnable verification command before moving to the next row. The existing ISO8583 application remains the compatibility reference until the new Reader and Aggregator/Sender paths pass end-to-end validation.
