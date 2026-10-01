# Quick Start Guide

Get the ISO8583 TAT Extractor running in 5 minutes.

## Prerequisites Check

```bash
# Java 11+
java -version
# Output: openjdk version "11.0.x" or higher âœ“

# Maven 3.8+
mvn -version
# Output: Apache Maven 3.8.x âœ“
```

If missing, install:
- **macOS**: `brew install openjdk@11 maven`
- **Linux**: `sudo apt install openjdk-11-jdk maven`
- **Solaris**: `pkg install jdk-11` or download from Oracle

## Build (2 minutes)

```bash
cd iso8583-tat-extractor
mvn clean package
```

Output:
```
BUILD SUCCESS
[INFO] Building jar: target/iso8583-tat-extractor.jar
```

## Configure (1 minute)

Create `application.conf`:

```conf
iso8583 {
  log.path = "./sample-data/sample-logs.txt"
  db.path = "./data/correlation.db"
  bindplane.url = "http://localhost:4318/v1/metrics"
  correlation.expiration.minutes = 60
  export.interval.seconds = 60
  watch.enabled = false
}
```

## Test with Sample Data (2 minutes)

### Setup

```bash
mkdir -p data
ls -la sample-data/sample-logs.txt
```

### Run

```bash
java -jar target/iso8583-tat-extractor.jar application.conf
```

### Expected Output

```
[INFO] Configuration loaded: Configuration{...}
[INFO] Processing log file: ./sample-data/sample-logs.txt
[DEBUG] Processed message: ISO8583Message{...stan=661765234767...}
[DEBUG] Processed message: ISO8583Message{...stan=661765234767...}
[INFO] Completed transaction: 9229814714205000026|661765234767 TAT=186ms
[INFO] Completed transaction: 9229814714205000027|661765234768 TAT=442ms
[INFO] Completed transaction: 9229814714205000028|661765234769 TAT=455ms
[INFO] Exporting metrics to Bindplane: http://localhost:4318/v1/metrics
```

### Verify Database

```bash
sqlite3 data/correlation.db
```

Inside SQLite:

```sql
SELECT COUNT(*) FROM tat_transactions;
-- Output: 3

SELECT * FROM tat_transactions LIMIT 1;
-- Shows TAT, channel, response code, etc.

SELECT channel, COUNT(*), AVG(tat_millis) FROM tat_transactions 
  GROUP BY channel;
-- Channel breakdown
```

## Test with Real Logs

### Step 1: Prepare Your Log File

```bash
# Copy your SarthiFlow payment logs
cp /var/log/payment/CIDC_SampleLogs.txt ./my-logs.txt

# Or use the sample data
cp sample-data/sample-logs.txt ./my-logs.txt
```

### Step 2: Update Config

```conf
iso8583 {
  log.path = "./my-logs.txt"
  db.path = "./data/correlation.db"
  bindplane.url = "http://localhost:4318/v1/metrics"
  correlation.expiration.minutes = 60
  export.interval.seconds = 60
  watch.enabled = false
}
```

### Step 3: Run

```bash
java -jar target/iso8583-tat-extractor.jar application.conf
```

### Step 4: Check Results

```bash
# Count transactions
sqlite3 data/correlation.db "SELECT COUNT(*) FROM tat_transactions;"

# Check unmatched (TIMEOUT)
sqlite3 data/correlation.db "SELECT response_code, COUNT(*) FROM tat_transactions GROUP BY response_code;"

# View metrics summary
sqlite3 data/correlation.db "SELECT channel, COUNT(*), ROUND(AVG(tat_millis)), MIN(tat_millis), MAX(tat_millis) FROM tat_transactions WHERE tat_millis > 0 GROUP BY channel;"
```

## Watch Mode (Continuous Processing)

Process logs as they're updated:

```conf
iso8583.watch.enabled = true
iso8583.export.interval.seconds = 60
```

Then run:

```bash
java -jar target/iso8583-tat-extractor.jar application.conf
```

