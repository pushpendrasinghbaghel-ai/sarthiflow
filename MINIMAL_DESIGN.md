# Minimal Design Philosophy

**Updated Architecture:** Shift processing from Java → Dynatrace DQL

---

## The Shift

### ❌ Original (Over-Engineered)
```
Java: Parse → Correlate → Aggregate → Percentiles → Export Metrics
```
- 7 classes
- Complex metrics calculation
- Pre-aggregated by channel, response-code
- Hard to change: new dimension = code change

### ✅ Minimal (Recommended)
```
Java: Parse → Correlate → Calculate TAT → Export Raw Events
Dynatrace: Ingest → DQL Aggregation → Dashboards
```
- 4 classes (removed: TATMetricsExporter + metrics calculation)
- Simple event export
- All dimensions added to event JSON
- Flexible: new aggregation = DQL query (no code change)

---

## What Java Does Now (Minimal)

| Step | Class | Do This |
|------|-------|---------|
| 1 | ISO8583Parser | Extract fields from multiline logs |
| 2 | CorrelationStore | Match request/response by STAN+PAN |
| 3 | TATTransaction | Calculate `tat_ms = response_time - request_time` |
| 4 | EventExporter | Convert to JSON event, send to Dynatrace |

**That's it. 4 responsibilities. No aggregation.**

---

## What Dynatrace Does Now (Everything Else)

| Dimension | DQL Query | Example |
|-----------|-----------|---------|
| By Channel | `stats avg(tat_ms) by channel` | UPI avg=245ms, CARD avg=310ms |
| By Response Code | `stats count, avg(tat_ms) by response_code` | "000" avg=200ms, "001" avg=5000ms |
| Percentiles | `percentile(tat_ms, 95), percentile(tat_ms, 99)` | p95=1200ms, p99=2100ms |
| Trends | `timeseries avg(tat_ms), by: {channel}` | Graph over 24h |
| Outliers | `filter tat_ms > 5000` | High-TAT transactions |
| Success Rate | `count(if(response_code=="000", 1)) / count()` | 99.2% success |

**All done via DQL in Dynatrace. Reusable. No code change.**

---

## Raw Event JSON Format

Instead of pre-aggregated metrics, send **one raw event per transaction**:

```json
{
  "timestamp": 1725088946721,
  "transaction_id": "661765234767",
  "tat_ms": 186,
  "channel": "UPI",
  "response_code": "000",
  "pan_masked": "****0026",
  "request_time": "2026-09-08T12:02:26.721Z",
  "response_time": "2026-09-08T12:02:26.907Z",
  "status": "matched"
}
```

**Benefits:**
- ✅ Minimal Java code (just serialize object to JSON)
- ✅ All dimensions available (can query any field in Dynatrace)
- ✅ Future dimensions: add fields without code change
- ✅ Reusable: same data, infinite analysis possibilities

---

## Where to Send Events

### Option 1: Dynatrace Logs API (Recommended)
```
POST https://<TENANT>.managed.apps.dynatrace.com/api/v1/logs/ingest
Header: Authorization: Bearer <TOKEN>
Content-Type: application/json

[
  {
    "timestamp": 1725088946721,
    "transaction_id": "661765234767",
    "tat_ms": 186,
    "channel": "UPI",
    ...
  }
]
```

Events appear as structured logs, queryable via DQL.

### Option 2: Bindplane HTTP Receiver
```
POST http://bindplane-host:8888/v1/logs
Content-Type: application/json

[
  {JSON event},
  {JSON event}
]
```

Bindplane forwards to Dynatrace (or stores locally).

### Option 3: Dynatrace Events API
```
POST https://<TENANT>.managed.apps.dynatrace.com/api/v2/events/ingest
```

For custom events (different structure, but same concept).

---

## Comparison: What Changed

| Aspect | Original | Minimal |
|--------|----------|---------|
| **Java Classes** | 7 | 4 |
| **Lines of Code** | ~800 | ~400 |
| **Metrics Export** | Pre-aggregated JSON | Raw event JSON |
| **Channel Grouping** | In Java (hardcoded) | In Dynatrace DQL (flexible) |
| **Percentiles** | In Java | In Dynatrace DQL |
| **New Dimension** | Code change + rebuild | DQL query (instant) |
| **Extensibility** | Hard | Easy |
| **Performance** | Medium | High (less processing) |
| **Maintenance** | Higher (complex logic) | Lower (simple export) |

