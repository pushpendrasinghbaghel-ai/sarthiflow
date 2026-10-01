# Integration Point: File Reading + TAT Extractor

**Important:** Your existing file reading system already handles:
- ✅ Tracking file positions (no re-reads on restart)
- ✅ Only processing new content
- ✅ Resumable reading after server restart
- ✅ Offset management per file

**This TAT Extractor:** Acts as the PROCESSOR, not the file reader.

---

## Architecture: Separation of Concerns

```
YOUR SYSTEM                          TAT EXTRACTOR (This Project)
(File Reading)                       (Processing)
═══════════════════════════════════════════════════════════════════

Log Files                                   
  ├─ CIDC_Log_001.txt                      
  ├─ CIDC_Log_002.txt                      
  └─ CIDC_Log_003.txt                      

       │                                    
       ▼                                    
┌──────────────────────┐                  
│ Your File Reader     │                  
│ - Tracks offsets     │                  
│ - Resumes on restart │                  
│ - Reads only NEW     │                  
│   content            │                  
│ - Line buffer        │                  
└──────────┬───────────┘                  
           │                              
           │ (Stream of ISO8583          
           │  message blocks)            
           │                              
           ▼                              ┌──────────────────────┐
      ISO8583                            │ TAT Extractor        │
      Message Block                      │ - Parse message      │
      (Multiline text)                   │ - Extract fields     │
                                         │ - Correlate req↔resp │
                                         │ - Calculate TAT      │
                                         │ - Aggregate metrics  │
                                         └──────────┬───────────┘
                                                    │
                                                    ▼
                                         ┌──────────────────────┐
                                         │ Output: Aggregated   │
                                         │ Metrics (JSON)       │
                                         │ - count, avg, p95    │
                                         │ - channel, response  │
                                         │ - time_bucket        │
                                         └──────────────────────┘
                                                    │
                                                    ▼
                                         ┌──────────────────────┐
                                         │ Dynatrace            │
                                         │ Bindplane HTTP POST  │
                                         └──────────────────────┘
```

---

## How to Integrate

### Option 1: Direct Java Integration (Recommended)

Your file reader → TAT Extractor's message processor directly:

```java
// Your existing file reader code
public class YourFileReader {
  private final CorrelationStore correlationStore;
  private final ISO8583Parser parser;
  
  public void processNewLines(List<String> newLines) {
    // You handle file position tracking
    // You read only new lines
    // You pass them to TAT Extractor
    
    String messageBlock = assembleMessageBlock(newLines);
    if (messageBlock != null && !messageBlock.isEmpty()) {
      ISO8583Message message = parser.parseMessage(messageBlock);
      correlationStore.processMessage(message);  // TAT Extractor processes
    }
  }
}

// TAT Extractor - only handles the processing
public class CorrelationStore {
  public void processMessage(ISO8583Message message) {
    // This is called with already-read, new messages only
    // You handle: correlation, TAT calculation, aggregation
    if ("Received".equalsIgnoreCase(message.getDirection())) {
      pendingTransactions.putIfAbsent(correlationKey, new TATTransaction(...));
    } else if ("Sent".equalsIgnoreCase(message.getDirection())) {
      // Match and calculate
    }
  }
}
```

### Option 2: Named Pipe / Queue Integration

Your file reader → Message queue → TAT Extractor:

```
File Reader              Named Pipe/Queue         TAT Extractor
(Your System)           (Inter-process)          (This Project)

┌─────────────┐
│ Read file   │         ┌──────────┐         ┌─────────────┐
│ Track offset│────────►│ Message  │────────►│ Process     │
│ Only NEW    │         │ Queue    │         │ Correlate   │
└─────────────┘         │ (FIFO)   │         │ Export      │
                        └──────────┘         └─────────────┘
                        
Per-server:
  /tmp/iso8583_queue_server1
  /tmp/iso8583_queue_server2
  /tmp/iso8583_queue_server3
```

Implementation:
```java
// Your file reader writes to queue
public class YourFileReader {
  private Queue<String> messageQueue = new LinkedBlockingQueue<>();
  
  public void readNewLines() {
    for (String line : newLinesFromFile) {
      messageQueue.offer(line);  // Send to TAT Extractor
    }
  }
}

// TAT Extractor reads from queue
public class TATExtractorApp {
  public void run() {
    while (true) {
      String line = messageQueue.poll(1, TimeUnit.SECONDS);
      if (line != null) {
        processLine(line);  // TAT Extractor logic
      }
    }
  }
}
```

