import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * AppTimeTracker GUI v5.0
 * Swing-based GUI showing app usage statistics with visual charts.
 * Auto-starts tracking on launch.
 * 
 * DB: data/usagelog.db
 */
public class AppTimeTrackerGUI {

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

    // GUI components
    private static JFrame frame;
    private static JTable table;
    private static DefaultTableModel tableModel;
    private static BarChartPanel chartPanel;
    private static JLabel statusLabel;
    private static String currentRange = "today";
    private static String baseDir;

    public static void main(String[] args) {
        baseDir = getBaseDir();
        initDb(baseDir);
        
        // Auto-start tracking
        startTrackingBackground(baseDir);
        System.out.println("[Auto] Tracking started on launch.");
        
        // Create GUI on Swing thread
        SwingUtilities.invokeLater(() -> createAndShowGUI());
    }

    private static void createAndShowGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        frame = new JFrame("App Time Tracker v5.0");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 650);
        frame.setMinimumSize(new Dimension(700, 500));
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopTracking(baseDir);
                closeDb();
                System.exit(0);
            }
        });

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top: Time range buttons
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        topPanel.add(new JLabel("\u65F6\u95F4\u8303\u56F4:"));

        JButton todayBtn = createRangeButton("\u4ECA\u5929", "today");
        JButton weekBtn = createRangeButton("\u672C\u5468", "week");
        JButton monthBtn = createRangeButton("\u672C\u6708", "month");
        JButton allBtn = createRangeButton("\u5168\u90E8", "all");

        topPanel.add(todayBtn);
        topPanel.add(weekBtn);
        topPanel.add(monthBtn);
        topPanel.add(allBtn);
        topPanel.add(Box.createHorizontalStrut(20));

        JButton refreshBtn = new JButton("\u5237\u65B0");
        refreshBtn.addActionListener(e -> refreshData());
        topPanel.add(refreshBtn);

        JButton exportBtn = new JButton("\u5BFC\u51FA");
        exportBtn.addActionListener(e -> exportReport());
        topPanel.add(exportBtn);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // Center: Table
        String[] columns = {"\u6392\u540D", "\u5E94\u7528", "\u65F6\u957F", "\u6B21\u6570"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        table = new JTable(tableModel);
        table.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
        table.setFillsViewportHeight(true);
        table.setRowHeight(25);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setMaxWidth(80);

        // Set column header font
        table.getTableHeader().setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));

        JScrollPane scrollPane = new JScrollPane(table);
        mainPanel.add(scrollPane, BorderLayout.CENTER);

        // Bottom: Chart + Status
        JPanel bottomPanel = new JPanel(new BorderLayout());

        chartPanel = new BarChartPanel();
        chartPanel.setPreferredSize(new Dimension(900, 150));
        bottomPanel.add(chartPanel, BorderLayout.CENTER);

        statusLabel = new JLabel("\u8DDF\u8E2A\u4E2D | \u6570\u636E\u66F4\u65B0\u4E8E: " + 
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        statusLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);

        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        frame.setContentPane(mainPanel);
        frame.setVisible(true);

        // Initial data load
        refreshData();

        // Auto-refresh timer (every 5 seconds)
        javax.swing.Timer timer = new javax.swing.Timer(5000, e -> refreshData());
        timer.start();
    }

    private static JButton createRangeButton(String text, String range) {
        JButton btn = new JButton(text);
        btn.addActionListener(e -> {
            currentRange = range;
            refreshData();
        });
        return btn;
    }

    private static void refreshData() {
        if (dbConn == null) return;

        try {
            String sql;
            PreparedStatement ps;
            String startDate, endDate;
            LocalDate today = LocalDate.now();

            switch (currentRange) {
                case "week":
                    LocalDate monday = today.with(DayOfWeek.MONDAY);
                    LocalDate sunday = today.with(DayOfWeek.SUNDAY);
                    startDate = monday.toString();
                    endDate = sunday.toString();
                    break;
                case "month":
                    LocalDate firstDay = today.withDayOfMonth(1);
                    LocalDate lastDay = today.withDayOfMonth(today.lengthOfMonth());
                    startDate = firstDay.toString();
                    endDate = lastDay.toString();
                    break;
                case "all":
                    startDate = "1970-01-01";
                    endDate = "2099-12-31";
                    break;
                default: // today
                    startDate = today.toString();
                    endDate = today.toString();
            }

            if (currentRange.equals("all")) {
                sql = "SELECT app_name, SUM(duration_sec) as total_secs, COUNT(*) as sessions " +
                      "FROM usage_log GROUP BY app_name ORDER BY total_secs DESC LIMIT 20";
                ps = dbConn.prepareStatement(sql);
            } else {
                sql = "SELECT app_name, SUM(duration_sec) as total_secs, COUNT(*) as sessions " +
                      "FROM usage_log WHERE date(start_time) >= ? AND date(start_time) <= ? " +
                      "GROUP BY app_name ORDER BY total_secs DESC LIMIT 20";
                ps = dbConn.prepareStatement(sql);
                ps.setString(1, startDate);
                ps.setString(2, endDate);
            }

            ResultSet rs = ps.executeQuery();

            // Update table
            tableModel.setRowCount(0);
            java.util.List<Object[]> chartData = new ArrayList<>();
            int rank = 1;
            long grandTotal = 0;

            while (rs.next()) {
                String app = rs.getString("app_name");
                if (shouldIgnore(app)) continue;

                long secs = rs.getLong("total_secs");
                int sessions = rs.getInt("sessions");
                grandTotal += secs;

                String displayName = getFriendlyName(app);
                tableModel.addRow(new Object[]{rank++, displayName, formatDuration(secs), sessions});
                chartData.add(new Object[]{displayName, secs});
            }

            rs.close();
            ps.close();

            // Update chart
            chartPanel.setData(chartData);

            // Update status
            String rangeText;
            switch (currentRange) {
                case "week": rangeText = "\u672C\u5468"; break;
                case "month": rangeText = "\u672C\u6708"; break;
                case "all": rangeText = "\u5168\u90E8"; break;
                default: rangeText = "\u4ECA\u5929";
            }
            statusLabel.setText("\u8DDF\u8E2A\u4E2D | " + rangeText + " | \u603B\u65F6\u957F: " + 
                formatDuration(grandTotal) + " | \u66F4\u65B0\u4E8E: " + 
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void exportReport() {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "report_" + dateStr + ".txt";
        File reportFile = new File(baseDir, DATA_DIR + "/" + filename);

        try (PrintWriter writer = new PrintWriter(reportFile, "UTF-8")) {
            String title;
            switch (currentRange) {
                case "week": title = "\u5468\u62A5\u544A"; break;
                case "month": title = "\u6708\u62A5\u544A"; break;
                case "all": title = "\u5168\u90E8\u62A5\u544A"; break;
                default: title = "\u65E5\u62A5\u544A";
            }

            writer.println("========================================");
            writer.println("  " + title);
            writer.println("========================================");
            writer.println(" \u751F\u6210\u65F6\u95F4: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            writer.println();

            writer.printf("%-4s %-30s %12s %10s\n", "\u6392\u540D", "\u5E94\u7528", "\u65F6\u957F", "\u6B21\u6570");
            writer.println(String.join("", Collections.nCopies(55, "-")));

            for (int i = 0; i < tableModel.getRowCount(); i++) {
                writer.printf("%-4d %-30s %12s %10d\n",
                    tableModel.getValueAt(i, 0),
                    tableModel.getValueAt(i, 1),
                    tableModel.getValueAt(i, 2),
                    tableModel.getValueAt(i, 3));
            }

            System.out.println("\u5BFC\u51FA\u6210\u529F: " + reportFile.getAbsolutePath());
            JOptionPane.showMessageDialog(frame, "\u5BFC\u51FA\u6210\u529F\uFF01\n" + reportFile.getAbsolutePath(), 
                "\u6210\u529F", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "\u5BFC\u51FA\u5931\u8D25: " + e.getMessage(), 
                "\u9519\u8BEF", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ========== Tracking Logic ==========

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
                    System.out.println("[" + time + "] " + appName);
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
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptPath);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            process.waitFor(10, TimeUnit.SECONDS);
            if (process.isAlive()) process.destroyForcibly();

            return (line != null) ? line.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void checkDateChange(String baseDir) {
        String today = LocalDate.now().toString();
        if (!today.equals(currentDate)) {
            flushCurrentApp(baseDir);
            currentDate = today;
        }
    }

    private static void initDb(String baseDir) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("SQLite JDBC driver not found");
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
            || lower.contains(".exe_") || lower.contains("uninst") || lower.contains("wizard")
            || lower.contains("launcher") || lower.endsWith(".tmp") || lower.endsWith(".log")) {
            return true;
        }
        return false;
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + secs + "s";
        else if (minutes > 0) return minutes + "m " + secs + "s";
        else return secs + "s";
    }

    // ========== Bar Chart Panel ==========

    static class BarChartPanel extends JPanel {
        private java.util.List<Object[]> data = new ArrayList<>();

        public void setData(java.util.List<Object[]> data) {
            this.data = data;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (data.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();
            int padding = 40;
            int barAreaWidth = width - padding * 2;
            int barAreaHeight = height - padding * 2;

            // Find max value
            long maxVal = 0;
            for (Object[] row : data) {
                long val = (Long) row[1];
                if (val > maxVal) maxVal = val;
            }
            if (maxVal == 0) maxVal = 1;

            // Draw bars (top 10)
            int barCount = Math.min(data.size(), 10);
            int barHeight = Math.min(25, (barAreaHeight - 20) / barCount);
            int gap = 5;
            int startY = padding + 10;

            Color[] colors = {
                new Color(66, 133, 244),
                new Color(234, 67, 53),
                new Color(251, 188, 5),
                new Color(52, 168, 83),
                new Color(160, 32, 240),
                new Color(255, 112, 67),
                new Color(0, 172, 193),
                new Color(156, 39, 176),
                new Color(121, 85, 72),
                new Color(96, 125, 139)
            };

            g2.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));

            for (int i = 0; i < barCount; i++) {
                Object[] row = data.get(i);
                String name = (String) row[0];
                long value = (Long) row[1];
                int barWidth = (int) ((double) value / maxVal * (barAreaWidth - 120));

                int y = startY + i * (barHeight + gap);
                Color barColor = colors[i % colors.length];

                // Draw bar
                g2.setColor(barColor);
                g2.fillRect(padding, y, barWidth, barHeight);

                // Draw name
                g2.setColor(Color.BLACK);
                String displayName = name.length() > 18 ? name.substring(0, 16) + "..." : name;
                g2.drawString(displayName, padding + barWidth + 5, y + barHeight - 2);

                // Draw value
                String valStr = formatDuration(value);
                g2.drawString(valStr, padding + barWidth + 5 + 150, y + barHeight - 2);
            }
        }
    }
}
