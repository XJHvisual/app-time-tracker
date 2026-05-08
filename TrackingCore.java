import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * Shared tracking engine v2.0
 * Added: single-instance lock, heartbeat, DB health check.
 */
public class TrackingCore {

    protected volatile boolean trackingActive = false;
    protected Thread trackingThread = null;

    protected static final int SCAN_INTERVAL_SECONDS = 2;
    protected static final String DATA_DIR = "data";
    protected static final String DB_NAME = "usagelog.db";
    protected static final String LOCK_FILE = "data/.tracker.lock";

    protected String currentDate = "";
    protected String currentApp = "";
    protected Instant currentAppStart = null;
    protected Connection dbConn = null;
    protected String baseDir;

    private RandomAccessFile lockRaf = null;
    private FileLock fileLock = null;
    private volatile Instant lastSuccessfulFlush = null;

    public TrackingCore(String baseDir) {
        this.baseDir = baseDir;
    }

    // === Single Instance Lock ===

    public boolean tryAcquireLock() {
        try {
            File lockFile = new File(baseDir, LOCK_FILE);
            lockFile.getParentFile().mkdirs();
            lockRaf = new RandomAccessFile(lockFile, "rw");
            fileLock = lockRaf.getChannel().tryLock();
            if (fileLock == null) {
                System.err.println("[Lock] Another instance is already running. Exiting.");
                lockRaf.close();
                return false;
            }
            lockRaf.writeBytes(String.valueOf(ProcessHandle.current().pid()));
            System.out.println("[Lock] Acquired (PID: " + ProcessHandle.current().pid() + ")");
            return true;
        } catch (Exception e) {
            System.err.println("[Lock] Failed: " + e.getMessage());
            return false;
        }
    }

    public void releaseLock() {
        try {
            if (fileLock != null) fileLock.release();
            if (lockRaf != null) lockRaf.close();
            File lockFile = new File(baseDir, LOCK_FILE);
            if (lockFile.exists()) lockFile.delete();
        } catch (Exception ignored) {}
    }

    public Instant getLastSuccessfulFlush() {
        return lastSuccessfulFlush;
    }

    // === DB ===

    public void initDb() {
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { System.err.println("SQLite driver missing"); System.exit(1); }
        try {
            new File(baseDir, DATA_DIR).mkdirs();
            String dbPath = new File(baseDir, DATA_DIR + "/" + DB_NAME).getAbsolutePath();
            dbConn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            dbConn.setAutoCommit(true);
            try (Statement s = dbConn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("CREATE TABLE IF NOT EXISTS usage_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, app_name TEXT NOT NULL, " +
                    "start_time TEXT NOT NULL, end_time TEXT NOT NULL, duration_sec INTEGER NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_usage_start ON usage_log(start_time)");
            }
            currentDate = LocalDate.now().toString();
            System.out.println("DB: " + dbPath);
        } catch (SQLException e) { System.err.println("DB init: " + e.getMessage()); System.exit(1); }
    }

    public void closeDb() {
        try { if (dbConn != null) dbConn.close(); } catch (SQLException ignored) {}
    }

    public Connection getConnection() { return dbConn; }

    public String getBaseDir() { return baseDir; }

    // === Tracking loop ===

    public void startTracking() {
        trackingActive = true;
        trackingThread = new Thread(() -> {
            while (trackingActive) {
                try {
                    trackForeground();
                    Thread.sleep(SCAN_INTERVAL_SECONDS * 1000);
                } catch (InterruptedException e) { break; }
            }
            flushCurrentApp();
        }, "TrackingThread");
        trackingThread.setDaemon(true);
        trackingThread.start();
    }

    public void stopTracking() {
        trackingActive = false;
        if (trackingThread != null && trackingThread.isAlive()) {
            try { trackingThread.join(3000); } catch (InterruptedException ignored) {}
        }
        flushCurrentApp();
    }

    public void waitForTracking() {
        try {
            if (trackingThread != null) trackingThread.join();
        } catch (InterruptedException ignored) {}
    }