### Option 3: REST/HTTP Integration

Your file reader → HTTP → TAT Extractor:

```
Your File Reader         HTTP POST           TAT Extractor
(Your System)           (Network)           (This Project)

Reads file
Tracks offset
Gets new ISO8583 blocks
        │
        ▼
POST /api/v1/message
Content-Type: application/json
{
  "message_type": "REQUEST|RESPONSE",
  "fields": {
    "002": "9229814714205000026",
    "011": "661765234767",
    "123": "UPI",
    ...
  }
}
        │
        ▼
TAT Extractor REST endpoint
  - Receives message JSON
  - Parses fields
  - Correlates
  - Calculates TAT
  - Returns status: {status: "processed", tat_ms: 186}
```

---

## Modified TAT Extractor (No File Reading)

Since you handle file reading, simplify the TAT Extractor:

### Remove These Classes:
- ❌ `ISO8583Parser.splitMessageBlocks()` — You already do this
- ❌ File watching logic
- ❌ `processLogFile()` method

### Keep These Classes:
- ✅ `ISO8583Parser.parseMessage()` — Still needed (parse fields)
- ✅ `ISO8583Message` — Represent parsed message
- ✅ `TATTransaction` — Request+response pair
- ✅ `CorrelationStore` — Core correlation logic
- ✅ `MetricsExporter` — Export aggregated metrics

### New Entry Point:

```java
public class TATExtractorAPI {
  private CorrelationStore correlationStore;
  private MetricsExporter metricsExporter;
  private ISO8583Parser parser;
  
  // Called by your file reader
  public void processMessage(String messageBlock) {
    ISO8583Message message = parser.parseMessage(messageBlock);
    correlationStore.processMessage(message);
  }
  
  // Called periodically
  public void exportMetrics() {
    metricsExporter.exportMetrics();
  }
  
  // Health check
  public Map<String, Object> getStatus() {
    return Map.of(
      "pending_transactions", correlationStore.getPendingTransactionCount(),
      "completed_transactions", correlationStore.getCompletedTransactions().size(),
      "memory_mb", Runtime.getRuntime().totalMemory() / 1024 / 1024
    );
  }
}
```

---

## Configuration: Integration Mode

```conf
iso8583 {
  # NO file reading configuration needed
  # (Your system provides the messages)
  
  # Only keep correlation & export settings
  correlation.expiration.minutes = 5
  time.window.aggregation.seconds = 60
  
  # Where to export metrics
  bindplane.url = "https://dynatrace.../api/v1/logs/ingest"
  export.interval.seconds = 300
  
  # Integration mode (file reading disabled)
  file.reading.enabled = false  # YOUR system handles this
  message.source = "api"        # Messages come from external source
}
```

---

## Data Flow: Your System + TAT Extractor

```
Your File Reading System (Per Server)
────────────────────────────────────

/var/log/payment/CIDC_Log_001.txt
    │
    ├─ Last read offset: byte 45,678
    │
    ├─ New content (from byte 45,679):
    │  "Pid: 22664 Received At: 12:02:26.721
    │   MessageId: 1200
    │   Field 002: 9229814714205000026
    │   ...
    │   <=========>
    │   
    │   Pid: 22664 Sent At: 12:02:26.907
    │   MessageId: 1210
    │   ..."
    │
    ▼
Your file reader:
  1. Detects new content since last offset
  2. Splits by "<========>" separator ← You do this
  3. For each message block:
     ▼
     ┌──────────────────────────────────────┐
     │ TAT Extractor.processMessage(block)  │
     │                                      │
     │ ISO8583Parser.parseMessage(block)    │
     │  → ISO8583Message (fields extracted) │
     │                                      │
     │ CorrelationStore.processMessage(msg) │
     │  → Match request ↔ response          │
     │  → Calculate TAT                     │
     │  → Store in SQLite                   │
     │  → Update aggregation window         │
     └──────────────────────────────────────┘
  4. Update offset file: byte 67,890 (don't re-read)

Every 5 minutes:
  ┌──────────────────────────────────────┐
  │ TAT Extractor.exportMetrics()         │
  │                                      │
  │ Get aggregated metrics               │
  │ POST to Dynatrace Bindplane          │
  │                                      │
  │ Clear completed transactions         │
  └──────────────────────────────────────┘

On Server Restart:
  1. File reader resumes from last offset
  2. TAT Extractor loads pending transactions from SQLite
  3. Processing continues as if no restart happened ✅
```

