# Cardinality-Optimized Design

**Problem:** Raw events per transaction = high cardinality = expensive Dynatrace metrics

**Solution:** Smart aggregation in Java + low-cardinality export

---

## The Issue with Raw Events

```
Scenario: 500k transactions/day

Raw Event Per Transaction:
  - timestamp: UNIQUE (every millisecond different)
  - transaction_id: UNIQUE (every transaction different)
  - Each event = new cardinality dimension

Result: 500k unique metric combinations
Cost: $$$$$ (Dynatrace charges per cardinality)
```

**Better approach:** Aggregate intelligently in Java

---

## Cardinality-Optimized Architecture

```
ISO8583 Logs
    ↓
Parser (extract fields)
    ↓
Correlator (STAN+PAN matching)
    ↓
TAT Calculator (tat_ms = response - request)
    ↓
Time-Window Aggregator ← KEY CHANGE
    │
    └─ Group by: [time_bucket, channel, response_code]
    └─ Calculate: count, avg, min, max, p50, p95, p99
    └─ Keep raw TAT values in-memory for percentile calc
    ↓
Export Aggregated Metrics (low cardinality)
    ↓
Dynatrace (accepts pre-aggregated metrics)
```

---

## Aggregation Strategy

### Time Window Bucketing
```
Instead of: One metric per transaction
Do this:    One metric per 1-minute bucket per dimension

Example 1-Minute Bucket:
  Time: 2026-09-08 12:02:00 - 12:03:00
  Channel: UPI
  Response Code: 000
  Metrics:
    - count: 45
    - avg_tat_ms: 245
    - min_tat_ms: 50
    - max_tat_ms: 2000
    - p50_tat_ms: 200
    - p95_tat_ms: 1200
    - p99_tat_ms: 1800
```

### Cardinality Calculation

```
Low Cardinality Dimensions:
  - Channel: ~5-10 values (UPI, CARD, WALLET, etc)
  - Response Code: ~20 values (000, 001, 002, etc)
  - Time Buckets: ~1440 per day (1 per minute)

Total Cardinality = Time Buckets × Channels × Response Codes
                  = 1440 × 10 × 20
                  = 288,000 per day
                  = ~10k per export cycle (5 min)

Cost: Low (vs 500k raw events)
```

---

## Export Format (Aggregated Metrics)

### Per Time Window + Dimensions

```json
{
  "metrics": [
    {
      "metric_name": "payment.tat",
      "timestamp": 1725088980000,
      "attributes": {
        "time_bucket": "2026-09-08T12:02:00Z",
        "channel": "UPI",
        "response_code": "000"
      },
      "values": {
        "count": 45,
        "sum_ms": 11025,
        "avg_ms": 245,
        "min_ms": 50,
        "max_ms": 2000,
        "p50_ms": 200,
        "p95_ms": 1200,
        "p99_ms": 1800
      }
    },
    {
      "metric_name": "payment.tat",
      "timestamp": 1725088980000,
      "attributes": {
        "time_bucket": "2026-09-08T12:02:00Z",
        "channel": "UPI",
        "response_code": "001"
      },
      "values": {
        "count": 3,
        "sum_ms": 15000,
        "avg_ms": 5000,
        "min_ms": 4500,
        "max_ms": 5500,
        "p50_ms": 5000,
        "p95_ms": 5500,
        "p99_ms": 5500
      }
    }
  ]
}
```

---

## Java Implementation (Aggregator)

### New Class: TimeWindowAggregator

