# Bindplane Configuration for ISO8583 TAT Extractor

## Overview

```
ISO8583 TAT Extractor           Bindplane                    Dynatrace Managed
(JSON Metrics)    ──HTTP POST──→ (Receiver)    ──Forward──→  (Ingest)
                                 (Processor)                  (Metrics Dashboard)
                                 (Exporter)
```

---

## What is Bindplane?

Bindplane is Dynatrace's observability pipeline that:
1. **Receives** data (logs, metrics, traces)
2. **Processes** data (filters, transforms, enriches)
3. **Exports** to Dynatrace or other destinations

For TAT Extractor:
- **Receives:** JSON metrics from our Java app (HTTP POST)
- **Processes:** Parses and validates metrics
- **Exports:** Sends to Dynatrace Managed as custom metrics

---

## Step 1: Verify Bindplane is Enabled in Dynatrace

### On Dynatrace Managed Console

Navigate to:
```
Administration
  → Dynatrace Managed
    → Bindplane
      → Check if enabled
```

**Status:** Should show "Bindplane enabled"

If not enabled:
1. Click "Enable Bindplane"
2. Wait 5 minutes for startup
3. Access will be available at:
   ```
   https://<TENANT_ID>.managed.apps.dynatrace.com:8443/api/v1/...
   ```

---

## Step 2: Understand TAT Extractor Output Format

### What TAT Extractor Sends

Every 5 minutes (configurable), it sends:

```json
{
  "transaction_level_metrics": [
    {
      "metric_name": "payment.transaction.tat",
      "metric_type": "gauge",
      "value": 186,
      "unit": "milliseconds",
      "attributes": {
        "transaction_id": "661765234767",
        "channel": "UPI",
        "response_code": "000",
        "pan_masked": "****0026"
      },
      "timestamp_ms": 1725088946721
    }
  ],
  
  "channel_level_metrics": [
    {
      "metric_name": "payment.channel.tat",
      "metric_type": "gauge",
      "values": {
        "count": 45,
        "sum_ms": 11025,
        "avg_ms": 245,
        "min_ms": 50,
        "max_ms": 2000,
        "p50_ms": 200,
        "p95_ms": 1200,
        "p99_ms": 1800
      },
      "channel": "UPI",
      "timestamp_ms": 1725088946721
    }
  ],
  
  "summary_stats": {
    "total_transactions": 100,
    "avg_tat_ms": 245,
    "min_tat_ms": 50,
    "max_tat_ms": 2000,
    "p50_ms": 200,
    "p95_ms": 1200,
    "p99_ms": 1800,
    "success_count": 98,
    "success_rate_pct": 98.0,
    "timestamp_ms": 1725088946721
  }
}
```

---

## Step 3: Configure Bindplane HTTP Receiver

### Option A: Via Dynatrace UI (Easiest)

1. **Navigate to Bindplane**
   ```
   Administration → Dynatrace Managed → Bindplane
   ```

2. **Create New Source**
   ```
   Sources → + Add Source
   ```

3. **Select "HTTP Receiver"**
   ```
   Source Type: HTTP Receiver
   Name: ISO8583-TAT-Receiver
   Port: 8888
   Path: /v1/metrics (or custom path)
   ```

4. **Configure Receiver Settings**
   ```
   Protocol: HTTP (or HTTPS if using certificates)
   Bind Address: 0.0.0.0 (listen on all interfaces)
   Enable CORS: YES
   Request Timeout: 30 seconds
   ```

5. **Create Processor** (optional, for data transformation)
   ```
   Processors → + Add Processor
   Type: Attributes Processor
   Actions:
     - Insert server_id = hostname
     - Insert environment = production
   ```

6. **Create Exporter**
   ```
   Exporters → + Add Exporter
   Type: Dynatrace Exporter
   Endpoint: https://<TENANT_ID>.managed.apps.dynatrace.com
   API Token: <DYNATRACE_TOKEN>
   ```

7. **Connect Flow**
   ```
   HTTP Receiver → Attributes Processor → Dynatrace Exporter
   ```

---

## Step 4: Get Dynatrace API Token

### Generate Token in Dynatrace

1. **Navigate to Tokens**
   ```
   Administration
     → Access Control
       → API Tokens
         → Generate New Token
   ```

2. **Token Permissions Required**
   ```
   ✅ Ingest custom metrics
   ✅ Read metrics
   ✅ Read events
   ✅ Write events
   ```

3. **Token Format**
   ```
   dt0c01.<RANDOM_STRING>.<RANDOM_STRING>
   ```

