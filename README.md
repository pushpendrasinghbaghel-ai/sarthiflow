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

Raw logs / files
      ?
Reader service
      ?
Parser + blueprint extraction
      ?
Correlation engine
      ?
Raw event store
      ?
Aggregator by dimensions and time bucket
      ?
Metric store
      ?
OTLP / collector exporter

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