```java
public class TimeWindowAggregator {
  private static final long WINDOW_SIZE_MS = 60_000; // 1 minute
  
  private Map<String, List<Long>> windowData; // key = time_bucket|channel|response_code
  
  public void addTransaction(TATTransaction txn) {
    String key = createAggregationKey(txn);
    windowData.computeIfAbsent(key, k -> new ArrayList<>())
             .add(txn.getTatMillis());
  }
  
  private String createAggregationKey(TATTransaction txn) {
    long bucketTime = (System.currentTimeMillis() / WINDOW_SIZE_MS) * WINDOW_SIZE_MS;
    return bucketTime + "|" + txn.getChannel() + "|" + txn.getResponseCode();
  }
  
  public Map<String, AggregatedMetric> exportMetrics() {
    Map<String, AggregatedMetric> result = new HashMap<>();
    
    for (Map.Entry<String, List<Long>> entry : windowData.entrySet()) {
      String key = entry.getKey();
      List<Long> tatValues = entry.getValue();
      
      if (tatValues.isEmpty()) continue;
      
      AggregatedMetric metric = new AggregatedMetric();
      metric.count = tatValues.size();
      metric.sum_ms = tatValues.stream().mapToLong(Long::longValue).sum();
      metric.avg_ms = metric.sum_ms / metric.count;
      metric.min_ms = Collections.min(tatValues);
      metric.max_ms = Collections.max(tatValues);
      metric.p50_ms = calculatePercentile(tatValues, 50);
      metric.p95_ms = calculatePercentile(tatValues, 95);
      metric.p99_ms = calculatePercentile(tatValues, 99);
      
      result.put(key, metric);
    }
    
    windowData.clear(); // Ready for next window
    return result;
  }
  
  private long calculatePercentile(List<Long> values, double percentile) {
    List<Long> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int index = (int) ((percentile / 100.0) * sorted.size());
    return sorted.get(Math.min(index, sorted.size() - 1));
  }
}
```

---

## Complete Minimal + Aggregated Architecture

```
┌────────────────────────┐
│ ISO8583 Logs           │
└──────────┬─────────────┘
           │
           ▼
┌────────────────────────┐
│ 1. Parser              │
│ Extract fields         │
└──────────┬─────────────┘
           │
           ▼
┌────────────────────────┐
│ 2. Correlator          │
│ Match req ↔ resp       │
│ Calculate TAT          │
└──────────┬─────────────┘
           │
           ▼
┌────────────────────────────────────┐
│ 3. TimeWindowAggregator (NEW)      │
│ Group by:                          │
│ - 1-minute time bucket             │
│ - channel (UPI, CARD, WALLET)      │
│ - response_code (000, 001, etc)    │
│                                    │
│ Calculate per group:               │
│ - count, sum, avg, min, max        │
│ - p50, p95, p99 (percentiles)      │
└──────────┬─────────────────────────┘
           │
           ▼
┌────────────────────────┐
│ 4. MetricsExporter     │
│ Export aggregated JSON │
│ POST to Dynatrace      │
└──────────┬─────────────┘
           │
           ▼
┌────────────────────────────────────┐
│ Dynatrace Metrics API              │
│ (Low Cardinality ✓)                │
│                                    │
│ Queryable as custom metrics:       │
│ - payment.tat.count                │
│ - payment.tat.avg_ms               │
│ - payment.tat.p95_ms               │
│ - payment.tat.p99_ms               │
│                                    │
│ Dimensions:                        │
│ - channel (UPI, CARD, etc)         │
│ - response_code (000, 001, etc)    │
│ - time_bucket (per minute)         │
└────────────────────────────────────┘
```

---

## Configuration

```conf
iso8583 {
  # Aggregation window (in seconds)
  aggregation.window.seconds = 60  # 1-minute buckets

  # Export interval
  export.interval.seconds = 300  # Export every 5 minutes
                                 # (will contain ~5 windows of data)

  # Only include dimensions with cardinality below threshold
  aggregation.include_dimensions = [
    "channel",           # Low cardinality (~10 values)
    "response_code"      # Low cardinality (~20 values)
  ]
  
  # DO NOT include high-cardinality dimensions:
  # - transaction_id (unique per txn)
  # - timestamp (unique per event)
  # - pan (unique per account)
}
```

---

## Cardinality Comparison

| Approach | Per-Day Metrics | Cardinality | Cost | Query Latency |
|----------|---|---|---|---|
| **Raw Events** | 500,000 | HIGH (each txn unique) | $$$$$ | High (too much data) |
| **Aggregated (Current)** | ~10,000 | LOW (time + dim combo) | $$ | Fast |
| **Pre-Aggregated All** | ~100 | VERY LOW | $ | Too coarse |

**Recommendation: Aggregated (Middle Ground)**

---

## What's Queryable in Dynatrace

