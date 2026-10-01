# SarthiFlow

SarthiFlow is a blueprint-driven telemetry correlation and metric orchestration platform for parsing raw log streams, correlating request/response events, aggregating metrics by configurable dimensions, and exporting them to observability backends.

## Tagline

Blueprint-driven telemetry correlation and metric orchestration

## Why SarthiFlow?

Most log-processing pipelines are tightly coupled to a single protocol or one message format. SarthiFlow separates the mechanics of data processing from the protocol blueprint itself so the same runtime can be reused across many event families.

### Highlights
- generic log ingestion for multiple formats
- blueprint-based parsing and field extraction
- request/response correlation using configurable keys and markers
- dimension-aware aggregation
- time-window metric calculation
- OTLP export support
- multi-worker parallel processing for fleet-scale ingestion
- extensible foundation for future central configuration management

## Architecture overview

```text
Raw files -> Reader -> blueprint parser -> correlation -> SQLite event store
                                                        |
                                   Aggregator/Sender <-+
                                       -> OTLP/HTTP
```

## Build And Run

Requirements: Java 11+ and Maven 3.8+.

Copy `src/main/resources/sarthiflow.conf.example` to `sarthiflow.conf`, then set the input paths and OTLP endpoint. The example contains no credentials; if the endpoint requires a bearer token, set the environment variable named by `sender.token-env`.

```powershell
mvn clean package
java -jar target/sarthiflow-1.0.0-reader.jar sarthiflow.conf
java -jar target/sarthiflow-1.0.0-sender.jar sarthiflow.conf
```

The Reader supports `reader.mode = batch` for one scan or `tail` for repeated polling. The sender supports the same modes through `sender.mode`; failed OTLP requests are retried on later polls. The previous combined application remains available as `target/sarthiflow-1.0.0-legacy.jar` during migration.

Both processes must currently access the same SQLite database on the same host/local filesystem. SQLite WAL is not a distributed database; deploying the pair on separate fleet hosts requires replacing the store with a server database or message broker.

## Example metric model

- latency_ms histogram
- p95, p99, avg, min, max
- tx_count
- success_count
- error_count
- success_rate
- error_rate
- throughput per minute

## Example blueprint

blueprint:
  name: payment-latency
  correlationKeyField: txn_id
  requestTypeField: direction
  requestValue: REQ
  responseTypeField: direction
  responseValue: RESP
  timestampField: ts
  dimensions:
    - channel
    - response_code
    - route
  granularity: 1m
  metrics:
    - latency_ms
    - tx_count
    - success_rate

## Intended use cases

- payment transaction latency monitoring
- request/response log correlation
- telecom and banking event pipelines
- API latency and SLA analytics
- operational telemetry pipelines for custom message formats

## Current status

This project is being shaped as a generic open-source blueprint platform for telemetry correlation and metric export.

## License

MIT

