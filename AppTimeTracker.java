import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Windows Foreground Window Time Tracker v4.4
 * Uses SQLite to record each app usage session.
 * Each app switch creates one record: app_name, start, end, duration.
 * Tracking runs in background thread, menu stays interactive.
 *
 * DB: data/usagelog.db (auto-created)
 * Table: usage_log (id, app_name, start_time, end_time, duration_sec)
 */
public class AppTimeTracker {

    private static volatile boolean trackingActive = false;
    private static Thread trackingThread = null;

    private static final int SCAN_INTERVAL_SECONDS = 2;
    private static final String DATA_DIR = "data";
    private static final String DB_NAME = "usagelog.db";
    private static final String PS_SCRIPT = "get-foreground.ps1";

    private static String currentDate = "";
    private static String currentApp = "";
    private static Instant currentAppStart = null;
    private static Connection dbConn = null;

    public static void main(String[] args) {
        String baseDir = getBaseDir();
        initDb(baseDir);

        java.io.BufferedReader br = new java.io.BufferedReader(
            new java.io.InputStreamReader(System.in));

        while (true) {
            System.out.println("========================================");
            System.out.println("  AppTimeTracker v4.4");
            if (trackingActive) System.out.println("  [TRACKING ACTIVE]");
            System.out.println("========================================");
            System.out.println();
            System.out.println("  [1] " + (trackingActive ? "Stop tracking" : "Start tracking"));
            System.out.println("  [2] View today's statistics");
            System.out.println("  [3] View this week's statistics");
            System.out.println("  [4] View this month's statistics");
            System.out.println("  [5] View all-time statistics");
            System.out.println("  [6] Export report to file");
            System.out.println("  [0] Exit");
            System.out.println();
            System.out.print("Choose option (0-6): ");

            String choice = null;
            try {
                String line = br.readLine();
                if (line != null) choice = line.trim();
            } catch (java.io.IOException ignored) {}

            if (choice == null || choice.isEmpty()) {
                System.out.println("Invalid input.\n");
                continue;
            }

            switch (choice) {
                case "0":
                    stopTracking(baseDir);
                    System.out.println("Bye!");
                    closeDb();
                    return;
                case "1":
                    if (trackingActive) {
                        stopTracking(baseDir);
                        System.out.println("Tracking stopped.\n");
                    } else {
                        startTrackingBackground(baseDir);
                        System.out.println("Tracking started in background.\n");
                    }
                    break;
                case "2":
                    generateDailyReport(baseDir, LocalDate.now().toString());
                    break;
                case "3":
                    generateRangeReport(baseDir, getThisWeekRange());
                    break;
                case "4":
                    generateRangeReport(baseDir, getThisMonthRange());
                    break;
                case "5":
                    generateAllTimeReport(baseDir);
                    break;
                case "6":
                    exportReportMenu(baseDir);
                    break;
                default:
                    System.out.println("Invalid choice.\n");
            }
        }
    }

