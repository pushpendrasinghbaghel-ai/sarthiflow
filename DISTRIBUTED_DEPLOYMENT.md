# Distributed Deployment Architecture

**Clarified Scenario:**
- 18 crore (180M) pairs/day **across many servers** (not one server)
- Each server has **multiple log files**
- Each server runs its own TAT Extractor instance
- Instances aggregate locally, export to Dynatrace

---

## Volume Distribution

```
Total:  180 million pairs/day across enterprise
        ├─ Server 1: 18M pairs/day (multiple files)
        ├─ Server 2: 18M pairs/day (multiple files)
        ├─ Server 3: 18M pairs/day (multiple files)
        ├─ ...
        └─ Server N: 18M pairs/day (multiple files)

Per Server (10-server setup):
        18M pairs/day = 208 pairs/second ✓ Very manageable
        
Per Server (5-server setup):
        36M pairs/day = 416 pairs/second ✓ Still fine
        
Per Server (3-server setup):
        60M pairs/day = 694 pairs/second ✓ Acceptable

Per Server (Single server - worst case):
        180M pairs/day = 2,083 pairs/second ⚠ Needs optimization
```

---

## Distributed Architecture

```
ENTERPRISE PAYMENT NETWORK
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│  Server A (ICICI Mumbai)          Server B (ICICI Delhi)      │
│  ├─ CIDC_Log_001.txt             ├─ CIDC_Log_005.txt         │
│  ├─ CIDC_Log_002.txt             ├─ CIDC_Log_006.txt         │
│  └─ CIDC_Log_003.txt             └─ CIDC_Log_007.txt         │
│        │                                │                      │
│        ▼                                ▼                      │
│  ┌──────────────────┐          ┌──────────────────┐           │
│  │ TAT Extractor A  │          │ TAT Extractor B  │           │
│  │ (Java process)   │          │ (Java process)   │           │
│  │ 18M pairs/day    │          │ 18M pairs/day    │           │
│  │ ~208 msg/sec     │          │ ~208 msg/sec     │           │
│  │ 500MB RAM        │          │ 500MB RAM        │           │
│  └──────────┬───────┘          └──────────┬───────┘           │
│             │                             │                    │
│  Server C (ICICI Bangalore)               │                    │
│  ├─ CIDC_Log_008.txt                      │                    │
│  ├─ CIDC_Log_009.txt                      │                    │
│  └─ CIDC_Log_010.txt                      │                    │
│        │                                  │                    │
│        ▼                                  │                    │
│  ┌──────────────────┐                     │                    │
│  │ TAT Extractor C  │                     │                    │
│  │ (Java process)   │                     │                    │
│  │ 18M pairs/day    │                     │                    │
│  │ ~208 msg/sec     │                     │                    │
│  │ 500MB RAM        │                     │                    │
│  └──────────┬───────┘                     │                    │
│             │                             │                    │
│             └─────────────────┬───────────┘                    │
│                               │                                │
│                               ▼ (All instances export)         │
│                    ┌─────────────────────┐                    │
│                    │    Dynatrace        │                    │
│                    │    Managed          │                    │
│                    │    (Central)        │                    │
│                    │                     │                    │
│                    │ Dashboards:         │                    │
│                    │ - Global TAT        │                    │
│                    │ - Per-server TAT    │                    │
│                    │ - Channel trends    │                    │
│                    │ - SLA metrics       │                    │
│                    └─────────────────────┘                    │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## Per-Server: Multi-File Processing

Each server has multiple log files being written simultaneously:

```
Server A Log Directory: /var/log/payment/

├─ CIDC_Log_001.txt  (File 1)
│  ├─ Requests:  [Request1, Request2, Request3, ...]
│  └─ Responses: [Response1, Response2, Response3, ...]
│
├─ CIDC_Log_002.txt  (File 2)
│  ├─ Requests:  [Request4, Request5, Request6, ...]
│  └─ Responses: [Response4, Response5, Response6, ...]
│
└─ CIDC_Log_003.txt  (File 3 - being written NOW)
   ├─ Requests:  [Request7, Request8, ...]
   └─ Responses: [Response7, Response8, ...]

TAT Extractor on Server A:
  ┌────────────────────────────────────────────┐
  │ Single Instance Processes All Files        │
  │                                            │
  │ Watch Mode: Monitor all 3 files            │
  │ Thread 1: Process CIDC_Log_001.txt         │
  │ Thread 2: Process CIDC_Log_002.txt         │
  │ Thread 3: Process CIDC_Log_003.txt (live)  │
  │                                            │
  │ Correlation Store:                         │
  │ - Matches requests ↔ responses             │
  │ - Across ANY file (not file-specific)      │
  │ - By STAN+PAN (globally unique)            │
  │                                            │
  │ Result: Aggregated TAT metrics             │
  │ Export: Send to Dynatrace                  │
  └────────────────────────────────────────────┘
