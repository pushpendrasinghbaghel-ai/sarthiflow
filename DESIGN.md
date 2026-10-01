# ISO8583 TAT Extractor - Complete Design & Requirements

## Problem Statement

**Customer:** SarthiFlow Bank  
**Challenge:** Extract Turn-Around Time (TAT) metrics from ISO8583 payment transaction logs and send to Dynatrace Managed.

**Issue:** ISO8583 logs contain separate request (MessageId 1200) and response (MessageId 1210) messages that must be correlated, but:
- No guarantee responses arrive after requests
- Responses may arrive much later or never
- Dynatrace Managed cannot natively correlate multiline log entries
- Bindplane doesn't support request/response matching

**Solution:** Build a custom Java application that:
1. Parses ISO8583 multiline logs
2. Correlates request/response pairs using transaction IDs
3. Calculates TAT (milliseconds) at transaction, channel, and response-code levels
4. Exports metrics to Dynatrace via Bindplane HTTP endpoint
5. Runs on Solaris 11 with no external dependencies beyond JVM

---

## Architecture

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ ISO8583 Logs (multiline text format)                             â”‚
â”‚ Separate request (1200) and response (1210) messages              â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                      â”‚
                      â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ JAVA: ISO8583Parser                                              â”‚
â”‚ Parse & extract fields (002=PAN, 011=STAN, 123=Channel, etc)     â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                      â”‚
                      â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ JAVA: CorrelationStore (In-Memory + SQLite)                      â”‚
â”‚ Match request â†” response by STAN+PAN key                         â”‚
â”‚ Calculate TAT = response_time - request_time                     â”‚
â”‚ Expiration: 60 min (configurable) â†’ mark TIMEOUT                â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                      â”‚
                      â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ JAVA: EventExporter (Minimal)                                    â”‚
â”‚ Convert each TAT to raw JSON event (NO aggregation):             â”‚
â”‚ {transaction_id, tat_ms, channel, response_code, ...}            â”‚
â”‚ Export: POST to Dynatrace Logs API or Bindplane                  â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                      â”‚
                      â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ Dynatrace / Bindplane                                            â”‚
â”‚ Ingest raw events (one per TAT)                                  â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¬â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                      â”‚
                      â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚ DYNATRACE DQL (All Aggregation Happens Here)                     â”‚
â”‚ timeseries avg(tat_ms), by: {channel}                            â”‚
â”‚ timeseries percentile(tat_ms, 95), by: {channel}                 â”‚
â”‚ filter response_code = "000"                                     â”‚
â”‚ Create dashboards, set alerts, analyze trends                    â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

**Philosophy:** Java does the MINIMUM (parse + correlate + calculate). Dynatrace does the MAXIMUM (aggregation, percentiles, grouping, dashboards).

---

## Class Responsibilities

### 1. **ISO8583Message** (model/)
**Purpose:** Represent a single parsed ISO8583 message

**Fields:**
- `int pid` â€” Process ID
- `LocalDateTime receiveTime` â€” Timestamp (from Received At or Sent At)
- `String messageId` â€” 1200 (request) or 1210 (response)
- `String direction` â€” "Received" or "Sent"
- `Map<String, String> fields` â€” Field number â†’ value (e.g., "011" â†’ "661765234767")

**Key Methods:**
- `getField(String fieldNum)` â€” Retrieve field value
- `getStan()` â€” Field 011 (System Trace Audit Number)
- `getPan()` â€” Field 002 (Primary Account Number)
- `getChannel()` â€” Field 123 (UPI/CARD/WALLET/etc)
- `getResponseCode()` â€” Field 039 (000 = success, others = failure)
- `getCorrelationKey()` â€” Returns PAN + "|" + STAN

---

### 2. **TATTransaction** (model/)
**Purpose:** Represent a matched request/response pair

**Fields:**
- `String correlationKey` â€” PAN|STAN (unique identifier)
- `ISO8583Message requestMessage` â€” Request
- `ISO8583Message responseMessage` â€” Response (null until matched)
- `long tatMillis` â€” Calculated TAT in milliseconds
- `boolean isComplete` â€” True when response received
- `LocalDateTime createdAt` â€” When request was stored

**Key Methods:**
- `setResponseMessage(ISO8583Message)` â€” Sets response & calculates TAT
- `calculateTAT()` â€” ChronoUnit.MILLIS.between(request time, response time)
- `isExpired(long expirationMinutes)` â€” Check if exceeded expiration window
- `getChannel()`, `getStan()`, `getPan()`, `getResponseCode()` â€” Convenience accessors

---

### 3. **ISO8583Parser** (parser/)
**Purpose:** Extract fields from multiline log text

