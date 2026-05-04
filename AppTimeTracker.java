import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Windows Foreground Window Time Tracker v5.0
 * Background tracker 鈥?auto-starts on launch, runs until Ctrl+C.
 * All stats viewing is now handled by the GUI version (AppTimeTrackerGUI).
 *
 * DB: data/usagelog.db (shared with GUI)
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

        // Auto-start tracking
        startTrackingBackground(baseDir);
        System.out.println("[Auto] Tracking started on launch.");
        System.out.println("Press Ctrl+C to stop.");
        System.out.println();

        // Register shutdown hook for clean exit on Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            stopTracking(baseDir);
            closeDb();
            System.out.println("Tracking stopped. Bye!");
        }));

        // Wait until tracking thread exits
        try {
            trackingThread.join();
        } catch (InterruptedException ignored) {}
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

    private static void trackForeground(String baseDir) {
        try {
            String appName = getForegroundProcessName(baseDir);

            if (appName != null && !shouldIgnore(appName)) {
                checkDateChange(baseDir);

                if (!appName.equals(currentApp)) {
                    String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                    flushCurrentApp(baseDir);
                    currentApp = appName;
                    currentAppStart = Instant.now();
                    System.out.println("[" + time + "] " + getFriendlyName(appName));
                }
            } else if (currentAppStart != null) {
                flushCurrentApp(baseDir);
            }
        } catch (Exception ignored) {}
    }

    private static void flushCurrentApp(String baseDir) {
        if (currentAppStart == null || currentApp.isEmpty()) return;

        Instant end = Instant.now();
        long secs = Duration.between(currentAppStart, end).getSeconds();
        if (secs < 1) secs = 1;

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

            System.out.println("  >> " + getFriendlyName(currentApp) + " recorded: " + formatDuration(secs));

        } catch (SQLException e) {
            System.err.println("DB insert failed: " + e.getMessage());
        }

        currentApp = "";
        currentAppStart = null;
    }

    private static void checkDateChange(String baseDir) {
        String today = LocalDate.now().toString();
        if (!today.equals(currentDate)) {
            flushCurrentApp(baseDir);
            currentDate = today;
        }
    }

    private static String getForegroundProcessName(String baseDir) {
        try {
            String scriptPath = new File(baseDir, PS_SCRIPT).getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptPath);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();

            process.waitFor(10, TimeUnit.SECONDS);
            if (process.isAlive()) process.destroyForcibly();

            if (line == null) return null;
            line = line.trim();
            int pipe = line.indexOf('|');
            return (pipe >= 0) ? line.substring(0, pipe) : line;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getFriendlyName(String appName) {
        if (appName == null) return null;
        String lower = appName.toLowerCase();
        if (lower.equals("client-win64-shipping") || lower.equals("krwebview")) {
            return "\u9E23\u6F6E";
        }
        return appName;
    }

    private static boolean shouldIgnore(String appName) {
        if (appName == null || appName.length() < 2) return true;

        int symbolCount = 0;
        for (char c : appName.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != ' ' && c != '-' && c != '_' && c != '.' && c != ':') {
                symbolCount++;
            }
        }
        if (symbolCount > appName.length() / 2) return true;

        String lower = appName.toLowerCase();
        switch (lower) {
            case "explorer": case "shellexperiencehost": case "searchapp":
            case "applicationframehost": case "startmenuexperiencehost": case "lockapp":
            case "system": case "textinputhost": case "windowsinternal":
            case "desktopwindowmanager": case "taskmgr": case "windowsterminal":
            case "windows terminal": case "wt": case "nexus": case "nexusclient":
            case "nexus_mod":
                return true;
        }
        if (lower.contains("setup") || lower.contains("install") || lower.contains(".tmp")
            || lower.contains(".exe_") || lower.contains("uninst")
            || lower.contains("wizard") || lower.contains("launcher")
            || lower.endsWith(".tmp") || lower.endsWith(".log")) {
            return true;
        }
        return false;
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
            dbConn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            dbConn.setAutoCommit(true);

            try (Statement stmt = dbConn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS usage_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, app_name TEXT NOT NULL, " +
                    "start_time TEXT NOT NULL, end_time TEXT NOT NULL, duration_sec INTEGER NOT NULL)");
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

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + secs + "s";
        else if (minutes > 0) return minutes + "m " + secs + "s";
        else return secs + "s";
    }
}