4. **Copy Token**
   ```
   Save securely - you'll need it in Bindplane config
   ```

---

## Step 5: Configure TAT Extractor to Send to Bindplane

### Update application.conf

```conf
iso8583 {
  # ... other settings ...
  
  # IMPORTANT: Update this URL to point to Bindplane
  bindplane.url = "http://localhost:8888/v1/metrics"
  
  # For Dynatrace Managed (via Bindplane):
  # bindplane.url = "https://<DYNATRACE_TENANT>.managed.apps.dynatrace.com:8443/api/v1/metrics"
  
  export.interval.seconds = 300  # Export every 5 minutes
}
```

### Two Configuration Options

**Option 1: Local Bindplane Collector** (Simpler)
```conf
bindplane.url = "http://localhost:8888/v1/metrics"
```
- Bindplane runs on same server as TAT Extractor
- Receives on localhost:8888
- Forwards to Dynatrace

**Option 2: Direct to Dynatrace Managed** (More Direct)
```conf
bindplane.url = "https://<TENANT_ID>.managed.apps.dynatrace.com:8443/api/v1/metrics"
```
- TAT Extractor sends directly to Dynatrace
- Requires Dynatrace to be configured as receiver
- No intermediate Bindplane needed (simpler!)

---

## Step 6: Test the Connection

### Test 1: Verify TAT Extractor Can Reach Bindplane

```bash
# From TAT Extractor server
curl -v http://localhost:8888/v1/metrics

# Expected response: 200 OK or 204 No Content
```

### Test 2: Send Test Metrics

```bash
curl -X POST http://localhost:8888/v1/metrics \
  -H "Content-Type: application/json" \
  -d '{
    "metric_name": "test.tat",
    "value": 100,
    "timestamp_ms": 1725088946721
  }'

# Expected: Success response
```

### Test 3: Check Bindplane Logs

```bash
# If running via Docker
docker logs bindplane-container | grep -i "metric\|error\|received"

# If running as service
journalctl -u bindplane -f
```

### Test 4: Verify in Dynatrace

1. Wait 2-5 minutes for metrics to appear
2. Navigate to: **Explore → Custom Metrics**
3. Search for: `payment.tat`
4. Should see:
   - `payment.transaction.tat`
   - `payment.channel.tat`
   - `payment.response_code.tat`

---

## Step 7: Create Dynatrace Queries for TAT Metrics

### Query 1: TAT by Channel

```dql
fetch logs
| filter metric_name == "payment.channel.tat"
| stats avg(values.avg_ms) by channel
```

Output:
```
Channel    Avg TAT (ms)
─────────────────────
UPI        245
CARD       310
WALLET     280
```

### Query 2: TAT Percentiles

```dql
fetch logs
| filter metric_name == "payment.channel.tat"
| stats max(values.p95_ms) as p95, max(values.p99_ms) as p99 by channel
```

### Query 3: Success Rate

```dql
fetch logs
| filter metric_name == "payment.summary_stats"
| stats max(success_rate_pct) as success_rate
```

### Query 4: TAT Trend Over Time

```dql
timeseries avg(values.avg_ms), by: {channel}
```

---

## Configuration: Minimal (Recommended)

If you want the **simplest setup**, skip Bindplane entirely:

### Direct to Dynatrace Managed

**In Dynatrace:**
1. Create API Token (read/write metrics)
2. Note your tenant ID

**In application.conf:**
```conf
bindplane.url = "https://abc12345.managed.apps.dynatrace.com/api/v1/metrics"
```

**In TAT Extractor code:**
Update `MetricsExporter.java` to add Authorization header:
```java
request = new Request.Builder()
    .url(bindplaneUrl)
    .post(body)
    .addHeader("Authorization", "Api-Token <YOUR_TOKEN>")
    .build();
```

This skips Bindplane and sends directly to Dynatrace.

---

## Configuration: Full (With Bindplane)

For enterprise deployments:

```
Server 1          Server 2           Server 3
TAT-Ext 1    +    TAT-Ext 2    +    TAT-Ext 3
     │             │                 │
     └─────────────┼─────────────────┘
                   │
                   ▼
          Bindplane Collector
          (localhost:8888)
          - Receives metrics
          - Validates JSON
          - Enriches with metadata
          - Batches for export
                   │
                   ▼
          Dynatrace Managed
          - Ingest endpoint
          - Stores metrics
          - Creates dashboards
```

**Bindplane Config File (YAML)**