**Static Patterns:**
- `PID_PATTERN` â€” "Pid: (\\d+)"
- `RECEIVED_PATTERN` â€” "Received At: (.+)"
- `SENT_PATTERN` â€” "Sent At: (.+)"
- `MESSAGE_ID_PATTERN` â€” "MessageId: (\\d+)"
- `FIELD_PATTERN` â€” "Field (\\d{3}): (.*)"
- `TIMESTAMP_FORMAT` â€” "MM/dd/yyyy HH:mm:ss.SSS"

**Key Methods:**
- `parseMessage(String messageBlock)` â€” Parse one <====> delimited block
  - Iterate lines, apply regex patterns, populate ISO8583Message
- `splitMessageBlocks(String logContent)` â€” Split by "<=====>" separator

---

### 4. **CorrelationStore** (store/)
**Purpose:** Maintain in-memory transaction buffer + persist to SQLite

**Fields:**
- `Map<String, TATTransaction> pendingTransactions` â€” Requests awaiting responses
- `List<TATTransaction> completedTransactions` â€” Matched request/response pairs
- `Connection dbConnection` â€” SQLite connection
- `long expirationMinutes` â€” Config parameter

**Key Methods:**
- `processMessage(ISO8583Message message)` â€” Main entry point
  - If "Received": `pendingTransactions.putIfAbsent(correlationKey, new TATTransaction(...))`
  - If "Sent": lookup, if found call `setResponseMessage()`, move to completed
- `cleanExpiredTransactions()` â€” Run periodically (every 5 min)
  - Find entries where `isExpired(expirationMinutes) == true`
  - Persist as TIMEOUT, remove from pending
- `persistTransaction(TATTransaction)` â€” Insert into `tat_transactions` table
- `persistOrphanedTransaction(TATTransaction)` â€” Insert with `tat_millis = -1` and `response_code = "TIMEOUT"`
- `getCompletedTransactions()` â€” Return list for metrics export

**Database Schema:**
```sql
CREATE TABLE tat_transactions (
  id INTEGER PRIMARY KEY,
  correlation_key TEXT,
  stan TEXT,
  pan TEXT,
  channel TEXT,
  tat_millis INTEGER,            -- -1 if TIMEOUT
  response_code TEXT,
  created_at TIMESTAMP,
  completed_at TIMESTAMP
);
```

---

### 5. **EventExporter** (metrics/)
**Purpose:** Convert TAT transactions to raw events & export to Dynatrace

**Philosophy:** Minimal processing. Send raw events; let Dynatrace DQL do aggregation.

**Fields:**
- `CorrelationStore store` â€” Reference to completed transactions
- `String dynatraceUrl` â€” Dynatrace HTTP ingest endpoint
- `OkHttpClient httpClient` â€” HTTP client

**Key Methods:**
- `exportMetrics()` â€” Called periodically
  1. Get completed transactions from store
  2. For each TATTransaction: create JSON event
  3. POST to Dynatrace

- `createRawEvent(TATTransaction)` â€” Single TAT as raw event (NO aggregation):
  ```json
  {
    "timestamp": 1725088946721,
    "transaction_id": "661765234767",
    "tat_ms": 186,
    "channel": "UPI",
    "response_code": "000",
    "pan_masked": "****0026",
    "request_time": "2026-09-08T12:02:26.721Z",
    "response_time": "2026-09-08T12:02:26.907Z"
  }
  ```

- `maskPan(String pan)` â€” Return "****" + last 4 digits
- `exportToBindplane(List<JsonObject> events)` â€” POST each event via OkHttp
  - Endpoint: `POST https://<TENANT>.managed.apps.dynatrace.com/api/v1/logs/ingest`
  - Or: `POST http://bindplane-host:8888/v1/logs`
  - Each event = one HTTP request or batched in array

---

### 6. **Configuration** (root)
**Purpose:** Load settings from application.conf

**Fields:**
- `String logFilePath` â€” Input log file path
- `String dbPath` â€” SQLite database location
- `String bindplaneUrl` â€” Bindplane HTTP endpoint
- `long expirationMinutes` â€” Transaction timeout window
- `long exportIntervalSeconds` â€” Metric export frequency
- `boolean watchMode` â€” Continuous monitoring vs. one-time

**Key Methods:**
- `loadFromFile(String configPath)` â€” Parse HOCON file via Typesafe Config
- `applyDefaults()` â€” Fallback defaults if file not found

---

### 7. **TATExtractorApp** (root)
**Purpose:** Main application entry point

**Flow:**
1. `main(String[] args)` â†’ `run(String[] args)`
2. Load configuration
3. Initialize components:
   - `CorrelationStore` (SQLite + memory)
   - `TATMetricsExporter` (metrics calculation)
   - `ISO8583Parser` (field extraction)
