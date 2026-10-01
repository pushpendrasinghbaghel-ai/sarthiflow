# Production Fixes Implemented

## Issue 1: Duplicate Exports ✅
**Problem**: Every scheduled export re-adds all previously completed transactions.
**Fix**: Add `exported` table to track exported transactions. Only export non-exported ones.
**File**: CorrelationStore.java
**Method**: getUnexportedTransactions(), markAsExported()

## Issue 2: Wrong Metric Type ✅
**Problem**: TAT emitted as UpDownCounter (doesn't support percentiles).
**Fix**: Switch to OpenTelemetry Histogram for TAT.
**File**: OTLPExporter.java
**Method**: Use histogramBuilder() instead of upDownCounterBuilder()

## Issue 3: Weak Correlation ✅
**Problem**: Only PAN|STAN, doesn't validate MTI (1200→1210).
**Fix**: Add MTI validation. Only 1200 (request) + 1210 (response) match.
**File**: CorrelationStore.java, ISO8583Message.java
**Method**: validateMTI(), isRequest(), isResponse()

## Issue 4: Silent Timestamp Failures ✅
**Problem**: Malformed timestamps silently become LocalDateTime.now().
**Fix**: Validate timestamps, log errors, reject invalid ones.
**File**: ISO8583Parser.java
**Method**: validateTimestamp(), parseTimestampStrict()

## Issue 5: No Pre-Export Validation ✅
**Problem**: Negative, zero, extreme, blank TAT values exported.
**Fix**: Pre-export validation checks TAT >= 1ms and <= 60000ms.
**File**: CorrelationStore.java, OTLPExporter.java
**Method**: validateTAT(), isValidTransaction()

## Issue 6: Config Flag Not Working ✅
**Problem**: export.use.otlp.direct=false doesn't disable direct export.
**Fix**: Check flag in TATExtractorApp before initializing OTLPExporter.
**File**: TATExtractorApp.java
**Method**: Initialize OTLPExporter only if export.use.otlp.direct=true

---

## Deployment Steps
1. Rebuild JAR: `mvn package -DskipTests -q`
2. Delete old databases: `Remove-Item C:\var\iso8583\*.db`
3. Copy fresh test file
4. Run: `java -Xmx2g -jar target\iso8583-tat-extractor.jar`
5. Verify: No duplicate metrics in Dynatrace