```yaml
receivers:
  http:
    protocols:
      http:
        endpoint: 0.0.0.0:8888

processors:
  attributes:
    actions:
      - key: server_id
        value: "payment-server-1"
        action: insert
      - key: environment
        value: "production"
        action: insert

exporters:
  dynatrace:
    endpoint: "https://abc12345.managed.apps.dynatrace.com"
    api:
      key: "dt0c01.abc123...xyz"  # Your API token

service:
  pipelines:
    metrics:
      receivers: [http]
      processors: [attributes]
      exporters: [dynatrace]
```

---

## Verification Checklist

Before deploying to production:

- [ ] Bindplane enabled in Dynatrace
- [ ] HTTP receiver configured on port 8888 (or custom)
- [ ] Dynatrace API token created and has correct permissions
- [ ] TAT Extractor `application.conf` has correct `bindplane.url`
- [ ] Network connectivity verified (curl test)
- [ ] Test metrics appear in Dynatrace (wait 5 min)
- [ ] DQL queries return results
- [ ] Dashboard created for TAT metrics
- [ ] Alerts configured for TAT thresholds

---

## Troubleshooting

### Issue 1: 404 Error from Bindplane

**Cause:** Wrong path in `bindplane.url`

**Fix:**
```
Wrong:  http://localhost:8888/metrics
Right:  http://localhost:8888/v1/metrics
```

### Issue 2: 401 Unauthorized

**Cause:** Missing or invalid API token

**Fix:**
1. Verify token is valid in Dynatrace
2. Add to Bindplane exporter config
3. Restart Bindplane

### Issue 3: Connection Refused

**Cause:** Bindplane not running or wrong port

**Fix:**
```bash
# Check if Bindplane is running
netstat -tlnp | grep 8888

# Check Bindplane status
docker ps | grep bindplane
# or
systemctl status bindplane
```

### Issue 4: Metrics Don't Appear in Dynatrace

**Cause:** Could be delay, filtering, or export issue

**Fix:**
1. Wait 5 minutes (metrics are batched)
2. Check Bindplane logs for errors
3. Verify API token has "Write metrics" permission
4. Try sending test metric via curl

### Issue 5: High Latency

**Cause:** Bindplane overloaded or network slow

**Fix:**
1. Increase `export.interval.seconds` to batch larger
2. Add more Bindplane instances behind load balancer
3. Check network latency

---

## Security Considerations

### 1. API Token Security

```
✅ Store in secure vault (not in config files)
✅ Rotate tokens regularly
✅ Use minimal required permissions
✅ Never commit token to git
```

### 2. Network Security

```
✅ Use HTTPS/TLS in production
✅ Restrict Bindplane port (8888) to internal network
✅ Firewall off from internet
✅ Use VPN for cross-datacenter traffic
```

### 3. Data Sensitivity

```
⚠️  Metrics include masked PAN (****0026) - OK
✅ No raw credit card data in metrics
✅ No passwords or API keys in metrics
✅ Safe to send to Dynatrace
```

---

## Performance Tuning

### Adjust Export Interval

```conf
# Frequent export (real-time, more network)
export.interval.seconds = 60

# Balanced (5 minutes, default)
export.interval.seconds = 300

# Less frequent (large batches)
export.interval.seconds = 900
```

### Bindplane Batch Settings

```yaml
exporters:
  dynatrace:
    batch:
      send_batch_size: 1000        # Batch 1000 metrics before send
      timeout: 10s                 # Send every 10 seconds max
```

---

## Summary: Quick Start

### Absolute Minimum

1. Get API token from Dynatrace
2. Update `application.conf`:
   ```conf
   bindplane.url = "https://<TENANT>.managed.apps.dynatrace.com/api/v1/metrics"
   ```
3. Run TAT Extractor
4. Wait 5 minutes
5. Check Dynatrace for `payment.tat` metrics

### With Bindplane (Recommended)

1. Enable Bindplane in Dynatrace
2. Create HTTP receiver on port 8888
3. Create exporter to Dynatrace
4. Update `application.conf`:
   ```conf
   bindplane.url = "http://localhost:8888/v1/metrics"
   ```
5. Start Bindplane
6. Run TAT Extractor on each server
7. Metrics flow: TAT → Bindplane → Dynatrace

---

## Next Steps

1. ✅ Decide: Direct to Dynatrace OR via Bindplane
2. ✅ Configure Dynatrace API token
3. ✅ Update `application.conf`
4. ✅ Deploy TAT Extractor
5. ✅ Test with `curl`
6. ✅ Verify metrics appear (5 min)
7. ✅ Create DQL queries/dashboards
8. ✅ Set up alerts

**Ready to go!** 🚀