---

## Example: Add New Dimension (Merchant ID)

### Original (Over-Engineered)
1. Add `merchant_id` field to ISO8583Message
2. Modify TATMetricsExporter to create merchant-level metric
3. Add new aggregation method
4. Rebuild & deploy JAR
5. 30 minutes of work

### Minimal (Recommended)
1. Add `merchant_id` to raw event JSON (1 line of code)
2. Run DQL query in Dynatrace:
   ```dql
   stats avg(tat_ms) by merchant_id
   ```
3. Done (5 seconds)

---

## Dynatrace DQL Examples (Post-Ingest)

### TAT by Channel & Response Code
```dql
fetch logs
| filter response_code != null
| stats avg(tat_ms), percentile(tat_ms, 95) as p95 by channel, response_code
| sort p95 desc
```

### High-TAT Transactions (Outlier Detection)
```dql
fetch logs
| filter tat_ms > (timeseries avg(tat_ms) + 2 * stddev(tat_ms))
| fields transaction_id, tat_ms, channel, response_code
```

### Success Rate Trend (Hourly)
```dql
timeseries count(if(response_code == "000", 1)) / count() as success_rate by channel
```

### Unmatched/Timeout Transactions
```dql
fetch logs
| filter status == "timeout"
| stats count by channel
```

---

## Configuration Change

### Before
```conf
iso8583 {
  export.interval.seconds = 300
  # Aggregation happens every 5 minutes
}
```

### After
```conf
iso8583 {
  export.interval.seconds = 60  # Can be more frequent (lightweight)
  # Raw events only, no aggregation
}
```

**Benefit:** Can export every 60s instead of 300s (more real-time) without overhead.

---

## Architecture Diagram (Minimal)

```
┌─────────────────────┐
│  ISO8583 Logs       │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Parser + Correlator │  ← Java (minimal)
│ - Extract fields    │
│ - Match req↔resp    │
│ - Calculate TAT     │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  Raw Event JSON     │  ← Simple format
│  {transaction_id,   │
│   tat_ms,           │
│   channel, ...}     │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Dynatrace Logs API  │  ← HTTP POST
│ or Bindplane        │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Dynatrace DQL       │  ← All analysis
│ (stats, timeseries, │
│  filter, sort, ...)  │
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│ Dashboards & Alerts │
└─────────────────────┘
```

---

## Code Changes Required

### Remove These (No Longer Needed)
- ❌ `TATMetricsExporter` class (all methods)
- ❌ `calculateMetrics()` logic
- ❌ Channel/response-code aggregation
- ❌ Percentile calculation
- ❌ Pre-aggregated JSON structure

### Keep These
- ✅ `ISO8583Parser`
- ✅ `ISO8583Message`
- ✅ `CorrelationStore`
- ✅ `TATTransaction`
- ✅ `Configuration`
- ✅ `TATExtractorApp`

### Replace With
- ✅ Simple `EventExporter` class
  ```java
  public class EventExporter {
    public void exportMetrics() {
      List<TATTransaction> completed = store.getCompletedTransactions();
      for (TATTransaction txn : completed) {
        JsonObject event = createRawEvent(txn);
        postToBindplane(event);
      }
    }

    private JsonObject createRawEvent(TATTransaction txn) {
      JsonObject event = new JsonObject();
      event.addProperty("timestamp", System.currentTimeMillis());
      event.addProperty("transaction_id", txn.getStan());
      event.addProperty("tat_ms", txn.getTatMillis());
      event.addProperty("channel", txn.getChannel());
      event.addProperty("response_code", txn.getResponseCode());
      event.addProperty("pan_masked", maskPan(txn.getPan()));
      return event;
    }
  }
  ```

---

## Summary

**Philosophy:** 
- Java = Extract + Correlate + Calculate (what **only Java can do**)
- Dynatrace = Aggregate + Analyze + Visualize (what **Dynatrace does best**)

**Result:**
- ✅ Simpler Java code
- ✅ More flexible analytics
- ✅ Easier to extend
- ✅ Better separation of concerns
- ✅ Faster queries (raw events, not aggregated)

---

**Recommended:** Implement with this minimal approach.
