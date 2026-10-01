import java.sql.*;
import java.util.*;

public class CornerCaseTest {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:data/corner-test.db")) {
            System.out.println("\n=== CORNER CASE TEST RESULTS ===\n");
            
            // Count all transactions
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as cnt FROM tat_transactions")) {
                rs.next();
                System.out.println("✓ Total transactions processed: " + rs.getInt("cnt"));
            }
            
            // Check correlation success
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                   "SELECT COUNT(*) as matched, " +
                   "COUNT(CASE WHEN response_code = 'TIMEOUT' THEN 1 END) as timeout " +
                   "FROM tat_transactions WHERE tat_millis > 0")) {
                rs.next();
                System.out.println("✓ Matched pairs: " + rs.getInt("matched"));
                System.out.println("✓ Timeouts: " + rs.getInt("timeout"));
            }
            
            // Check TAT values
            System.out.println("\n=== TAT ANALYSIS ===\n");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                   "SELECT channel, COUNT(*) as cnt, AVG(tat_millis) as avg_tat, " +
                   "MIN(tat_millis) as min_tat, MAX(tat_millis) as max_tat " +
                   "FROM tat_transactions WHERE tat_millis > 0 GROUP BY channel")) {
                System.out.printf("%-10s | %-5s | %-8s | %-8s | %-8s%n", 
                    "Channel", "Count", "Avg TAT", "Min TAT", "Max TAT");
                System.out.println(new String(new char[50]).replace('\0', '-'));
                while (rs.next()) {
                    System.out.printf("%-10s | %-5d | %-8.0f | %-8d | %-8d%n",
                        rs.getString("channel"),
                        rs.getInt("cnt"),
                        rs.getDouble("avg_tat"),
                        rs.getLong("min_tat"),
                        rs.getLong("max_tat"));
                }
            }
            
            // Check response codes
            System.out.println("\n=== RESPONSE CODES ===\n");
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                   "SELECT response_code, COUNT(*) as cnt FROM tat_transactions " +
                   "GROUP BY response_code ORDER BY response_code")) {
                System.out.printf("%-10s | %-5s%n", "Code", "Count");
                System.out.println(new String(new char[20]).replace('\0', '-'));
                while (rs.next()) {
                    System.out.printf("%-10s | %-5d%n",
                        rs.getString("response_code"),
                        rs.getInt("cnt"));
                }
            }
            
            System.out.println("\n✓ Corner case tests completed!");
        }
    }
}
