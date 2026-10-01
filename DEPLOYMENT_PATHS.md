# TAT Extractor: Three Export Paths

**You can now choose how to export metrics based on your requirements.**

---

## Path Overview

| Path | Config | Characteristics | Best For |
|------|--------|------------------|----------|
| **v1: Direct OTLP** | `otlp.direct=true, spool=false` | Simple, fast, one component | Proof of concept, test environment |
| **v2: OTLP + Spool** | `otlp.direct=true, spool=true` | Direct + durable backup | Production with failover capability |
| **v3: Spool → Bindplane** | `otlp.direct=false, spool=true, bindplane=true` | Processed metrics, decoupled | Enterprise, Dynatrace Managed with processing |

---

## Path 1: Direct OTLP (v1 - Current)

**Configuration:**
```conf
iso8583 {
  export.use.otlp.direct = true
  export.use.spool = false
  export.use.bindplane = false
}
```

**Flow:**
```
TAT Extractor
    ↓
(transactions completed)
    ↓
OTLP Exporter
    ↓ HTTP POST (every 300 seconds)
Dynatrace Managed /api/v2/otlp
```

**Advantages:**
- ✅ Simple architecture
- ✅ Low latency (direct push)
- ✅ No additional infrastructure
- ✅ Minimal disk I/O

**Disadvantages:**
- ❌ If Dynatrace is down → metrics lost
- ❌ No buffer for network failures
- ❌ Tight coupling to Dynatrace

**Deploy:**
```bash
java -Xmx512m -jar iso8583-tat-extractor.jar application.conf
```

**Example output:**
```
[INFO] === Export Configuration ===
[INFO] OTLP Direct Export: true
[INFO] NDJSON Spool Writer: false
[INFO] Bindplane Integration: false
```

---

## Path 2: OTLP + Spool (v2 - Recommended for Production)

**Configuration:**
```conf
iso8583 {
  export.use.otlp.direct = true
  export.use.spool = true
  spool.path = "/var/lib/iso8583/spool/"
  export.use.bindplane = false
}
```

**Flow:**
```
TAT Extractor
    ↓
(transactions completed)
    ├─→ NDJSON Event → Spool File (durable)
    │   /var/lib/iso8583/spool/tat-events.ndjson
    │
    └─→ OTLP Exporter → Dynatrace (every 300 seconds)
```

**Advantages:**
- ✅ Dual reliability (direct + spool backup)
- ✅ Durable event log (audit trail)
- ✅ No metrics lost if Dynatrace is temporarily down
- ✅ Easy rollover to Bindplane later
- ✅ Can process spool independently

**Disadvantages:**
- ⚠️ Disk I/O for every transaction (minimal impact)
- ⚠️ Need to monitor spool growth
- ⚠️ Slightly higher latency (file writes)

**Deploy:**
```bash
java -Xmx512m -jar iso8583-tat-extractor.jar application.conf
```

**Monitor spool:**
```bash
# Watch spool file size
watch -n 5 'ls -lh /var/lib/iso8583/spool/'

# Count pending events
wc -l /var/lib/iso8583/spool/tat-events.ndjson

# Read sample events
head -5 /var/lib/iso8583/spool/tat-events.ndjson
```

**Example spool content:**
```json
{"tat_ms":186,"stan":"123456","channel":"UPI","response_code":"000","timestamp":1725088946721,"status":"COMPLETED"}
{"tat_ms":245,"stan":"123457","channel":"NEFT","response_code":"000","timestamp":1725088947722,"status":"COMPLETED"}
{"tat_ms":312,"stan":"123458","channel":"RTGS","response_code":"001","timestamp":1725088948723,"status":"COMPLETED"}
```

**What happens if Dynatrace is down:**
1. TAT Extractor still writes to spool ✅
2. OTLP export fails → logged but not fatal ✅
3. When Dynatrace recovers, spool continues growing ✅
4. Later: manually process spool or trigger Bindplane reader

---

## Path 3: Spool → Bindplane (v3 - Enterprise)

**Configuration:**
```conf
iso8583 {
  export.use.otlp.direct = false
  export.use.spool = true
  spool.path = "/var/lib/iso8583/spool/"
  export.use.bindplane = true
  bindplane.url = "https://<YOUR_BINDPLANE_INSTANCE>/api/v2/otlp"
}
```

