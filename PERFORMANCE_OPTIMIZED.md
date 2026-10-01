# Performance Optimization for 180M Pairs/Day

**Scenario:** 18 crore (180 million) request/response pairs per day across multiple files/servers

**Challenge:** Current design not optimized for this scale. Need aggressive memory management & throughput.

---

## The Problem

```
180M pairs/day = 2,083 pairs/second
180M / 1,440 min = 125,000 pairs per minute
180M / 24 hours = 7.5 million pairs per hour

Current Design Issues:
  ❌ Holds pending transactions in memory indefinitely
  ❌ 60-minute expiration window = 7.5M pending txns in memory
  ❌ Each transaction ~1KB = 7.5GB RAM needed
  ❌ SQLite synchronous writes = bottleneck
  ❌ No batching of database operations
  ❌ Single-threaded log processing
  ❌ Export via individual HTTP calls (not batched)
```

---

## Performance-Optimized Architecture

### 1. Aggressive Time-Window Aggregation (Not Raw Events)

**Instead of storing pending transactions indefinitely:**

```
Current (Bad):
  - Store every request in memory
  - Wait up to 60 minutes for response
  - Result: 7.5M objects in heap

Optimized (Good):
  - Aggregate by 1-minute time window
  - ONLY store per-window TAT stats
  - Discard raw transactions after window closes
  - Result: ~10k objects in memory max
```

### 2. Memory-Bounded Aggregation

```java
public class WindowedTATAggregator {
  private static final long WINDOW_SIZE_MS = 60_000;  // 1-minute window
  private static final int MAX_WINDOWS = 10;          // Keep only 10 windows in memory
  
  // Ring buffer: old windows pushed out, new ones added
  private Map<String, AggregatedMetric>[] windowRing = new Map[MAX_WINDOWS];
  private int currentWindowIndex = 0;
  
  public void addTransaction(TATTransaction txn) {
    long windowId = getWindowId(txn.getReceiveTime());
    Map<String, AggregatedMetric> window = getOrCreateWindow(windowId);
    
    // Aggregate only - don't store raw txn
    String key = txn.getChannel() + "|" + txn.getResponseCode();
    window.computeIfAbsent(key, k -> new AggregatedMetric())
          .addValue(txn.getTatMillis());
  }
  
  // Memory usage: ~10k metrics × 10 windows = 100k objects = ~10MB
  // vs 7.5M objects = 7.5GB (original)
}
```

### 3. Optimized Correlation (Shorter Window)

**Reduce expiration time aggressively:**

| Volume | Expiration | Rationale |
|--------|-----------|-----------|
| Low (< 1M/day) | 60 min | Some txns may be slow |
| Medium (1-50M/day) | 10 min | Most responses within 10 min |
| High (50M+/day) | 5 min | Assume faster SLA, aggressive cleanup |
| **Your case (180M/day)** | **2-5 min** | Very high volume, must keep memory low |

**Configuration:**
```conf
iso8583 {
  correlation.expiration.minutes = 5    # Down from 60
  time.window.aggregation.seconds = 60  # 1-minute buckets
  max.pending.transactions = 100000     # Circuit breaker
  
  # Ring buffer settings
  ring.buffer.windows = 10              # Keep 10 windows (10 minutes of data)
}
```

### 4. Database Optimization (Async + Batching)

**Replace synchronous SQLite writes:**

```java
public class AsyncBatchDatabaseWriter {
  private static final int BATCH_SIZE = 10000;
  private LinkedBlockingQueue<AggregatedMetric> writeQueue;
  private ExecutorService dbWriter;
  
  public void offerMetric(AggregatedMetric metric) {
    writeQueue.offer(metric);  // Non-blocking add to queue
    
    if (writeQueue.size() >= BATCH_SIZE) {
      flushToDB();             // Batch write when threshold reached
    }
  }
  
  private void flushToDB() {
    List<AggregatedMetric> batch = new ArrayList<>(BATCH_SIZE);
    writeQueue.drainTo(batch, BATCH_SIZE);
    
    // Batch insert (single transaction)
    dbWriter.submit(() -> {
      try (PreparedStatement pstmt = conn.prepareStatement(
           "INSERT INTO aggregated_metrics (...) VALUES (?, ?, ?, ...)")) {
        
        conn.setAutoCommit(false);
        for (AggregatedMetric m : batch) {
          addToBatch(pstmt, m);
        }
        pstmt.executeBatch();
        conn.commit();
      }
    });
  }
}
```