4. Set up shutdown hook for graceful cleanup
5. Schedule background tasks:
   - Every 5 min: `cleanExpiredTransactions()`
   - Every N sec: `exportMetrics()`
6. Process logs:
   - If `watch.enabled`: `watchLogFile()` (tail mode)
   - Else: `processLogFile()` (one-time)
7. Keep running until shutdown

**Key Methods:**
- `processLogFile(Path logFile)` â€” Read entire file, parse, process
- `watchLogFile(Path logFile)` â€” Monitor for changes, re-process incrementally

---

## Data Flow Example

**Input Log:**
```
Pid: 22664 Received At: 08/09/2026 12:02:26.721
MessageId: 1200
Field 002: 9229814714205000026
Field 011: 661765234767
Field 123: UPI
Field 127: ...
<=========>

Pid: 22664 Sent At: 08/09/2026 12:02:26.907
MessageId: 1210
Field 002: 9229814714205000026
Field 011: 661765234767
Field 039: 000
Field 123: UPI
<=========>
```

**Processing Steps:**

1. **Parser** splits by `<========>`, gets 2 blocks
2. **First block (request):**
   - Create ISO8583Message
   - Extract: messageId=1200, direction=Received, receiveTime=12:02:26.721
   - Extract fields: 002=9229814714205000026, 011=661765234767, 123=UPI
   - correlationKey = "9229814714205000026|661765234767"
   - Pass to CorrelationStore

3. **CorrelationStore (request):**
   - Direction="Received" â†’ Store in pendingTransactions[correlationKey]

4. **Second block (response):**
   - Create ISO8583Message
   - Extract: messageId=1210, direction=Sent, receiveTime=12:02:26.907
   - Extract fields: 002=9229814714205000026, 011=661765234767, 039=000, 123=UPI
   - correlationKey = "9229814714205000026|661765234767"
   - Pass to CorrelationStore

5. **CorrelationStore (response):**
   - Direction="Sent" â†’ Lookup pendingTransactions[correlationKey]
   - Found! Call `transaction.setResponseMessage(message)`
   - TATTransaction.calculateTAT() â†’ 186 milliseconds
   - Move to completedTransactions
   - Persist to SQLite

6. **Event Export (every 300s):**
   - Get completedTransactions (size=1)
   - Create raw JSON event:
     ```json
     {
       "timestamp": 1725088946721,
       "transaction_id": "661765234767",
       "tat_ms": 186,
       "channel": "UPI",
       "response_code": "000",
       "pan_masked": "****0026"
     }
     ```
   - POST to Dynatrace: `POST /api/v1/logs/ingest`
   - Dynatrace stores as raw event (queryable via DQL)

7. **Dynatrace DQL Aggregation:**
   - Query: `timeseries avg(tat_ms), by: {channel}`
   - Result: UPI avg=186ms
   - Query: `timeseries percentile(tat_ms, 95), by: {channel}`
   - Result: UPI p95=186ms
   - Create dashboards, alerts, trends from raw events

---

## Configuration (application.conf)

```conf
iso8583 {
  # Path to ISO8583 log file
  log.path = "/var/log/payment/CIDC_SampleLogs.txt"

  # SQLite database path
  db.path = "/var/lib/iso8583/correlation.db"

  # Bindplane HTTP receiver endpoint
  bindplane.url = "https://<TENANT>.managed.apps.dynatrace.com/api/v1/logs"

  # How long to keep pending transactions before expiring
  correlation.expiration.minutes = 60

  # Export interval for completed metrics
  export.interval.seconds = 300

  # Watch mode: tail log continuously (true) or process once (false)
  watch.enabled = false
}

logging {
  level = "INFO"
  file = "/var/log/iso8583/tat-extractor.log"
}
```

---

## Build & Deployment

### Build
```bash
mvn clean package
# Output: target/iso8583-tat-extractor.jar (fat JAR, ~50MB)
```

### Dependencies (Maven pom.xml)
- `com.google.code.gson:gson` â€” JSON serialization
- `org.xerial:sqlite-jdbc` â€” SQLite driver
- `io.prometheus:simpleclient` â€” Metrics (optional, for future Prometheus export)
- `com.squareup.okhttp3:okhttp` â€” HTTP client
- `ch.qos.logback:logback-classic` â€” Logging
- `com.typesafe:config` â€” HOCON config parsing

### Runtime Requirements
- Java 11+ (LTS recommended: 11, 17, 21)
- Solaris 11 compatible (tested on OmniOS)
- No external services required (SQLite embedded)
- Network: HTTP connectivity to Dynatrace Bindplane endpoint