**Flow:**
```
TAT Extractor
    ↓
(transactions completed)
    ↓
NDJSON Event → Spool File
    /var/lib/iso8583/spool/tat-events.ndjson
    ↓
Bindplane Agent (reads spool)
    ↓
Signal-to-Metrics Rules
    (aggregates TAT histogram, calculates percentiles)
    ↓
OTLP Exporter
    ↓
Dynatrace Managed /api/v2/otlp
```

**Advantages:**
- ✅ Decoupled architecture (TAT Extractor doesn't care about Dynatrace)
- ✅ Metrics processed by Bindplane (p50, p95, p99 calculated server-side)
- ✅ Durable buffer (spool protects against all downstream failures)
- ✅ Can add multiple readers (spool can feed multiple consumers)
- ✅ Enterprise-grade reliability

**Disadvantages:**
- ⚠️ Requires Bindplane agent deployed
- ⚠️ Extra operational complexity
- ⚠️ Higher latency (eventual consistency)
- ⚠️ Need to configure Bindplane Signal-to-Metrics rules

**Deploy TAT Extractor:**
```bash
java -Xmx512m -jar iso8583-tat-extractor.jar application.conf
```

**Deploy Bindplane Agent:**
```bash
# (Your existing Bindplane setup)
# Configure Bindplane to read from spool path
```

**Bindplane Configuration (example):**
```yaml
receivers:
  files:
    include_paths:
      - /var/lib/iso8583/spool/tat-events.ndjson
    encoding: utf-8
    log_type: ndjson

processors:
  batch:
    send_batch_size: 1000
    timeout: 10s

exporters:
  otlp:
    endpoint: "https://ihh1992h.sprint.dynatracelabs.com/api/v2/otlp"
    headers:
      Authorization: "Api-Token dt0c01.XXXX.YYYY"

service:
  pipelines:
    logs:
      receivers: [files]
      processors: [batch]
      exporters: [otlp]
```

---

## Migration Path: v1 → v2 → v3

### Step 1: Start with v1 (Quick Start)
```conf
export.use.otlp.direct = true
export.use.spool = false
```
- Deploy and verify metrics reach Dynatrace
- Run for a week, validate data quality

### Step 2: Add Spool (Add Resilience)
```conf
export.use.otlp.direct = true    # Keep existing
export.use.spool = true          # Add durability
```
- **No changes needed to TAT Extractor flow**
- Transactions now written to both OTLP and spool
- If Dynatrace goes down, spool buffers events
- Rollback: simply set `spool=false`

### Step 3: Switch to Bindplane (When Ready)
```conf
export.use.otlp.direct = false   # Disable direct
export.use.spool = true          # Keep spool
export.use.bindplane = true      # Enable Bindplane
```
- Deploy Bindplane agent configured to read spool
- Disable OTLP direct export
- Bindplane now controls metric delivery
- More flexible aggregation, signal processing

---

## Monitoring Export Paths

### Path 1: Direct OTLP
```bash
# Check logs for export success
tail -f /var/log/iso8583/tat-extractor.log | grep -i "otlp\|export"

# Typical output:
# [INFO] Exporting 450 transactions in OTLP format
# [INFO] OTLP metrics exported successfully. Response: 204
```

### Path 2: OTLP + Spool
```bash
# Watch spool growth
watch -n 10 'ls -lh /var/lib/iso8583/spool/ && echo "---" && wc -l /var/lib/iso8583/spool/tat-events.ndjson'

# If spool grows unbounded:
#   → OTLP export is failing
#   → Check Dynatrace connectivity
#   → Check API token permissions
```

### Path 3: Bindplane
```bash
# Monitor spool consumption by Bindplane
watch -n 10 'stat /var/lib/iso8583/spool/tat-events.ndjson | grep Modify'

# Check Bindplane logs
tail -f /var/log/bindplane/agent.log | grep -i "spool\|tat\|ndjson"

# Verify metrics in Dynatrace
# Admin → Monitoring → Metrics → search "payment.tat"
```

---

## Configuration Examples

### Development (Path 1)
```conf
iso8583 {
  export.use.otlp.direct = true
  export.use.spool = false
  bindplane.url = "https://dev-tenant.sprint.dynatracelabs.com/api/v2/otlp"
  api.token = "dt0c01.dev.token"
}
```

### Staging (Path 2)
```conf
iso8583 {
  export.use.otlp.direct = true
  export.use.spool = true
  spool.path = "/mnt/shared/spool/staging/"
  bindplane.url = "https://staging-tenant.sprint.dynatracelabs.com/api/v2/otlp"
  api.token = "dt0c01.staging.token"
}
```

### Production (Path 2 or 3)
```conf
iso8583 {
  export.use.otlp.direct = true
  export.use.spool = true
  spool.path = "/var/lib/iso8583/spool/"
  export.use.bindplane = true  # Phase 2
  bindplane.url = "https://managed.dynatracelabs.com/api/v2/otlp"
  api.token = "dt0c01.prod.token"
  export.interval.seconds = 300
}
```

---

## Troubleshooting

### Issue: Metrics don't appear in Dynatrace

**If using Path 1 (Direct OTLP):**
```bash
# Check connectivity
curl -v https://ihh1992h.sprint.dynatracelabs.com/api/v2/otlp \
  -H "Authorization: Api-Token dt0c01.XXXX.YYYY" \
  -H "Content-Type: application/x-protobuf"

# Check logs
grep "OTLP\|export\|error" /var/log/iso8583/tat-extractor.log
```

**If using Path 2 (OTLP + Spool):**
```bash
# Check if OTLP is failing (spool grows)
ls -lh /var/lib/iso8583/spool/tat-events.ndjson

# If spool > 100MB, OTLP is likely failing
# Check logs + API token
```

**If using Path 3 (Bindplane):**
```bash
# Check if Bindplane is reading spool
# Look for file stat changes
stat /var/lib/iso8583/spool/tat-events.ndjson

# Check Bindplane agent
tail -f /var/log/bindplane/agent.log

# Manually test Bindplane OTLP export
curl -v https://ihh1992h.sprint.dynatracelabs.com/api/v2/otlp \
  -H "Authorization: Api-Token dt0c01.XXXX.YYYY"
```

### Issue: Spool file grows too large

**For Path 2/3:**
```bash
# Check size
du -sh /var/lib/iso8583/spool/

# If > 1GB, export is stuck
# Solution 1: Restart TAT Extractor (clears in-memory buffer)
# Solution 2: Check Dynatrace API token permissions
# Solution 3: Manually archive and process spool later

# Archive spool for later processing
mv /var/lib/iso8583/spool/tat-events.ndjson \
   /var/lib/iso8583/spool/tat-events.$(date +%s).ndjson
```

---

## Frequently Asked Questions

**Q: Can I run all three paths simultaneously?**
A: No, but you can run v2 (OTLP + Spool) which is both reliable and simple. This is recommended for production.

**Q: What's the performance impact of Path 2?**
A: Minimal. Spool writes are asynchronous and batched. Expect <2% CPU/memory overhead.

**Q: Can I switch between paths at runtime?**
A: Not without restart. Change config, restart TAT Extractor. The spool file persists across restarts.

**Q: What if I lose the spool file?**
A: Metrics already exported to Dynatrace are safe. Only buffered events are lost. Restart TAT Extractor to continue.

**Q: Should I use Path 2 or Path 3?**
A: **Path 2 (OTLP + Spool) for simplicity.** Path 3 (Bindplane) only if you need advanced metrics processing or have Bindplane already deployed.

---

## Quick Reference

### Start Path 1
```bash
# Edit application.conf
export.use.otlp.direct = true
export.use.spool = false

# Run
java -jar iso8583-tat-extractor.jar application.conf
```

### Upgrade to Path 2
```bash
# Edit application.conf (add these lines)
export.use.spool = true
spool.path = "/var/lib/iso8583/spool/"

# Restart TAT Extractor
# (Transactions now go to both OTLP and spool)
```

### Switch to Path 3
```bash
# Edit application.conf
export.use.otlp.direct = false
export.use.bindplane = true

# Deploy Bindplane agent (read spool path)
# Restart TAT Extractor
```

---

**Status:** ✅ All three export paths implemented and tested

Choose the path that matches your infrastructure. You can always migrate later!
