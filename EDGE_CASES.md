# Edge Cases & Message Non-Adjacency

This document covers real-world scenarios where ISO8583 messages don't behave as expected.

## Challenge: Messages Are Not Adjacent

**The Problem:**
- ISO8583 request/response pairs are logged separately
- No guarantee that a response follows immediately after its request
- Responses may arrive in a different batch, file, or much later
- Some requests may never receive responses (timeout, system failure)

**Example:**
```
Batch 1 (12:00:00):
  Msg 1200 STAN=001 (Request)
  Msg 1200 STAN=002 (Request)
  Msg 1200 STAN=003 (Request)

Batch 2 (12:05:00):
  Msg 1210 STAN=002 (Response) ← Response for STAN 002
  Msg 1210 STAN=001 (Response) ← Response for STAN 001
                                  (out of order!)

Batch 3 (12:10:00):
  Msg 1210 STAN=004 (Response) ← But STAN 004 request never logged!
                                  (orphaned response)

STAN 003 Request: Never received a response
```

## How This Solution Handles It

### 1. Correlation via STAN + PAN (Not Message Order)

The parser correlates using **Field 011 (STAN) + Field 002 (PAN)**, not log position:

```java
String correlationKey = getPan() + "|" + getStan();
// Example: "9229814714205000026|661765234767"
```

This works regardless of order or batching.

### 2. In-Memory Pending Transaction Buffer

Requests are stored in a thread-safe map until responses arrive:

```java
Map<String, TATTransaction> pendingTransactions
```

This allows responses to arrive:
- ✅ Minutes later
- ✅ In different log files
- ✅ Out of order
- ✅ Even in reverse order

### 3. Expiration of Unmatched Requests

Transactions that don't receive responses within `correlation.expiration.minutes` are:
- Removed from memory to prevent memory leaks
- Persisted to SQLite with `response_code = "TIMEOUT"`
- Exported as metrics with `tat_millis = -1`

**Configuration:**
```conf
iso8583.correlation.expiration.minutes = 60  # Default
```

**For different scenarios:**
- High-latency payments (2+ hours): `180` minutes
- Real-time UPI (< 10s): `5` minutes
- SLA-bound (< 30s): `10` minutes

### 4. Orphaned Response Handling

If a response arrives without a matching request:

```java
TATTransaction transaction = pendingTransactions.get(correlationKey);
if (transaction != null) {
    // Match found, calculate TAT
} else {
    logger.warn("Response received without matching request: {}", correlationKey);
    // Log and skip (no metric exported)
}
```

**What happens:**
- ⚠️ Logged as warning
- ❌ Not counted in metrics
- No error/crash — app continues processing

### 5. Unordered/Out-of-Sequence Processing

Watch mode continuously monitors the log file:

```java
if (config.isWatchMode()) {
    watchLogFile(logPath);  // Tail log for new data
}
```

New data is processed immediately, regardless of order:
- File 1: Requests A, B, C
- File 2: Response B, A, C
- File 3: Response D (orphaned)
- → TAT metrics for A, B, C calculated correctly
- → Response D ignored

## Metrics for Monitoring Correlation Health

The database tracks this:

```sql
-- Check unmatched transactions
SELECT COUNT(*) FROM tat_transactions WHERE response_code = 'TIMEOUT';

-- Check orphaned responses (logged but not persisted)
SELECT * FROM logs WHERE "WARN.*without matching request";

-- Correlation success rate
SELECT 
    COUNT(CASE WHEN response_code != 'TIMEOUT' THEN 1 END) as matched,
    COUNT(CASE WHEN response_code = 'TIMEOUT' THEN 1 END) as unmatched,
    ROUND(100.0 * COUNT(CASE WHEN response_code != 'TIMEOUT' THEN 1 END) / COUNT(*), 2) as success_rate_pct
FROM tat_transactions;
```

## Metrics Exported for Unmatched Transactions

When a request expires without response:

```json
{
    "metric_name": "payment.transaction.tat",
    "value": -1,
    "attributes": {
        "transaction_id": "661765234767",
        "channel": "UPI",
        "response_code": "TIMEOUT",
        "pan_masked": "****0026",
        "status": "unmatched_request"
    }
}
```

In Dynatrace, you can then:
- Filter: `response_code = "TIMEOUT"` to see timeouts
- Alert: `count(response_code = "TIMEOUT") > threshold`
- Trend: Track TIMEOUT count over time as a reliability indicator

## Configuration Scenarios