---

## Integration Checklist

- [ ] Your file reader already handles:
  - [ ] Tracking file offsets
  - [ ] Detecting new content only
  - [ ] Resuming after restart
  - [ ] Splitting messages by `<=========>`

- [ ] TAT Extractor handles:
  - [ ] `parseMessage(String block)` — Extract fields
  - [ ] `processMessage(ISO8583Message)` — Correlate & TAT
  - [ ] `exportMetrics()` — Send to Dynatrace
  - [ ] SQLite persistence — Survive restarts

- [ ] Integration point:
  - [ ] Your reader → TAT Extractor.processMessage()
  - [ ] Scheduled: TAT Extractor.exportMetrics()
  - [ ] Ensure thread-safe (CorrelationStore is synchronized)

---

## Code Example: Your File Reader + TAT Extractor

```java
public class PaymentLogProcessor {
  private final TATExtractorAPI tatExtractor;
  private final FileOffsetTracker offsetTracker;
  private final ISO8583Parser parser;
  
  public void processNewLogsFromAllFiles(List<Path> logFiles) {
    for (Path logFile : logFiles) {
      // YOUR SYSTEM: Get new content only
      long lastOffset = offsetTracker.getLastOffset(logFile);
      String newContent = readNewContent(logFile, lastOffset);
      
      // Split messages (YOUR SYSTEM already does this)
      String[] messageBlocks = newContent.split("<=========>");
      
      // TAT EXTRACTOR: Process each message
      for (String block : messageBlocks) {
        if (!block.trim().isEmpty()) {
          tatExtractor.processMessage(block);  // ← Single integration point
        }
      }
      
      // YOUR SYSTEM: Update offset for next run
      offsetTracker.updateOffset(logFile, newContent.length());
    }
  }
  
  // Scheduled every 5 minutes
  @Scheduled(fixedRate = 300000)
  public void exportMetrics() {
    tatExtractor.exportMetrics();  // ← TAT Extractor exports
  }
  
  // On server restart
  public void onServerStart() {
    // TAT Extractor loads from SQLite
    tatExtractor.loadPendingTransactions();
    
    // Your system resumes from last offset
    offsetTracker.load();
    
    // Continue processing
    processNewLogsFromAllFiles(logFiles);
  }
}
```

---

## Benefits of This Separation

```
Your System Responsibility:
  ✓ File I/O efficiency
  ✓ Offset tracking
  ✓ Resume on restart
  ✓ Only read new content
  ✓ Handle any file format variations
  ✓ Proven, production-tested

TAT Extractor Responsibility:
  ✓ Parse ISO8583 fields
  ✓ Correlate request/response
  ✓ Calculate TAT
  ✓ Aggregate metrics
  ✓ Export to Dynatrace
  ✓ SQLite persistence

Result:
  ✓ Clean separation
  ✓ Your system is unchanged
  ✓ TAT Extractor is lightweight processor
  ✓ Easy to test & debug
  ✓ Can swap components if needed
```

---

## Deployment Implication

```
Current (Your System):
  Process A: File Reader (reads new content, tracks offset)
  → Message queue/buffer
  
New (With TAT Extractor):
  Process A: File Reader (as before)
  → Calls TAT Extractor.processMessage() ← SINGLE integration point
  
  Process B: TAT Extractor (Java)
  → Correlates, calculates, aggregates
  → Exports to Dynatrace every 5 min
  
Result:
  ✓ Your file reading unchanged
  ✓ TAT Extractor is additional light process
  ✓ Both have clear responsibilities
  ✓ Easy to monitor & troubleshoot
```

---

## Summary

**Your File Reading System: Already optimized** ✅
- Offset tracking
- No re-reads on restart
- Efficient handling

**TAT Extractor: Processing layer only**
- Receives message blocks
- Correlates & calculates
- Exports metrics

**Integration: Single method call**
```java
tatExtractor.processMessage(messageBlock);
```

**Deployment: 200 servers, minimal overhead**
- Small Java process per server
- Handles message processing
- Exports aggregated metrics
- ~50-100MB RAM per instance

**Ready to integrate!** ✅