The app will:
- Monitor log file for changes
- Process new messages immediately
- Export metrics every 60 seconds
- Keep running until you press Ctrl+C

## Send Metrics to Bindplane

### Option 1: Local Bindplane Receiver

If running Bindplane locally:

```bash
# In Bindplane, set up HTTP receiver on :4318
# Then update config:
# bindplane.url = "http://localhost:4318/v1/metrics"
```

Verify connection:

```bash
curl -v http://localhost:4318/v1/metrics
# Should get response (even if metrics are rejected, connection works)
```

### Option 2: Dynatrace Managed

If using Dynatrace Managed Bindplane:

```bash
# Get your tenant ID
# From: https://<TENANT>.managed.apps.dynatrace.com

# Update config:
bindplane.url = "https://<TENANT>.managed.apps.dynatrace.com/api/v1/logs"
```

Test connectivity:

```bash
curl -v "https://<TENANT>.managed.apps.dynatrace.com/api/v1/logs" \
  -H "Content-Type: application/json" \
  -d '{"test": "metric"}'
```

## Verify Metrics in Dynatrace

### In Dynatrace Managed UI

1. Navigate to **Explore â†’ Custom Metrics**
2. Search for `payment.transaction.tat`
3. Should see:
   - **payment.transaction.tat** â€” individual transaction TAT
   - **payment.channel.tat** â€” aggregated by channel (UPI, CARD, WALLET)
   - **payment.response_code.tat** â€” aggregated by response code

### Example Query in Dynatrace DQL

```dql
timeseries avg(payment.transaction.tat),
  by: {channel, response_code}
```

## Troubleshooting Quick Fixes

### 1. JAR Not Found

```bash
# Make sure you're in the right directory
pwd  # Should end with iso8583-tat-extractor

# Rebuild
mvn clean package
ls -la target/iso8583-tat-extractor.jar
```

### 2. Config File Not Found

```bash
# Check config path
ls -la application.conf

# Or specify full path
java -jar target/iso8583-tat-extractor.jar /full/path/to/application.conf
```

### 3. Log File Not Found

```bash
# Verify sample logs exist
ls -la sample-data/sample-logs.txt

# Or update config to point to your actual log file
sed -i 's|sample-data/sample-logs.txt|/your/actual/path|' application.conf
```

### 4. No Transactions Processed

```bash
# Check if log format is correct
head -50 sample-data/sample-logs.txt | grep "Field 011"
# Should show: Field 011: 661765234767

# Enable debug logging
# In application.conf, set: logging.level = "DEBUG"
# Then run again
```

### 5. Metrics Not Exported

```bash
# Check Bindplane connectivity
curl -v http://localhost:4318/v1/metrics

# Check export interval
sqlite3 data/correlation.db "SELECT COUNT(*) FROM tat_transactions;"

# If transactions exist but not exported, check logs for errors
grep "ERROR\|Exception" tat-extractor.log
```

## Next Steps

### Development

- Modify `src/main/java/com/sarthiflow/payment/iso8583/` for custom logic
- Add new metrics in `TATMetricsExporter.java`
- Test with `mvn test`
- Rebuild: `mvn clean package`

### Production Deployment

1. Follow [README.md](README.md) for Solaris 11 deployment
2. Set up systemd service for auto-restart
3. Monitor with:
   ```bash
   systemctl status iso8583-tat
   journalctl -u iso8583-tat -f
   ```

### Performance Tuning

For high volume (500k+ txns/day):

```bash
java -Xmx4g -Xms2g \
  -jar target/iso8583-tat-extractor.jar \
  application.conf
```

---

**Done!** You now have TAT metrics flowing to Dynatrace. Next, create dashboards for:
- Transaction-level TAT by channel
- Percentile trends (p95, p99)
- Success/failure rates
- Alert on TAT threshold breaches

See [README.md](README.md) for production setup and [EDGE_CASES.md](EDGE_CASES.md) for handling non-adjacent messages.