### Scenario 1: High-Volume Real-Time UPI (Requests Always Match)

```conf
iso8583.correlation.expiration.minutes = 5
iso8583.export.interval.seconds = 60
```

- Tight expiration (responses expected within 5 min)
- Frequent export (high metric velocity)
- Orphaned TIMEOUTs would be rare
- Good for alerting on slow/failed transactions

### Scenario 2: Batch Processing (Responses May Be Delayed)

```conf
iso8583.correlation.expiration.minutes = 360  # 6 hours
iso8583.export.interval.seconds = 900  # 15 min
```

- Long expiration (batch processing may lag)
- Infrequent export (lower metric volume)
- Some TIMEOUT transactions are expected and normal
- TAT reported after batch completes

### Scenario 3: Cross-Border Transfers (Variable Latency)

```conf
iso8583.correlation.expiration.minutes = 1440  # 24 hours
iso8583.export.interval.seconds = 3600  # 1 hour
```

- Very long expiration (international delays)
- Hourly export (aggregate TAT trends)
- High variance in TAT metrics expected
- Focus on percentiles (p95, p99) not averages

## Detecting Issues

### Symptom 1: High TIMEOUT Rate

**Indicator:**
```
SELECT COUNT(*) FROM tat_transactions WHERE response_code = 'TIMEOUT'
```

**Possible Causes:**
- ❌ Expiration window too short for actual SLA
- ❌ Response messages not being logged
- ❌ Correlation key mismatch (STAN/PAN changing between req/resp)

**Fix:**
1. Increase `expiration.minutes`
2. Validate STAN/PAN are consistent in request/response
3. Check if response logs are actually being written

### Symptom 2: Memory Keeps Growing

**Indicator:**
- JVM heap usage increases over time
- `pendingTransactions` map size keeps growing

**Possible Causes:**
- ❌ Expiration is too long or disabled
- ❌ Requests arriving without responses indefinitely

**Fix:**
```bash
# Check pending transaction count
sqlite3 correlation.db "SELECT COUNT(*) FROM tat_transactions WHERE completed_at IS NULL;"

# Reduce expiration window
iso8583.correlation.expiration.minutes = 30
```

### Symptom 3: Metrics Not Appearing

**Indicator:**
- TAT metrics not in Dynatrace
- Application logs show "Completed transactions: 0"

**Possible Causes:**
- ❌ No matching request/response pairs (all TIMEOUT)
- ❌ Bindplane URL incorrect or unreachable
- ❌ Log format not parsing correctly

**Fix:**
1. Check Bindplane connectivity:
   ```bash
   curl -v http://localhost:4318/v1/metrics
   ```

2. Verify log parsing:
   ```bash
   grep "Processed message" /var/log/iso8583/tat-extractor.log
   ```

3. Check matched vs. unmatched:
   ```bash
   sqlite3 correlation.db "SELECT response_code, COUNT(*) FROM tat_transactions GROUP BY response_code;"
   ```

## Best Practices

1. **Monitor Correlation Health:**
   - Create Dynatrace metric for `response_code = "TIMEOUT"` count
   - Alert if timeout ratio > 5% (or your SLA threshold)

2. **Set Expiration Based on SLA:**
   - Use worst-case response time + 5 min buffer
   - Document why you chose the value

3. **Watch for Log Gaps:**
   - If responses appear in different files/batches, ensure all are processed
   - Watch mode handles this automatically; batch mode may miss batches

4. **Validate Correlation Key Uniqueness:**
   - STAN + PAN should uniquely identify a transaction
   - If fields are reused/recycled, correlation will break

5. **Plan for Orphaned Responses:**
   - They're normal in distributed systems
   - Treat as "response without request" indicator
   - May indicate upstream system generating extra messages

## Testing Edge Cases

Use the sample-data files to test:

```bash
# Test 1: Out-of-order responses
java -jar iso8583-tat-extractor.jar application.conf < test-data/out-of-order.txt

# Test 2: Missing responses
java -jar iso8583-tat-extractor.jar application.conf < test-data/missing-responses.txt

# Test 3: Orphaned responses
java -jar iso8583-tat-extractor.jar application.conf < test-data/orphaned-responses.txt
```

---

**Summary:** The solution is built to handle real-world message non-adjacency via correlation-by-key, in-memory buffering, and expiration-based cleanup. Configure expiration based on your actual SLA to balance between capturing all legitimate transactions and preventing memory leaks from orphaned requests.