    protected void trackForeground() {
        try {
            String[] info = ForegroundDetector.detect();
            if (info == null || info[0] == null || info[0].isEmpty()) {
                if (currentAppStart != null) flushCurrentApp();
                return;
            }
            String app = info[0];
            String path = info.length > 1 ? info[1] : "";
            if (shouldIgnore(app)) {
                if (currentAppStart != null) flushCurrentApp();
                return;
            }
            onAppDetected(app, path);
            checkDateChange();
            if (!app.equals(currentApp)) {
                flushCurrentApp();
                currentApp = app;
                currentAppStart = Instant.now();
                onAppChanged(app, path);
            }
        } catch (Exception ignored) {}
    }

    protected void onAppDetected(String appName, String exePath) {}
    protected void onAppChanged(String appName, String exePath) {}
    protected void onFlush(long durationSec) {}

    // === Recording ===

    protected void flushCurrentApp() {
        if (currentAppStart == null || currentApp.isEmpty()) return;
        Instant end = Instant.now();
        long secs = Math.max(1, Duration.between(currentAppStart, end).getSeconds());
        try {
            // DB health check
            if (dbConn == null || dbConn.isClosed()) {
                System.err.println("[DB] Connection lost, reconnecting...");
                initDb();
            }
            String s = LocalDateTime.ofInstant(currentAppStart, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String e = LocalDateTime.ofInstant(end, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            try (PreparedStatement ps = dbConn.prepareStatement(
                    "INSERT INTO usage_log(app_name,start_time,end_time,duration_sec) VALUES(?,?,?,?)")) {
                ps.setString(1, currentApp); ps.setString(2, s);
                ps.setString(3, e);          ps.setLong(4, secs);
                ps.executeUpdate();
            }
            lastSuccessfulFlush = Instant.now();
            onFlush(secs);
        } catch (SQLException ex) {
            System.err.println("[DB] Write failed: " + ex.getMessage());
        }
        currentApp = ""; currentAppStart = null;
    }

    protected void checkDateChange() {
        String today = LocalDate.now().toString();
        if (!today.equals(currentDate)) { flushCurrentApp(); currentDate = today; }
    }

    // === Helpers ===

    public static String formatDuration(long secs) {
        if (secs < 0) secs = 0;
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return s + "s";
    }

    protected boolean shouldIgnore(String name) {
        if (name == null || name.isEmpty()) return true;
        String lower = name.toLowerCase();
        switch (lower) {
            case "explorer": case "searchapp": case "lockapp":
            case "system": case "system idle process":
            case "applicationframehost": case "startmenuexperiencehost":
            case "shellexperiencehost": case "textinputhost":
            case "desktopwindowmanager": case "windowsinternal": case "idle":
            case "nexus":
                return true;
        }
        if (lower.contains("setup") || lower.contains("install") || lower.contains("uninst")
            || lower.contains("wizard") || lower.endsWith(".tmp") || lower.endsWith(".log"))
            return true;
        return false;
    }

    protected String getFriendlyName(String name) {
        if (name == null) return null;
        switch (name.toLowerCase()) {
            case "client-win64-shipping": case "krwebview": return "鸣潮";
            case "msedge": return "Microsoft Edge";
            case "chrome": return "Google Chrome";
            case "firefox": return "Firefox";
            case "wechat": case "weixin": return "微信";
            case "qq": return "QQ";
            case "dingtalk": return "钉钉";
            case "cloudmusic": return "网易云音乐";
            case "douyin": return "抖音";
            case "code": return "Visual Studio Code";
            case "notepad++": return "Notepad++";
            case "typora": return "Typora";
            case "cursor": return "Cursor";
            case "steamwebhelper": return "Steam";
            case "valorant-win64-shipping": return "VALORANT";
            case "cs2": return "Counter-Strike 2";
            case "wegame": return "WeGame";
            case "wps": case "wpspdf": return "WPS Office";
            case "mysqlworkbench": return "MySQL Workbench";
            case "qclaw": return "QClaw";
            default: return name;
        }
    }

    // === Base dir resolution ===

    public static String resolveBaseDir() {
        String d = System.getProperty("user.dir");
        if (new File(d, DATA_DIR).isDirectory()) return d;
        File p = new File(d).getParentFile();
        if (p != null && new File(p, DATA_DIR).isDirectory()) return p.getAbsolutePath();
        return d;
    }
}
