import java.sql.*;

public class TestTATExtractor {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:data/correlation.db")) {
            System.out.println("\n=== TAT Extractor Test Results ===\n");

            // Check if tables exist
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet tables = meta.getTables(null, null, "tat_transactions", new String[]{"TABLE"});

            if (!tables.next()) {
                System.out.println("❌ Table tat_transactions not found!");
                return;
            }

            System.out.println("✓ Database and table created successfully");

            // Count transactions
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM tat_transactions")) {
                rs.next();
                int count = rs.getInt("cnt");
                System.out.println("✓ Total transactions in database: " + count);

                if (count == 0) {
                    System.out.println("⚠ No transactions found. Check log parsing.");
                    return;
                }
            }

            // Display transaction details
            System.out.println("\n=== Transaction Details ===\n");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT correlation_key, stan, channel, tat_millis, response_code, completed_at " +
                     "FROM tat_transactions ORDER BY completed_at")) {

                System.out.printf("%-40s | %-10s | %-10s | %-10s | %-5s%n",
                    "Correlation Key", "STAN", "Channel", "TAT (ms)", "Code");
                System.out.println(new String(new char[100]).replace('\0', '-'));

                while (rs.next()) {
                    System.out.printf("%-40s | %-10s | %-10s | %-10d | %-5s%n",
                        rs.getString("correlation_key"),
                        rs.getString("stan"),
                        rs.getString("channel"),
                        rs.getLong("tat_millis"),
                        rs.getString("response_code"));
                }
            }

            // Summary statistics
            System.out.println("\n=== Summary Statistics ===\n");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT " +
                     "  COUNT(*) as total, " +
                     "  ROUND(AVG(tat_millis), 2) as avg_tat, " +
                     "  MIN(tat_millis) as min_tat, " +
                     "  MAX(tat_millis) as max_tat, " +
                     "  COUNT(CASE WHEN response_code = '000' THEN 1 END) as success_count, " +
                     "  COUNT(CASE WHEN response_code = 'TIMEOUT' THEN 1 END) as timeout_count " +
                     "FROM tat_transactions")) {

                rs.next();
                System.out.println("Total Transactions: " + rs.getInt("total"));
                System.out.println("Average TAT: " + rs.getDouble("avg_tat") + " ms");
                System.out.println("Min TAT: " + rs.getLong("min_tat") + " ms");
                System.out.println("Max TAT: " + rs.getLong("max_tat") + " ms");
                System.out.println("Successful (code 000): " + rs.getInt("success_count"));
                System.out.println("Timeouts: " + rs.getInt("timeout_count"));
            }

            // By channel
            System.out.println("\n=== Breakdown by Channel ===\n");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT channel, COUNT(*) as cnt, ROUND(AVG(tat_millis), 2) as avg_tat " +
                     "FROM tat_transactions GROUP BY channel ORDER BY channel")) {

                System.out.printf("%-10s | %-10s | %-10s%n", "Channel", "Count", "Avg TAT (ms)");
                System.out.println(new String(new char[35]).replace('\0', '-'));

                while (rs.next()) {
                    System.out.printf("%-10s | %-10d | %-10.2f%n",
                        rs.getString("channel"),
                        rs.getInt("cnt"),
                        rs.getDouble("avg_tat"));
                }
            }

            System.out.println("\n✓ Test completed successfully!\n");
        }
    }
}