```

---

## Configuration: Per-Server Instance

### Server A (Mumbai) - /opt/iso8583-tat/application.conf

```conf
iso8583 {
  # Monitor ALL log files in directory (not just one)
  log.path = "/var/log/payment/"  # Directory, not file!
  
  db.path = "/var/lib/iso8583/correlation.db"
  
  # Export to Dynatrace (shared central instance)
  bindplane.url = "https://dynatrace-managed.example.com/api/v1/logs/ingest"
  
  # For 18M pairs/day on one server = 208 pairs/second
  correlation.expiration.minutes = 30    # Standard
  
  time.window.aggregation.seconds = 60   # 1-minute buckets
  
  # Watch mode: tail multiple files continuously
  watch.enabled = true
  watch.poll.interval.seconds = 5
  
  # Threading for multi-file processing
  parser.threads = 4                     # 4 files × 4 threads each
  
  # Metrics aggregation
  export.interval.seconds = 300          # Export every 5 minutes
}
```

### Server B (Delhi) - Same config, different db.path

```conf
iso8583 {
  log.path = "/var/log/payment/"
  db.path = "/var/lib/iso8583/correlation.db"  # Local to Server B
  bindplane.url = "https://dynatrace-managed.example.com/api/v1/logs/ingest"
  # ... same settings ...
}
```

---

## Implementation: Multi-File Watcher

**Enhanced to process DIRECTORY, not just single file:**

```java
public class DirectoryLogWatcher {
  private final Path logDirectory;
  private final ISO8583Parser parser;
  private final CorrelationStore correlationStore;
  private ExecutorService executor = Executors.newFixedThreadPool(4);
  
  public void watchDirectory(Path directory) throws IOException {
    try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
      directory.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
      
      Map<String, Long> lastProcessedSize = new ConcurrentHashMap<>();
      
      // Initial scan: process existing files
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "CIDC_*.txt")) {
        for (Path file : stream) {
          lastProcessedSize.put(file.getFileName().toString(), 0L);
          executor.submit(() -> processFile(file, lastProcessedSize));
        }
      }
      
      // Continuous monitoring
      while (true) {
        WatchKey key = watchService.poll(10, TimeUnit.SECONDS);
        if (key == null) continue;
        
        for (WatchEvent<?> event : key.pollEvents()) {
          Path file = (Path) event.context();
          if (file.toString().startsWith("CIDC_") && file.toString().endsWith(".txt")) {
            executor.submit(() -> processFile(directory.resolve(file), lastProcessedSize));
          }
        }
        key.reset();
      }
    }
  }
  
  private void processFile(Path file, Map<String, Long> lastProcessed) {
    try {
      long currentSize = Files.size(file);
      long lastSize = lastProcessed.getOrDefault(file.getFileName().toString(), 0L);
      
      if (currentSize > lastSize) {
        String content = Files.readString(file);
        String[] blocks = parser.splitMessageBlocks(content);
        
        for (String block : blocks) {
          if (block.trim().isEmpty()) continue;
          
          ISO8583Message msg = parser.parseMessage(block);
          if (msg.getMessageId() != null) {
            // Correlation store is thread-safe
            correlationStore.processMessage(msg);
          }
        }
        
        lastProcessed.put(file.getFileName().toString(), currentSize);
      }
    } catch (IOException e) {
      logger.error("Error processing file: {}", file, e);
    }
  }
}
```

---

## Per-Server Resource Requirements

### Memory
```
For 208 pairs/second (18M/day):
  - Correlation store (windowed): 50MB
  - Write queue: 10MB
  - Other JVM overhead: 200MB
  ─────────────────────────
  Total: ~300-500MB heap
  
Recommendation: -Xmx1g (safe headroom)
```

### CPU
```
For 208 pairs/second:
  - Parsing (regex): Light CPU usage
  - Correlation: Light CPU usage
  - Aggregation: Light CPU usage
  - Total: ~10-20% of single core
  
Recommendation: 2+ cores available
```

### Disk I/O
```
Input: Multiple log files being written by payment system
Output: SQLite writes (async batched, ~1MB/minute)

I/O Impact: Minimal (async writes, fast SSD)
```

### Network
```
Export: Every 5 minutes
  - 5-minute aggregated metrics: ~100-200 metrics
  - JSON payload: ~50-100KB
  - HTTP POST: <100ms

Bandwidth: ~10-20 requests/hour = minimal
```

---

## Deployment: Multi-Server Setup

### Step 1: Deploy Same JAR to All Servers

```bash
# Build once
cd iso8583-tat-extractor
mvn clean package

# Copy to all servers (e.g., via Ansible, Chef, etc)
for server in server-a server-b server-c; do
  scp target/iso8583-tat-extractor.jar $server:/opt/iso8583-tat/
  scp application.conf $server:/opt/iso8583-tat/
  scp scripts/deploy-solaris.sh $server:/tmp/