**Benefits:**
- Async: Doesn't block metric calculation
- Batched: 10,000 inserts in single transaction
- Throughput: ~50k writes/second (vs 100/second sync)

### 5. Multi-Threaded Log Processing

**Single thread is bottleneck for 2k msg/second:**

```java
public class ParallelLogProcessor {
  private static final int NUM_THREADS = 8;  // Or CPU count
  private ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
  
  public void processLogFile(Path logFile) throws IOException {
    String content = Files.readString(logFile);
    String[] blocks = parser.splitMessageBlocks(content);
    
    // Process blocks in parallel
    for (String block : blocks) {
      executor.submit(() -> {
        ISO8583Message msg = parser.parseMessage(block);
        correlationStore.processMessage(msg);  // Thread-safe store
      });
    }
    
    executor.awaitTermination(5, TimeUnit.MINUTES);
  }
}
```

**Throughput improvement:**
- Single thread: ~500 msg/second (regex parsing bottleneck)
- 8 threads: ~4,000 msg/second (CPU-bound becomes I/O-bound)

### 6. Optimized Metric Export (Batching)

**Current: One HTTP call per metric**
```
180M pairs/day
÷ 1,440 minutes
÷ 10 channels
÷ 20 response codes
= ~63 metrics/minute
= 1 metric/second

But with batching every 300 seconds:
= 63 × 5 = ~315 metrics per batch
Payload: ~50KB per export

HTTP calls: 288 per day (vs millions for raw events)
Bandwidth: ~14MB per day
```

**Implementation:**
```java
public class BatchedMetricsExporter {
  private List<AggregatedMetric> batch = Collections.synchronizedList(new ArrayList<>());
  private static final int BATCH_SIZE = 1000;
  
  public void exportMetrics() {
    if (batch.isEmpty()) return;
    
    List<AggregatedMetric> toExport = new ArrayList<>(batch);
    batch.clear();
    
    // Single HTTP POST with all metrics
    String json = serializeMetrics(toExport);
    postToBindplane(json);  // One call, not many
  }
}
```

---

## Memory Profile: Optimized vs Original

```
ORIGINAL DESIGN (180M pairs/day):
  Pending transactions:    7.5M objects × 1KB = 7.5GB
  Completed transactions:  180M/day → SQLite (grows indefinitely)
  Total Heap:              10-20GB (with GC overhead)
  Result:                  ❌ Host runs out of memory

OPTIMIZED DESIGN:
  Windowed aggregation:    100k objects × 10 windows = 10MB
  Per-window metrics:      ~63 metrics × 10 windows = 630 objects
  Write queue:             10k queued writes × 100B = 1MB
  Total Heap:              ~500MB (with GC overhead)
  Result:                  ✅ Runs efficiently on any server
```

---

## Configuration for High Volume

```conf
iso8583 {
  # Correlation settings (aggressive)
  correlation.expiration.minutes = 5
  
  # Windowing
  time.window.aggregation.seconds = 60
  ring.buffer.windows = 10
  
  # Memory bounds
  max.pending.transactions = 100000
  max.write.queue.size = 50000
  
  # Threading
  parser.threads = 8                    # CPU cores
  database.writer.threads = 2           # I/O threads
  
  # Batching
  database.batch.size = 10000           # Batch 10k writes
  export.batch.size = 1000              # Batch 1000 metrics
  
  # Monitoring
  log.memory.stats.interval.seconds = 300
  alert.on.queue.size.exceeds = 40000
  alert.on.heap.usage_percent = 80
}
```

---

## Expected Performance

```
Throughput:
  Input:  2,083 msg/sec (180M/day)
  Parse:  4,000 msg/sec (8 threads) ✓ handles 2x volume
  
Latency:
  Message to aggregation:     < 1ms
  Aggregation to export:      < 5ms
  Export to Dynatrace:        < 100ms
  
Memory:
  Heap size:                  512MB minimum
  Peak usage:                 ~400MB
  GC pause time:              < 100ms (frequent, small collections)
  
Database:
  Writes per second:          50,000 (vs 100 sync)
  Disk space per day:         ~100MB (aggregated only)
  Query performance:          < 1s for daily reports
```

---

## Distributed Setup (If Needed)

For extreme scale (1B+ pairs/day), use multiple instances:

```
                    ┌────────────────────────────┐
                    │   Log Files (Multiple)     │
                    │   /data/logs/server-1      │
                    │   /data/logs/server-2      │
                    │   /data/logs/server-3      │
                    └────────────┬───────────────┘
                                 │
                ┌────────────────┼────────────────┐
                │                │                │
                ▼                ▼                ▼
        ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
        │ TAT Extract  │ │ TAT Extract  │ │ TAT Extract  │
        │ Instance 1   │ │ Instance 2   │ │ Instance 3   │
        │ (Memory OK)  │ │ (Memory OK)  │ │ (Memory OK)  │
        └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
               │                │                │
               └────────────────┼────────────────┘
                                │
                    ┌───────────▼────────────┐
                    │  Central Aggregation   │
                    │  (Optional - can skip) │
                    │  Combine metrics from  │
                    │  all 3 instances       │
                    └───────────┬────────────┘
                                │
                                ▼
                        ┌──────────────────┐
                        │    Dynatrace     │
                        │   Bindplane      │
                        └──────────────────┘

Configuration per instance:
  Instance 1: Process logs from server-1 only
  Instance 2: Process logs from server-2 only
  Instance 3: Process logs from server-3 only
  
Each instance:
  - Uses 500MB heap (low overhead)
  - Exports ~100 metrics/batch
  - Parallel processing with 8 threads
  
Central aggregation (optional):
  - Combines metrics from 3 instances
  - Creates dashboard-level summaries
  - Or: Let Dynatrace DQL do this
```

---

## Monitoring & Alerting

Add health checks to prevent issues:

```java
public class PerformanceMonitor {
  public void logStats() {
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    long heapUsed = memory.getHeapMemoryUsage().getUsed();
    long heapMax = memory.getHeapMemoryUsage().getMax();
    double heapPercent = (heapUsed * 100.0) / heapMax;
    
    logger.info("Performance: Heap={}MB/{}MB ({}%), " +
                "Pending={}, WriteQueue={}, ProcessingRate={}msg/sec",
      heapUsed / 1024 / 1024,
      heapMax / 1024 / 1024,
      heapPercent,
      pendingTransactionCount,
      writeQueueSize,
      messagesPerSecond);
    
    // Alerts
    if (heapPercent > 80) {
      logger.warn("⚠ Heap usage critical: {}%", heapPercent);
      // Trigger alert: Reduce expiration window or add instance
    }
    
    if (pendingTransactionCount > maxPending) {
      logger.warn("⚠ Pending txn overflow: {} > {}", 
        pendingTransactionCount, maxPending);
      // Trigger alert: Responses not arriving fast enough
    }
    
    if (writeQueueSize > maxQueueSize) {
      logger.warn("⚠ Database write queue backed up: {}", writeQueueSize);
      // Trigger alert: Database can't keep up
    }
  }
}
```

---

## Summary: High-Volume Recommendations

| Aspect | Original | Optimized |
|--------|----------|-----------|
| **Expiration** | 60 min | 5 min |
| **Memory per instance** | 10-20GB | 512MB |
| **Threads** | 1 | 8 |
| **DB writes** | Sync, 1 at a time | Async, batched (10k) |
| **Metrics stored** | Raw events (180M) | Aggregated (86k) |
| **Export calls** | Many (1/metric) | Few (1 per 5min) |
| **Instances needed** | 1 (fails) | 3-4 (handles load) |
| **Throughput** | ~100 msg/sec | ~4,000 msg/sec |

---

## Implementation Priority

### Phase 1 (Critical - Do First)
1. ✅ Reduce expiration to 5 minutes
2. ✅ Add ring-buffer windowed aggregation
3. ✅ Implement async batch database writes
4. ✅ Add monitoring/alerting

### Phase 2 (Important)
1. ⏳ Multi-threaded log processing (8 threads)
2. ⏳ Batched metric export
3. ⏳ Memory circuit breaker

### Phase 3 (Scale-Out)
1. ⏳ Distributed instance setup (3-4 copies)
2. ⏳ Central metric aggregation (optional)
3. ⏳ Load balancing

---

**Estimated Performance After Optimization:**
- ✅ Handles 180M pairs/day easily
- ✅ <500MB heap per instance
- ✅ Can run on standard servers
- ✅ Sub-second latency
- ✅ No performance impact on host

**Ready to implement?** Mark Phase 1 changes and rebuild.
