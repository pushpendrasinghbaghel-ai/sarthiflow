import java.nio.file.*;
import java.io.*;

public class DebugTest {
    public static void main(String[] args) throws Exception {
        StringBuilder log = new StringBuilder();
        log.append("=== DEBUG TEST ===\n");

        // Check if test log file exists
        Path testFile = Paths.get("test_logs/CIDC_001.txt");
        log.append("Test file exists: ").append(Files.exists(testFile)).append("\n");

        if (Files.exists(testFile)) {
            String content = new String(Files.readAllBytes(testFile));
            log.append("File size: ").append(content.length()).append(" bytes\n");
            log.append("File content:\n").append(content).append("\n");

            // Check parsing
            String[] blocks = content.split("<=========+>");
            log.append("\nFound ").append(blocks.length).append(" message blocks\n");

            for (int i = 0; i < blocks.length; i++) {
                String block = blocks[i];
                log.append("\n--- Block ").append(i).append(" ---\n");
                log.append(block).append("\n");

                // Check for expected patterns
                log.append("Has 'Received At': ").append(block.contains("Received At")).append("\n");
                log.append("Has 'Sent At': ").append(block.contains("Sent At")).append("\n");
                log.append("Has 'Field 011': ").append(block.contains("Field 011")).append("\n");
                log.append("Has 'Field 002': ").append(block.contains("Field 002")).append("\n");
            }
        }

        // Check database
        Path dbFile = Paths.get("test_data/correlation.db");
        log.append("\nDatabase exists: ").append(Files.exists(dbFile)).append("\n");
        if (Files.exists(dbFile)) {
            log.append("DB size: ").append(Files.size(dbFile)).append(" bytes\n");
        }

        // Write log to file
        Files.write(Paths.get("debug_output.txt"), log.toString().getBytes());
        System.out.println("Debug output written to debug_output.txt");
        System.out.println(log.toString());
    }
}
