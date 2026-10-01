import java.nio.file.*;
import java.io.*;

public class DetailedDebugTest {
    public static void main(String[] args) throws Exception {
        StringBuilder log = new StringBuilder();
        log.append("=== DETAILED DEBUG TEST ===\n\n");

        // Test 1: Check file reading
        log.append("TEST 1: Reading CIDC_001.txt\n");
        Path testFile = Paths.get("test_logs/CIDC_001.txt");
        String fileContent = new String(Files.readAllBytes(testFile));
        log.append("✅ File read successfully\n");
        log.append("Content length: ").append(fileContent.length()).append(" bytes\n\n");

        // Test 2: Check if ResumableFileReader would process it
        log.append("TEST 2: Message block splitting\n");
        String[] blocks = fileContent.split("<==========+>");
        log.append("Found ").append(blocks.length).append(" blocks\n");

        int requestCount = 0, responseCount = 0;
        for (int i = 0; i < blocks.length; i++) {
            String block = blocks[i].trim();
            if (block.isEmpty()) continue;

            if (block.contains("Received At")) requestCount++;
            if (block.contains("Sent At")) responseCount++;

            log.append("  Block ").append(i).append(": ");
            if (block.contains("Received At")) log.append("[REQUEST]");
            if (block.contains("Sent At")) log.append("[RESPONSE]");
            log.append(" - ").append(block.split("\n")[0]).append("\n");
        }
        log.append("Requests: ").append(requestCount).append(", Responses: ").append(responseCount).append("\n\n");

        // Test 3: Field extraction
        log.append("TEST 3: Field extraction from first message\n");
        String firstBlock = blocks[0];

        String stan = extractField(firstBlock, "Field 011");
        String pan = extractField(firstBlock, "Field 002");
        String channel = extractField(firstBlock, "Field 123");

        log.append("STAN (Field 011): ").append(stan).append("\n");
        log.append("PAN (Field 002): ").append(pan).append("\n");
        log.append("Channel (Field 123): ").append(channel).append("\n\n");

        // Test 4: Correlation key generation
        log.append("TEST 4: Correlation key\n");
        String correlationKey = pan + ":" + stan;
        log.append("Correlation key would be: ").append(correlationKey).append("\n\n");

        // Test 5: Timestamp parsing
        log.append("TEST 5: Timestamp parsing\n");
        String receivedTs = "[NOT FOUND]";
        String sentTs = "[NOT FOUND]";

        for (String block : blocks) {
            if (block.trim().isEmpty()) continue;
            if (block.contains("Received At") && receivedTs.equals("[NOT FOUND]")) {
                receivedTs = extractTimestamp(block, "Received At");
            }
            if (block.contains("Sent At") && sentTs.equals("[NOT FOUND]")) {
                sentTs = extractTimestamp(block, "Sent At");
            }
        }

        log.append("Request timestamp: ").append(receivedTs).append("\n");
        log.append("Response timestamp: ").append(sentTs).append("\n\n");

        // Test 6: TAT calculation
        log.append("TEST 6: TAT calculation\n");
        try {
            java.time.LocalDateTime rdt = java.time.LocalDateTime.parse(receivedTs,
                java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss.SSS"));
            java.time.LocalDateTime sdt = java.time.LocalDateTime.parse(sentTs,
                java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss.SSS"));

            long tatMs = java.time.temporal.ChronoUnit.MILLIS.between(rdt, sdt);
            log.append("TAT = ").append(tatMs).append(" ms\n");
            log.append("✅ TAT calculation successful!\n\n");
        } catch (Exception e) {
            log.append("❌ TAT calculation failed: ").append(e.getMessage()).append("\n\n");
        }

        // Test 7: Database check
        log.append("TEST 7: Database tables\n");
        Path dbPath = Paths.get("test_data/correlation.db");
        log.append("DB exists: ").append(Files.exists(dbPath)).append("\n");
        log.append("DB size: ").append(Files.size(dbPath)).append(" bytes\n");
        log.append("Note: To check if transactions were stored, run:\n");
        log.append("  sqlite3 test_data/correlation.db \"SELECT COUNT(*) FROM tat_transactions;\"\n\n");

        // Write to file
        Files.write(Paths.get("detailed_debug.txt"), log.toString().getBytes());
        System.out.println(log.toString());
        System.out.println("\n✅ Full debug output saved to detailed_debug.txt");
    }

    static String extractField(String block, String fieldLabel) {
        for (String line : block.split("\n")) {
            if (line.startsWith(fieldLabel)) {
                String[] parts = line.split(":", 2);
                if (parts.length > 1) {
                    return parts[1].trim();
                }
            }
        }
        return "[NOT FOUND]";
    }

    static String extractTimestamp(String block, String label) {
        for (String line : block.split("\n")) {
            if (line.startsWith(label)) {
                String[] parts = line.split(":", 2);
                if (parts.length > 1) {
                    return parts[1].trim();
                }
            }
        }
        return "[NOT FOUND]";
    }
}
