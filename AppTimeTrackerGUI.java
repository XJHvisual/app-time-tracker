import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * AppTimeTracker GUI v6.0
 * Modern Swing GUI with app icons, visual charts, and auto-refresh.
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

    // Icon cache: app_name -> Icon
    private static final Map<String, Icon> iconCache = new HashMap<>();
    private static final int ICON_SIZE = 20;

    // GUI components
    private static JFrame frame;
    private static JTable table;
    private static DefaultTableModel tableModel;
    private static BarChartPanel chartPanel;
    private static JLabel statusLabel;
    private static String currentRange = "today";
    private static String baseDir;

    // Modern color palette
    private static final Color BG_COLOR = new Color(250, 251, 252);
    private static final Color HEADER_BG = new Color(45, 55, 72);
    private static final Color HEADER_TEXT = Color.WHITE;
    private static final Color ROW_EVEN = new Color(255, 255, 255);
    private static final Color ROW_ODD = new Color(248, 250, 252);
    private static final Color ROW_SELECTED = new Color(214, 230, 254);
    private static final Color BORDER_COLOR = new Color(226, 232, 240);
    private static final Color ACCENT_COLOR = new Color(66, 153, 225);
    private static final Color TEXT_PRIMARY = new Color(45, 55, 72);
    private static final Color TEXT_SECONDARY = new Color(113, 128, 150);

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

        frame = new JFrame("\u5E94\u7528\u65F6\u95F4\u8FFD\u8E2A v6.0");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 700);
        frame.setMinimumSize(new Dimension(750, 550));
        frame.setLocationRelativeTo(null);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopTracking(baseDir);
                closeDb();
                System.exit(0);
            }
        });

        // Main panel with modern background
        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(BG_COLOR);

        // Top toolbar
        JPanel topPanel = createTopPanel();
        mainPanel.add(topPanel, BorderLayout.NORTH);

        // Center: Table with icon column
        JPanel centerPanel = createTablePanel();
        mainPanel.add(centerPanel, BorderLayout.CENTER);

        // Bottom: Chart + Status
        JPanel bottomPanel = createBottomPanel();
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        frame.setContentPane(mainPanel);
        frame.setVisible(true);

        // Initial data load
        refreshData();

        // Auto-refresh timer (every 5 seconds)
        javax.swing.Timer timer = new javax.swing.Timer(5000, e -> refreshData());
        timer.start();
    }

    private static JPanel createTopPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 10));
        panel.setBackground(HEADER_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(5, 15, 5, 15));
        panel.setPreferredSize(new Dimension(0, 55));

        JLabel titleLabel = new JLabel("\u5E94\u7528\u65F6\u95F4\u8FFD\u8E2A");
        titleLabel.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 16));
        titleLabel.setForeground(HEADER_TEXT);
        panel.add(titleLabel);

        panel.add(Box.createHorizontalStrut(30));

        String[][] buttons = {
            {"\u4ECA\u5929", "today"}, {"\u672C\u5468", "week"},
            {"\u672C\u6708", "month"}, {"\u5168\u90E8", "all"}
        };

        for (String[] btn : buttons) {
            JButton button = createStyledButton(btn[0], btn[1].equals(currentRange));
            final String range = btn[1];
            button.addActionListener(e -> {
                currentRange = range;
                // Update button states
                for (Component c : panel.getComponents()) {
                    if (c instanceof JButton) {
                        c.setBackground(HEADER_BG);
                        c.setForeground(new Color(180, 190, 210));
                    }
                }
                button.setBackground(ACCENT_COLOR);
                button.setForeground(Color.WHITE);
                refreshData();
            });
            panel.add(button);
            panel.add(Box.createHorizontalStrut(5));
        }

        panel.add(Box.createHorizontalStrut(20));

        JButton refreshBtn = createIconButton("\u5237\u65B0", "\u21BB");
        refreshBtn.addActionListener(e -> refreshData());
        panel.add(refreshBtn);

        JButton exportBtn = createIconButton("\u5BFC\u51FA", "\u2193");
        exportBtn.addActionListener(e -> exportReport());
        panel.add(exportBtn);

        return panel;
    }

    private static JButton createStyledButton(String text, boolean active) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(true);
        btn.setOpaque(true);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(60, 32));

        if (active) {
            btn.setBackground(ACCENT_COLOR);
            btn.setForeground(Color.WHITE);
        } else {
            btn.setBackground(HEADER_BG);
            btn.setForeground(new Color(180, 190, 210));
        }

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!btn.getBackground().equals(ACCENT_COLOR)) {
                    btn.setBackground(new Color(60, 70, 90));
                }
            }
            @Override
            public void mouseExited(MouseEvent e) {
                if (!btn.getBackground().equals(ACCENT_COLOR)) {
                    btn.setBackground(HEADER_BG);
                }
            }
        });

        return btn;
    }

    private static JButton createIconButton(String tooltip, String icon) {
        JButton btn = new JButton(icon);
        btn.setToolTipText(tooltip);
        btn.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 16));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setForeground(new Color(180, 190, 210));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(36, 32));

        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                btn.setForeground(Color.WHITE);
            }
            @Override
            public void mouseExited(MouseEvent e) {
                btn.setForeground(new Color(180, 190, 210));
            }
        });

        return btn;
    }

    private static JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_COLOR);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        // Table with icon column
        String[] columns = {"", "\u6392\u540D", "\u5E94\u7528", "\u65F6\u957F", "\u6B21\u6570"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
            @Override
            public Class<?> getColumnClass(int col) {
                return col == 0 ? Icon.class : Object.class;
            }
        };

        table = new JTable(tableModel);
        table.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        table.setFillsViewportHeight(true);
        table.setRowHeight(36);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionBackground(ROW_SELECTED);
        table.setSelectionForeground(TEXT_PRIMARY);
        table.setBackground(ROW_EVEN);

        // Column widths
        TableColumnModel cm = table.getColumnModel();
        cm.getColumn(0).setMaxWidth(40);
        cm.getColumn(0).setMinWidth(40);
        cm.getColumn(1).setMaxWidth(50);
        cm.getColumn(1).setMinWidth(50);
        cm.getColumn(2).setPreferredWidth(280);
        cm.getColumn(3).setPreferredWidth(120);
        cm.getColumn(3).setMaxWidth(150);
        cm.getColumn(4).setMaxWidth(80);
        cm.getColumn(4).setMinWidth(60);

        // Icon renderer
        cm.getColumn(0).setCellRenderer(new IconTableCellRenderer());

        // Center-align rank and count
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        centerRenderer.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        cm.getColumn(1).setCellRenderer(centerRenderer);
        cm.getColumn(4).setCellRenderer(centerRenderer);

        // Duration renderer
        DefaultTableCellRenderer durationRenderer = new DefaultTableCellRenderer();
        durationRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        durationRenderer.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        durationRenderer.setForeground(TEXT_SECONDARY);
        cm.getColumn(3).setCellRenderer(durationRenderer);

        // App name renderer with padding
        DefaultTableCellRenderer nameRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val,
                    boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
                setFont(new Font("Microsoft YaHei UI", Font.BOLD, 13));
                if (!sel) setForeground(TEXT_PRIMARY);
                return this;
            }
        };
        cm.getColumn(2).setCellRenderer(nameRenderer);

        // Header styling
        JTableHeader header = table.getTableHeader();
        header.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
        header.setBackground(HEADER_BG);
        header.setForeground(HEADER_TEXT);
        header.setPreferredSize(new Dimension(0, 36));
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val,
                    boolean sel, boolean focus, int row, int col) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                lbl.setBackground(HEADER_BG);
                lbl.setForeground(HEADER_TEXT);
                lbl.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
                lbl.setHorizontalAlignment(SwingConstants.CENTER);
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
                return lbl;
            }
        });

        // Alternating row colors
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val,
                    boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                if (!sel) {
                    setBackground(row % 2 == 0 ? ROW_EVEN : ROW_ODD);
                }
                return this;
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR));
        scrollPane.getViewport().setBackground(ROW_EVEN);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private static JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(BG_COLOR);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 15, 10, 15));

        chartPanel = new BarChartPanel();
        chartPanel.setPreferredSize(new Dimension(0, 180));
        chartPanel.setBackground(BG_COLOR);
        panel.add(chartPanel, BorderLayout.CENTER);

        statusLabel = new JLabel("\u8DDF\u8E2A\u4E2D | \u6570\u636E\u66F4\u65B0\u4E8E: " +
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        statusLabel.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
        statusLabel.setForeground(TEXT_SECONDARY);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 5, 5, 5));
        panel.add(statusLabel, BorderLayout.SOUTH);

        return panel;
    }

    // ========== Icon Handling ==========

    private static Icon getAppIcon(String appName, String exePath) {
        if (appName == null) return null;

        // Check cache
        Icon cached = iconCache.get(appName);
        if (cached != null) return cached;

        Icon icon = null;

        // Try to get icon from exe path
        if (exePath != null && !exePath.isEmpty()) {
            File exeFile = new File(exePath);
            if (exeFile.exists()) {
                try {
                    javax.swing.filechooser.FileSystemView fsv =
                        javax.swing.filechooser.FileSystemView.getFileSystemView();
                    Icon sysIcon = fsv.getSystemIcon(exeFile);
                    if (sysIcon != null) {
                        icon = scaleIcon(sysIcon, ICON_SIZE, ICON_SIZE);
                    }
                } catch (Exception ignored) {}
            }
        }

        // Fallback: try to find exe by process name
        if (icon == null) {
            icon = findIconByProcessName(appName);
        }

        // Default icon
        if (icon == null) {
            icon = createDefaultIcon(appName);
        }

        iconCache.put(appName, icon);
        return icon;
    }

    private static Icon findIconByProcessName(String appName) {
        // Try common paths
        String[] searchPaths = {
            System.getenv("LOCALAPPDATA") + "\\",
            System.getenv("PROGRAMFILES") + "\\",
            System.getenv("PROGRAMFILES(X86)") + "\\",
            System.getenv("SystemRoot") + "\\System32\\",
        };

        String lower = appName.toLowerCase();
        String[] possibleNames = {lower + ".exe", appName + ".exe"};

        for (String base : searchPaths) {
            if (base == null || base.equals("null")) continue;
            for (String name : possibleNames) {
                File f = new File(base, name);
                if (f.exists()) {
                    try {
                        javax.swing.filechooser.FileSystemView fsv =
                            javax.swing.filechooser.FileSystemView.getFileSystemView();
                        return scaleIcon(fsv.getSystemIcon(f), ICON_SIZE, ICON_SIZE);
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private static Icon createDefaultIcon(String appName) {
        // Create a simple colored circle with first letter
        BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Generate color from app name hash
        int hue = Math.abs(appName.hashCode()) % 360;
        Color bg = Color.getHSBColor(hue / 360f, 0.6f, 0.85f);
        g2.setColor(bg);
        g2.fillOval(1, 1, ICON_SIZE - 2, ICON_SIZE - 2);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 11));
        String letter = appName.substring(0, 1).toUpperCase();
        FontMetrics fm = g2.getFontMetrics();
        int x = (ICON_SIZE - fm.stringWidth(letter)) / 2;
        int y = (ICON_SIZE + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(letter, x, y);
        g2.dispose();

        return new ImageIcon(img);
    }

    private static Icon scaleIcon(Icon icon, int w, int h) {
        if (icon instanceof ImageIcon) {
            Image img = ((ImageIcon) icon).getImage();
            Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        }
        return icon;
    }

    // ========== Data Refresh ==========

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
                default:
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
                Icon icon = iconCache.get(app);
                if (icon == null) icon = createDefaultIcon(displayName);

                tableModel.addRow(new Object[]{icon, rank++, displayName, formatDuration(secs), sessions});
                chartData.add(new Object[]{displayName, secs});
            }

            rs.close();
            ps.close();

            chartPanel.setData(chartData);

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
                    tableModel.getValueAt(i, 1),
                    tableModel.getValueAt(i, 2),
                    tableModel.getValueAt(i, 3),
                    tableModel.getValueAt(i, 4));
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
            String[] result = getForegroundProcessInfo(baseDir);
            if (result == null) return;

            String appName = result[0];
            String exePath = result.length > 1 ? result[1] : "";

            if (appName != null && !shouldIgnore(appName)) {
                // Cache icon from path
                if (!exePath.isEmpty() && !iconCache.containsKey(appName)) {
                    getAppIcon(appName, exePath);
                }

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

    private static String[] getForegroundProcessInfo(String baseDir) {
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
            if (pipe >= 0) {
                return new String[]{line.substring(0, pipe), line.substring(pipe + 1)};
            }
            return new String[]{line, ""};
        } catch (Exception e) {
            return null;
        }
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

    // ========== Custom Renderers ==========

    static class IconTableCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = new JLabel();
            label.setOpaque(true);
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setVerticalAlignment(SwingConstants.CENTER);

            if (value instanceof Icon) {
                label.setIcon((Icon) value);
            }

            if (isSelected) {
                label.setBackground(ROW_SELECTED);
            } else {
                label.setBackground(row % 2 == 0 ? ROW_EVEN : ROW_ODD);
            }

            return label;
        }
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
            int padding = 50;
            int barAreaWidth = width - padding * 2;
            int barAreaHeight = height - padding * 2;

            long maxVal = 0;
            for (Object[] row : data) {
                long val = (Long) row[1];
                if (val > maxVal) maxVal = val;
            }
            if (maxVal == 0) maxVal = 1;

            int barCount = Math.min(data.size(), 10);
            int barHeight = Math.min(28, (barAreaHeight - 20) / barCount);
            int gap = 6;
            int startY = padding + 10;

            Color[] colors = {
                new Color(66, 133, 244), new Color(234, 67, 53),
                new Color(251, 188, 5), new Color(52, 168, 83),
                new Color(160, 32, 240), new Color(255, 112, 67),
                new Color(0, 172, 193), new Color(156, 39, 176),
                new Color(121, 85, 72), new Color(96, 125, 139)
            };

            g2.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));

            for (int i = 0; i < barCount; i++) {
                Object[] row = data.get(i);
                String name = (String) row[0];
                long value = (Long) row[1];
                int barWidth = (int) ((double) value / maxVal * (barAreaWidth - 200));

                int y = startY + i * (barHeight + gap);
                Color barColor = colors[i % colors.length];

                // Draw rounded bar with gradient
                GradientPaint gradient = new GradientPaint(
                    padding, y, barColor.brighter(),
                    padding + barWidth, y + barHeight, barColor);
                g2.setPaint(gradient);
                g2.fillRoundRect(padding, y, barWidth, barHeight, 6, 6);

                // Draw border
                g2.setColor(barColor.darker());
                g2.drawRoundRect(padding, y, barWidth, barHeight, 6, 6);

                // Draw name
                g2.setColor(TEXT_PRIMARY);
                String displayName = name.length() > 20 ? name.substring(0, 18) + "..." : name;
                g2.drawString(displayName, padding + barWidth + 10, y + barHeight - 7);

                // Draw value
                g2.setColor(TEXT_SECONDARY);
                String valStr = formatDuration(value);
                g2.drawString(valStr, padding + barWidth + 10 + 160, y + barHeight - 7);
            }
        }
    }
}