### Deploy to Solaris 11
```bash
# Automated
./scripts/deploy-solaris.sh /opt/java/jdk-11 iso8583 solaris-host

# Manual
1. Copy JAR to /opt/iso8583-tat/
2. Copy application.conf to /opt/iso8583-tat/
3. Create systemd service in /etc/systemd/system/iso8583-tat.service
4. Start: systemctl start iso8583-tat
```

---

## Edge Cases & Handling

| Scenario | Behavior |
|----------|----------|
| Response never arrives | Request expires after `correlation.expiration.minutes`, persisted with `response_code=TIMEOUT`, `tat_millis=-1` |
| Responses out of order | Correlated by STAN+PAN key, not log position â†’ works correctly |
| Orphaned response (no request) | Logged as warning, not persisted, app continues |
| Log file with mixed batches | In-memory buffer handles multi-batch/file scenarios |
| High volume (500k txns/day) | Tune JVM: `-Xmx4g -Xms2g`, expiration window, export interval |
| Database corruption | Recreate from scratch (correlation.db auto-created) |

---

## Testing

### Sample Test Data
- **Location:** `sample-data/sample-logs.txt`
- **Contains:** 3 complete request/response pairs (UPI, CARD, WALLET channels)
- **TAT values:** 186ms, 442ms, 455ms

### Quick Test
```bash
java -jar target/iso8583-tat-extractor.jar application.conf
# Expected: Processes 3 transactions, creates SQLite DB, exports metrics
```

### Verify Results
```bash
sqlite3 data/correlation.db
SELECT COUNT(*) FROM tat_transactions;           # Should return 3
SELECT channel, COUNT(*), AVG(tat_millis) FROM tat_transactions GROUP BY channel;
```

---

## API / Interface Summary

### Key External Interfaces

| Interface | Method | Input | Output |
|-----------|--------|-------|--------|
| ISO8583Parser | `parseMessage(String)` | Multiline block text | ISO8583Message |
| ISO8583Parser | `splitMessageBlocks(String)` | Log content | String[] |
| CorrelationStore | `processMessage(ISO8583Message)` | Parsed message | void (side effect: update state) |
| CorrelationStore | `getCompletedTransactions()` | none | List<TATTransaction> |
| CorrelationStore | `cleanExpiredTransactions()` | none | void |
| TATMetricsExporter | `exportMetrics()` | none | void (HTTP POST) |
| TATExtractorApp | `main(String[] args)` | Config file path | void (runs until shutdown) |

---

---

## Dynatrace DQL Queries (After Events Are Ingested)

Once raw events are in Dynatrace, use DQL for all analysis:

### TAT by Channel
```dql
fetch logs
| filter channel != null
| stats avg(tat_ms), percentile(tat_ms, 50) as p50, percentile(tat_ms, 95) as p95, percentile(tat_ms, 99) as p99 by channel
```

### TAT Success vs Failure
```dql
fetch logs
| filter tat_ms > 0
| stats count, avg(tat_ms), max(tat_ms) by response_code
```

### TAT Trend Over Time (1-hour buckets)
```dql
timeseries avg(tat_ms), by: {channel}
```

### High TAT Outliers (>5000ms)
```dql
fetch logs
| filter tat_ms > 5000
| fields transaction_id, tat_ms, channel, response_code
```

### Success Rate by Channel
```dql
fetch logs
| stats count(if(response_code == "000", 1)) as success_count, count() as total by channel
| fields channel, success_count, total, round(success_count / total * 100, 2) as success_rate_pct
```

### Timeout/Unmatched Transactions
```dql
fetch logs
| filter tat_ms == -1 AND response_code == "TIMEOUT"
| fields transaction_id, channel, created_at
```

---

## Future Enhancement Points

1. **Pluggable exporters** â€” Add Kafka, direct Dynatrace API, gRPC
2. **Multiple log formats** â€” Add Grok patterns for other payment systems
3. **Distributed setup** â€” PostgreSQL instead of SQLite for multi-instance correlation
4. **Dynatrace Dashboard API** â€” Auto-generate dashboards from DQL queries
5. **Alert Rules** â€” Trigger alerts when p95_tat_ms > 5000 or success_rate < 99%
6. **Event enrichment** â€” Add merchant ID, transaction type from other sources

---

## Code Quality

- **Thread Safety:** ConcurrentHashMap, Collections.synchronizedList
- **Error Handling:** Try-catch on parse failures, continue processing
- **Logging:** SLF4J with Logback, rotation every 10MB
- **Resource Management:** Shutdown hooks, close DB connection, executor service shutdown

---

**Complete Design Document for Agent Handoff**  
Ready for: Code review, extension, porting to other languages, or deployment