### Metrics Available
```
payment.tat.count              (transactions per bucket)
payment.tat.sum_ms             (total TAT in bucket)
payment.tat.avg_ms             (average TAT)
payment.tat.min_ms             (minimum TAT)
payment.tat.max_ms             (maximum TAT)
payment.tat.p50_ms             (median TAT)
payment.tat.p95_ms             (95th percentile TAT)
payment.tat.p99_ms             (99th percentile TAT)
```

### Available Dimensions
```
channel (UPI, CARD, WALLET, etc)
response_code (000, 001, 002, etc)
time_bucket (1-minute granularity)
```

### DQL Queries

```dql
# TAT by channel (already aggregated in Java)
timeseries avg(payment.tat.avg_ms), by: {channel}

# P95 by response code
timeseries max(payment.tat.p95_ms), by: {response_code}

# Transaction count by channel (volume)
timeseries sum(payment.tat.count), by: {channel}

# Success rate
timeseries sum(if(response_code == "000", payment.tat.count)) / sum(payment.tat.count) as success_rate
```

---

## Benefits of Aggregated Approach

✅ **Low Cardinality** — ~10k metrics/day vs 500k raw events  
✅ **Cheap** — Fits within Dynatrace custom metrics budget  
✅ **Fast** — Aggregation done in Java, not Dynatrace  
✅ **Percentiles** — Real p95/p99, not estimates  
✅ **Real-Time** — Metrics available every 5 minutes  
✅ **Queryable** — All dimensions available in DQL  
✅ **Flexible** — Can adjust window size/dimensions via config  

---

## Implementation Plan

### Phase 1: Core (Minimal)
- ✅ Parser + Correlator (done)
- ✅ TAT Calculator (done)

### Phase 2: Aggregation (Add)
- ⏳ TimeWindowAggregator class
- ⏳ AggregatedMetric data class
- ⏳ Update MetricsExporter to use aggregator

### Phase 3: Export
- ⏳ Export aggregated metrics to Dynatrace
- ⏳ Include time_bucket, channel, response_code as dimensions

### Phase 4: Validation
- ⏳ Test with sample data
- ⏳ Verify cardinality in Dynatrace
- ⏳ Create dashboards

---

## Database Optimization

SQLite table for aggregated metrics:

```sql
CREATE TABLE aggregated_metrics (
  id INTEGER PRIMARY KEY,
  time_bucket TIMESTAMP,
  channel TEXT,
  response_code TEXT,
  count INTEGER,
  sum_ms INTEGER,
  avg_ms REAL,
  min_ms INTEGER,
  max_ms INTEGER,
  p50_ms INTEGER,
  p95_ms INTEGER,
  p99_ms INTEGER,
  exported_at TIMESTAMP,
  UNIQUE(time_bucket, channel, response_code)
);
```

**Benefits:**
- Deduplication: Can't have duplicate time+channel+response_code
- Historical: Query past metrics for trending
- Audit: Track what was exported and when

---

## Unmatched Transactions (TIMEOUT)

Include in aggregation:

```json
{
  "metric_name": "payment.tat.timeout",
  "attributes": {
    "time_bucket": "2026-09-08T12:02:00Z",
    "channel": "UPI"
  },
  "values": {
    "count": 2,
    "percent": 4.4  // 2 out of 45 timeouts
  }
}
```

---

## Summary

**Best Approach: Aggregated Metrics**

```
Java Responsibility:
  ✓ Parse ISO8583
  ✓ Correlate STAN+PAN
  ✓ Calculate TAT
  ✓ AGGREGATE by time + low-cardinality dimensions
  ✓ Export: count, sum, avg, min, max, p50, p95, p99

Dynatrace Responsibility:
  ✓ Store metrics (low cardinality)
  ✓ Query via DQL (flexible)
  ✓ Create dashboards
  ✓ Set alerts
```

**Result:**
- ✅ Handles 500k txns/day
- ✅ Low cardinality (cost-effective)
- ✅ Real percentiles (not estimates)
- ✅ Real-time (5-minute refresh)
- ✅ Fully queryable in Dynatrace

---

**Recommended: Implement with 1-minute time windows + channel + response_code dimensions**