    private static void startTrackingBackground(String baseDir) {
        trackingActive = true;
        trackingThread = new Thread(() -> {
            while (trackingActive) {
                try {
                    trackForeground(baseDir);
                    Thread.sleep(SCAN_INTERVAL_SECONDS * 1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
            flushCurrentApp(baseDir);
        }, "TrackingThread");
        trackingThread.setDaemon(true);
        trackingThread.start();
    }

    private static void stopTracking(String baseDir) {
        trackingActive = false;
        if (trackingThread != null && trackingThread.isAlive()) {
            try {
                trackingThread.join(3000);
            } catch (InterruptedException ignored) {}
        }
        flushCurrentApp(baseDir);
    }

    private static void startTracking(String baseDir) {
        // Legacy: direct tracking mode (blocking)
        System.out.println();
        System.out.println("Press Ctrl+C to stop and exit");
        System.out.println("Tracking started...\n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flushCurrentApp(baseDir);
            closeDb();
            System.out.println("\nDatabase closed. Bye!");
        }));

        while (true) {
            try {
                trackForeground(baseDir);
                Thread.sleep(SCAN_INTERVAL_SECONDS * 1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static void generateReport(String baseDir) {
        generateAllTimeReport(baseDir);
    }

    private static void generateDailyReport(String baseDir, String date) {


        String sql = "SELECT app_name, SUM(duration_sec) as total_secs, COUNT(*) as sessions " +
                     "FROM usage_log WHERE date(start_time) = ? " +
                     "GROUP BY app_name ORDER BY total_secs DESC";

        try (PreparedStatement ps = dbConn.prepareStatement(sql)) {
            ps.setString(1, date);
            ResultSet rs = ps.executeQuery();

            long grandTotal = 0;
            int rank = 1;

            System.out.printf("%-4s %-30s %12s %10s\n", "Rank", "Application", "Duration", "Sessions");
            System.out.println(String.join("", Collections.nCopies(60, "-")));

            while (rs.next()) {
                String app = rs.getString("app_name");
                if (shouldIgnore(app)) {

                    continue;
                }
                long secs = rs.getLong("total_secs");
                int sessions = rs.getInt("sessions");
                grandTotal += secs;

                System.out.printf("%-4d %-30s %12s %10d\n", rank++, app, formatDuration(secs), sessions);
            }

            System.out.println(String.join("", Collections.nCopies(60, "-")));
            System.out.printf("%-4s %-30s %12s\n", "", "TOTAL", formatDuration(grandTotal));

            if (rank == 1) {
                System.out.println("\nNo data for this date.");
            }

        } catch (SQLException e) {
            System.err.println("Report failed: " + e.getMessage());
        }

        System.out.println();
    }

    private static void generateAllTimeReport(String baseDir) {
        System.out.println("\n========== All-Time Statistics ==========\n");

        String sql = "SELECT app_name, SUM(duration_sec) as total_secs, COUNT(*) as sessions, " +
                     "MAX(date(start_time)) as last_used " +
                     "FROM usage_log GROUP BY app_name ORDER BY total_secs DESC LIMIT 20";

        try (Statement stmt = dbConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            long grandTotal = 0;
            int rank = 1;

            System.out.printf("%-4s %-25s %12s %8s %12s\n", "Rank", "Application", "Duration", "Sessions", "Last Used");
            System.out.println(String.join("", Collections.nCopies(70, "-")));

            while (rs.next()) {
                String app = rs.getString("app_name");
                if (shouldIgnore(app)) continue;
                long secs = rs.getLong("total_secs");
                int sessions = rs.getInt("sessions");
                String lastUsed = rs.getString("last_used");
                grandTotal += secs;

                System.out.printf("%-4d %-25s %12s %8d %12s\n", rank++, app, formatDuration(secs), sessions, lastUsed);
            }

            System.out.println(String.join("", Collections.nCopies(70, "-")));
            System.out.printf("%-4s %-25s %12s\n", "", "TOTAL", formatDuration(grandTotal));

            // Summary stats
            System.out.println("\n========== Summary ==========");
            try (ResultSet summary = stmt.executeQuery("SELECT COUNT(DISTINCT date(start_time)) as days, " +
                    "COUNT(*) as total_sessions, SUM(duration_sec) as total_time FROM usage_log")) {
                if (summary.next()) {
                    System.out.println("Active days:     " + summary.getInt("days"));
                    System.out.println("Total sessions:  " + summary.getInt("total_sessions"));
                    System.out.println("Total time:      " + formatDuration(summary.getLong("total_time")));
                }
            }

        } catch (SQLException e) {
            System.err.println("Report failed: " + e.getMessage());
        }

        System.out.println();
    }

    private static void exportReportMenu(String baseDir) {
        System.out.println();
        System.out.println("  [1] Export today's report");
        System.out.println("  [2] Export this week's report");
        System.out.println("  [3] Export this month's report");
        System.out.println("  [4] Export all-time report");
        System.out.println();
        System.out.print("Choose (1-4): ");

        Scanner scanner = new Scanner(System.in);
        String choice = scanner.nextLine().trim();
        String title;
        String dateCondition;

        switch (choice) {
            case "1":
                title = "Daily Report - " + LocalDate.now();
                dateCondition = "WHERE date(start_time) = '" + LocalDate.now().toString() + "'";
                break;
            case "2": {
                String[] range = getThisWeekRange();
                title = "Weekly Report - " + range[0] + " to " + range[1];
                dateCondition = "WHERE date(start_time) >= '" + range[0] + "' AND date(start_time) <= '" + range[1] + "'";
                break;
            }
            case "4": {
                title = "All-Time Report";
                dateCondition = "";
                break;
            }
            default:
            case "3": {
                String[] range = getThisMonthRange();
                title = "Monthly Report - " + range[0].substring(0, 7);
                dateCondition = "WHERE date(start_time) >= '" + range[0] + "' AND date(start_time) <= '" + range[1] + "'";
                break;
            }
        }
        exportReportToFile(baseDir, title, dateCondition);
    }

    private static String[] getThisWeekRange() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        LocalDate sunday = today.with(DayOfWeek.SUNDAY);
        return new String[]{monday.toString(), sunday.toString()};
    }

    private static String[] getThisMonthRange() {
        LocalDate today = LocalDate.now();
        LocalDate first = today.withDayOfMonth(1);
        LocalDate last = today.withDayOfMonth(today.lengthOfMonth());
        return new String[]{first.toString(), last.toString()};
    }

    private static void generateRangeReport(String baseDir, String[] range) {
        String label = range[0] + " to " + range[1];
        System.out.println("\n========== Report: " + label + " ==========\n");

        String sql = "SELECT app_name, SUM(duration_sec) as total_secs, COUNT(*) as sessions " +
                     "FROM usage_log WHERE date(start_time) >= ? AND date(start_time) <= ? " +
                     "GROUP BY app_name ORDER BY total_secs DESC";

        try (PreparedStatement ps = dbConn.prepareStatement(sql)) {
            ps.setString(1, range[0]);
            ps.setString(2, range[1]);
            ResultSet rs = ps.executeQuery();

            long grandTotal = 0;
            int rank = 1;

            System.out.printf("%-4s %-30s %12s %10s\n", "Rank", "Application", "Duration", "Sessions");
            System.out.println(String.join("", Collections.nCopies(60, "-")));

            while (rs.next()) {
                String app = rs.getString("app_name");
                boolean ignored = shouldIgnore(app);

                if (ignored) continue;  // skip ignored apps in report
                long secs = rs.getLong("total_secs");
                int sessions = rs.getInt("sessions");
                grandTotal += secs;

                System.out.printf("%-4d %-30s %12s %10d\n", rank++, app, formatDuration(secs), sessions);
            }

            System.out.println(String.join("", Collections.nCopies(60, "-")));
            System.out.printf("%-4s %-30s %12s\n", "", "TOTAL", formatDuration(grandTotal));

            if (rank == 1) {
                System.out.println("\nNo data for this period.");
            }

        } catch (SQLException e) {
            System.err.println("Report failed: " + e.getMessage());
        }

        System.out.println();
    }

    private static void exportReportToFile(String baseDir) {
        // kept for --report backward compat
        generateAllTimeReport(baseDir);
    }

    private static void exportReportToFile(String baseDir, String title, String dateCondition) {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "report_" + dateStr + ".txt";
        File reportFile = new File(baseDir, DATA_DIR + "/" + filename);

        try (PrintWriter writer = new PrintWriter(reportFile, "UTF-8")) {
            writer.println("========================================");
            writer.println("  " + title);
            writer.println("========================================");
            writer.println("  Generated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println();

            String sql = "SELECT app_name, SUM(duration_sec) as total_secs, COUNT(*) as sessions " +
                         "FROM usage_log " + dateCondition + " GROUP BY app_name ORDER BY total_secs DESC";

            try (Statement stmt = dbConn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                long grandTotal = 0;
                writer.printf("%-4s %-30s %12s %10s\n", "Rank", "Application", "Duration", "Sessions");
                writer.println(String.join("", Collections.nCopies(60, "-")));

                int rank = 1;
                while (rs.next()) {
                    String app = rs.getString("app_name");
                    if (shouldIgnore(app)) continue;  // skip ignored apps in report
                    long secs = rs.getLong("total_secs");
                    int sessions = rs.getInt("sessions");
                    grandTotal += secs;
                    writer.printf("%-4d %-30s %12s %10d\n", rank++, app, formatDuration(secs), sessions);
                }

                writer.println(String.join("", Collections.nCopies(60, "-")));
                writer.printf("%-4s %-30s %12s\n", "", "TOTAL", formatDuration(grandTotal));
            }

            System.out.println("\nReport exported to: " + reportFile.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("Export failed: " + e.getMessage());
        }
    }

    private static String getBaseDir() {
        String baseDir = System.getProperty("user.dir");
        File psFile = new File(baseDir, PS_SCRIPT);
        if (!psFile.exists()) {
            File parent = new File(baseDir).getParentFile();
            if (parent != null && new File(parent, PS_SCRIPT).exists()) {
                baseDir = parent.getAbsolutePath();
            }
        }
        return baseDir;
    }

    private static void initDb(String baseDir) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("SQLite JDBC driver not found: " + e.getMessage());
            System.exit(1);
        }
        try {
            new File(baseDir, DATA_DIR).mkdirs();
            String dbPath = new File(baseDir, DATA_DIR + "/" + DB_NAME).getAbsolutePath();
            String url = "jdbc:sqlite:" + dbPath;

            dbConn = DriverManager.getConnection(url);
            dbConn.setAutoCommit(true);

            try (Statement stmt = dbConn.createStatement()) {
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS usage_log (" +
                    "  id         INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  app_name   TEXT    NOT NULL," +
                    "  start_time TEXT    NOT NULL," +
                    "  end_time   TEXT    NOT NULL," +
                    "  duration_sec INTEGER NOT NULL" +
                    ")"
                );
            }

            currentDate = LocalDate.now().toString();
            System.out.println("DB: " + dbPath);
            System.out.println();

        } catch (SQLException e) {
            System.err.println("DB init failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void closeDb() {
        if (dbConn != null) {
            try { dbConn.close(); } catch (SQLException ignored) {}
        }
    }

    private static void trackForeground(String baseDir) {
        try {
            String appName = getForegroundProcessName(baseDir);

            if (appName != null && !shouldIgnore(appName)) {
                checkDateChange(baseDir);

                if (!appName.equals(currentApp)) {
                    String time = LocalTime.now().format(
                        DateTimeFormatter.ofPattern("HH:mm:ss"));

                    flushCurrentApp(baseDir);

                    currentApp = appName;
                    currentAppStart = Instant.now();
                    System.out.println("[" + time + "] " + appName);
                }
            } else if (currentAppStart != null) {
                // Foreground went to ignored app 鈥?treat as switching away
                flushCurrentApp(baseDir);
            }

        } catch (Exception e) {
            // silent
        }
    }

    /**
     * Called when switching away from the current app.
     * Inserts one record into SQLite.
     */
    private static void flushCurrentApp(String baseDir) {
        if (currentAppStart == null || currentApp.isEmpty()) return;

        Instant end = Instant.now();
        long secs = Duration.between(currentAppStart, end).getSeconds();
        if (secs < 1) secs = 1;  // at least 1 second

        try {
            String startStr = LocalDateTime.ofInstant(currentAppStart, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String endStr = LocalDateTime.ofInstant(end, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            try (PreparedStatement ps = dbConn.prepareStatement(
                    "INSERT INTO usage_log(app_name,start_time,end_time,duration_sec) VALUES(?,?,?,?)")) {
                ps.setString(1, currentApp);
                ps.setString(2, startStr);
                ps.setString(3, endStr);
                ps.setLong(4, secs);
                ps.executeUpdate();
            }

            System.out.println("  >> " + currentApp + " recorded: " + formatDuration(secs));

        } catch (SQLException e) {
            System.err.println("DB insert failed: " + e.getMessage());
        }

        currentApp = "";
        currentAppStart = null;
    }

    private static String getForegroundProcessName(String baseDir) {
        try {
            String scriptPath = new File(baseDir, PS_SCRIPT).getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", scriptPath
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();

            process.waitFor(10, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
            }

            return (line != null) ? line.trim() : null;

        } catch (Exception e) {
            return null;
        }
    }

    private static boolean shouldIgnore(String appName) {
        if (appName == null || appName.length() < 2) return true;

        // Filter out garbled/special-character titles (mostly symbols)
        int symbolCount = 0;
        for (char c : appName.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != ' ' && c != '-' && c != '_' && c != '.' && c != ':') {
                symbolCount++;
            }
        }
        if (symbolCount > appName.length() / 2) return true;

        String lower = appName.toLowerCase();
        switch (lower) {
            case "explorer":
            case "shellexperiencehost":
            case "searchapp":
            case "applicationframehost":
            case "startmenuexperiencehost":
            case "lockapp":
            case "system":
            case "textinputhost":
            case "windowsinternal":
            case "desktopwindowmanager":
            case "taskmgr":
            case "windowsterminal":
            case "windows terminal":
            case "wt":
            case "nexus":
            case "nexusclient":
            case "nexus_mod":
                return true;
        }

        // Ignore installers, temp files, setup programs
        if (lower.contains("setup") || lower.contains("install") || lower.contains(".tmp")
            || lower.contains(".exe_") || lower.contains("uninst")
            || lower.contains("wizard") || lower.contains("launcher")
            || lower.endsWith(".tmp") || lower.endsWith(".log")) {
            return true;
        }
        return false;
    }

    private static void checkDateChange(String baseDir) {
        String today = LocalDate.now().toString();
        if (!today.equals(currentDate)) {
            flushCurrentApp(baseDir);
            currentDate = today;
        }
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m " + secs + "s";
        } else if (minutes > 0) {
            return minutes + "m " + secs + "s";
        } else {
            return secs + "s";
        }
    }
}