done
```

### Step 2: Configure Each Instance

```bash
# On Server A
ssh server-a
cd /opt/iso8583-tat
vi application.conf
  # Set: log.path = "/var/log/payment/"
  # Set: db.path = "/var/lib/iso8583-a/correlation.db" (unique per server)
  # All others same

systemctl start iso8583-tat-a

# On Server B
ssh server-b
cd /opt/iso8583-tat
vi application.conf
  # Set: log.path = "/var/log/payment/"
  # Set: db.path = "/var/lib/iso8583-b/correlation.db" (unique per server)

systemctl start iso8583-tat-b

# On Server C
ssh server-c
cd /opt/iso8583-tat
vi application.conf
  # Set: log.path = "/var/log/payment/"
  # Set: db.path = "/var/lib/iso8583-c/correlation.db" (unique per server)

systemctl start iso8583-tat-c
```

### Step 3: Verify All Instances

```bash
# Check each instance is running
for server in server-a server-b server-c; do
  ssh $server "systemctl status iso8583-tat | grep Active"
done

# Check metrics flowing to Dynatrace
# In Dynatrace: Explore → Custom Metrics → payment.tat
# Should see 3 separate instances contributing metrics
```

---

## Dynatrace Dashboards (Distributed)

### Dashboard 1: Global View (All Servers)
```dql
# TAT across all servers
timeseries avg(payment.tat.avg_ms), by: {channel}

# Per-server comparison
timeseries avg(payment.tat.avg_ms), by: {server, channel}

# Total transaction count per server
timeseries sum(payment.tat.count), by: {server}
```

### Dashboard 2: Server-Specific (Server A)
```dql
# Filter to Server A only
fetch logs
| filter server == "server-a" OR attributes["server_id"] == "server-a"
| stats avg(tat_ms), p95(tat_ms), p99(tat_ms) by channel
```

### Dashboard 3: File-Level Breakdown (Optional)
```dql
# If tracking per-file metrics
timeseries avg(payment.tat.avg_ms), by: {server, log_file}

# Identify hot spots: which file causing delays?
```

---

## Monitoring: Distributed Health

### Central Monitoring Dashboard

```
Server Status Check:
┌──────────┬──────────┬────────────┬────────────┐
│ Server   │ Status   │ Last Export│ Metrics    │
├──────────┼──────────┼────────────┼────────────┤
│ Server A │ ✓ Running│ 2min ago   │ 63/300    │
│ Server B │ ✓ Running│ 1min ago   │ 52/300    │
│ Server C │ ✓ Running│ 4min ago   │ 58/300    │ ⚠ Slow
│ Server D │ ✗ Offline│ 25min ago  │ 0/300     │ 🔴 Alert
└──────────┴──────────┴────────────┴────────────┘

Alerts:
  🔴 Server D offline - no metrics for 25 min
  ⚠ Server C slow export - 4 min since last metric
  
Per-Server Stats:
  Server A: 18M txns/day, 208/sec, Avg TAT 245ms, Success 99.2%
  Server B: 18M txns/day, 212/sec, Avg TAT 238ms, Success 99.1%
  Server C: 18M txns/day, 205/sec, Avg TAT 252ms, Success 98.8% ⚠
  ─────────────────────────────────────────────────────────────
  Total:   54M txns/day, 625/sec, Avg TAT 245ms, Success 99.0%
```

---

## Resource Summary: 10-Server Setup

```
Infrastructure:
  10 servers with TAT Extractor instances
  Each server: Solaris 11, 2+ cores, 2GB RAM, SSD

Per-Instance (Each Server):
  JAR Size:         18MB
  Heap Usage:       ~500MB
  Disk (SQLite):    ~100MB/day (aggregated, can be archived)
  Network:          ~50KB every 5 min
  CPU:              10-20% of 1 core

Total Enterprise:
  Throughput:       ~2,000 pairs/second (10 × 208)
  Memory:           5GB across all instances
  Disk I/O:         Minimal
  Network:          Minimal
  Cost:             Low (no expensive servers needed)
```

---

## Key Points

✅ **Distributed = Manageable**
  - 180M pairs across 10 servers = 18M each = 208 pairs/sec per server
  - This is very low load (even single-core Solaris can handle)

✅ **Multi-File Handling Built-In**
  - Each instance watches directory, processes all files in parallel
  - No need for complex sharding logic

✅ **Low Resource Footprint**
  - 500MB heap per instance
  - Can run on standard payment servers
  - No performance impact on core transaction processing

✅ **Centralized Visibility**
  - All instances export to single Dynatrace
  - Global dashboards show enterprise-wide TAT
  - Per-server breakdowns available

✅ **Easy to Scale**
  - Add servers = add instances = linear scaling
  - No complex coordination between instances
  - Each server independent (fail-isolated)

---

**Architecture Ready for Distributed Deployment Across Multiple Servers** ✅
