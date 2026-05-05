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
 * AppTimeTracker GUI v6.1
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

    // GUI components
    private static JFrame frame;
    private static JTable table;
    private static DefaultTableModel tableModel;
    private static BarChartPanel chartPanel;
    private static JLabel statusLabel;
    private static String currentRange = "today";
    private static String baseDir;

    // Modern color palette
    private static final Color BG_COLOR = new Color(248, 249, 250);
    private static final Color HEADER_BG = new Color(44, 54, 71);
    private static final Color ROW_EVEN = new Color(255, 255, 255);
    private static final Color ROW_ODD = new Color(246, 248, 250);
    private static final Color ROW_SELECTED = new Color(235, 245, 255);
    private static final Color ACCENT = new Color(65, 140, 235);
    private static final Color ACCENT_HOVER = new Color(85, 155, 245);
    private static final Color TEXT_PRIMARY = new Color(33, 37, 41);
    private static final Color TEXT_SECONDARY = new Color(108, 117, 125);
    private static final Color TEXT_MUTED = new Color(169, 179, 193);

    public static void main(String[] args) {
        baseDir = getBaseDir();
        initDb(baseDir);

        // Auto-start tracking
        startTrackingBackground(baseDir);
        System.out.println("[Auto] Tracking started on launch.");

        // Create GUI on Swing thread
        SwingUtilities.invokeLater(() -> createAndShowGUI());
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  GUI Construction
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    private static void createAndShowGUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        frame = new JFrame("\u5E94\u7528\u65F6\u95F4\u8FFD\u8E2A v6.1");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 720);
        frame.setMinimumSize(new Dimension(800, 550));
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(BG_COLOR);

        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                stopTracking(baseDir);
                closeDb();
                System.exit(0);
            }
        });

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG_COLOR);
        root.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        root.add(createTopBar(), BorderLayout.NORTH);
        root.add(createContent(), BorderLayout.CENTER);
        root.add(createBottomBar(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setVisible(true);

        refreshData();

        javax.swing.Timer timer = new javax.swing.Timer(5000, e -> refreshData());
        timer.start();
    }

    private static JPanel createTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(HEADER_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        JLabel title = new JLabel("\u5E94\u7528\u65F6\u95F4\u8FFD\u8E2A");
        title.setFont(new Font("SansSerif", Font.BOLD, 18));
        title.setForeground(Color.WHITE);
        bar.add(title, BorderLayout.WEST);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        btns.setOpaque(false);

        String[][] ranges = {
            {"\u4ECA\u5929", "today"}, {"\u672C\u5468", "week"},
            {"\u672C\u6708", "month"}, {"\u5168\u90E8", "all"}
        };

        for (String[] r : ranges) {
            final String range = r[1];
            JButton b = new JButton(r[0]);
            b.setFont(new Font("SansSerif", Font.PLAIN, 13));
            b.setFocusPainted(false);
            b.setBorderPainted(false);
            b.setContentAreaFilled(false);
            b.setCursor(new Cursor(Cursor.HAND_CURSOR));
            b.setPreferredSize(new Dimension(64, 34));
            b.setForeground(TEXT_MUTED);

            b.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) {
                    if (!b.getForeground().equals(ACCENT))
                        b.setForeground(new Color(200, 210, 225));
                }
                public void mouseExited(MouseEvent e) {
                    if (!b.getForeground().equals(ACCENT))
                        b.setForeground(TEXT_MUTED);
                }
            });

            b.addActionListener(e -> {
                currentRange = range;
                Container p = b.getParent();
                for (Component c : p.getComponents()) {
                    if (c instanceof JButton) {
                        c.setForeground(TEXT_MUTED);
                    }
                }
                b.setForeground(ACCENT);
                refreshData();
            });

            btns.add(b);
        }

        // Default today active
        for (Component c : btns.getComponents()) {
            if (c instanceof JButton && ((JButton) c).getText().equals("\u4ECA\u5929")) {
                c.setForeground(ACCENT);
                break;
            }
        }

        bar.add(btns, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);

        JButton refreshBtn = new JButton("\u21BB \u5237\u65B0");
        styleFlatButton(refreshBtn);
        refreshBtn.addActionListener(e -> refreshData());
        actions.add(refreshBtn);

        JButton exportBtn = new JButton("\u2193 \u5BFC\u51FA");
        styleFlatButton(exportBtn);
        exportBtn.addActionListener(e -> exportReport());
        actions.add(exportBtn);

        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    private static void styleFlatButton(JButton btn) {
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setForeground(new Color(195, 205, 220));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { btn.setForeground(Color.WHITE); }
            public void mouseExited(MouseEvent e) { btn.setForeground(new Color(195, 205, 220)); }
        });
    }

    private static JPanel createContent() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));
        panel.setBackground(BG_COLOR);
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        // 鈹€鈹€ Table 鈹€鈹€
        String[] cols = {"\u56FE\u6807", "\u6392\u540D", "\u5E94\u7528", "\u65F6\u957F", "\u6B21\u6570"};
        tableModel = new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };

        table = new JTable(tableModel);
        table.setFont(new Font("SansSerif", Font.PLAIN, 14));
        table.setRowHeight(40);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionBackground(ROW_SELECTED);
        table.setSelectionForeground(TEXT_PRIMARY);
        table.setBackground(ROW_EVEN);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        // Column widths
        TableColumnModel cm = table.getColumnModel();
        cm.getColumn(0).setMinWidth(56);  cm.getColumn(0).setMaxWidth(56);
        cm.getColumn(1).setMinWidth(55);  cm.getColumn(1).setMaxWidth(55);
        cm.getColumn(2).setMinWidth(200);
        cm.getColumn(3).setMinWidth(120);
        cm.getColumn(4).setMinWidth(70);  cm.getColumn(4).setMaxWidth(80);

        // Renderers
        cm.getColumn(0).setCellRenderer(new AppIconRenderer());
        cm.getColumn(1).setCellRenderer(new CenterRenderer());
        cm.getColumn(4).setCellRenderer(new CenterRenderer());
        cm.getColumn(2).setCellRenderer(new NameRenderer());
        cm.getColumn(3).setCellRenderer(new DurationRenderer());

        // Alternating rows (fallback for any column we missed)
        table.setDefaultRenderer(Object.class, new AlternatingRenderer());

        // Header
        JTableHeader hdr = table.getTableHeader();
        hdr.setFont(new Font("SansSerif", Font.BOLD, 12));
        hdr.setBackground(HEADER_BG);
        hdr.setForeground(Color.WHITE);
        hdr.setPreferredSize(new Dimension(0, 36));
        hdr.setBorder(BorderFactory.createEmptyBorder());
        hdr.setDefaultRenderer(new HeaderRenderer());

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1,
            new Color(222, 228, 234)));
        scroll.getViewport().setBackground(Color.WHITE);
        scroll.setPreferredSize(new Dimension(0, 320));
        panel.add(scroll, BorderLayout.CENTER);

        // 鈹€鈹€ Chart 鈹€鈹€
        chartPanel = new BarChartPanel();
        chartPanel.setPreferredSize(new Dimension(0, 200));
        chartPanel.setBackground(Color.WHITE);
        chartPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 1, 1,
            new Color(222, 228, 234)));
        panel.add(chartPanel, BorderLayout.SOUTH);

        return panel;
    }

    private static JPanel createBottomBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(240, 242, 245));
        bar.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        statusLabel = new JLabel("\u8DDF\u8E2A\u4E2D");
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusLabel.setForeground(TEXT_SECONDARY);
        bar.add(statusLabel, BorderLayout.WEST);

        JLabel tip = new JLabel("\u6570\u636E\u6BCF 5 \u79D2\u81EA\u52A8\u5237\u65B0");
        tip.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tip.setForeground(TEXT_MUTED);
        bar.add(tip, BorderLayout.EAST);

        return bar;
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Icon Handling 鈥?uses ShellFolder reflection for real Windows icons
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    private static Icon extractRealIcon(File file) {
        // Method 1: ShellFolder (most reliable on Windows)
        try {
            Class<?> sfClass = Class.forName("sun.awt.shell.ShellFolder");
            java.lang.reflect.Method getSF = sfClass.getMethod("getShellFolder", File.class);
            Object sf = getSF.invoke(null, file);
            java.lang.reflect.Method getIcon = sfClass.getMethod("getIcon", boolean.class);
            Image img = (Image) getIcon.invoke(sf, true);
            if (img != null) {
                return new ImageIcon(img.getScaledInstance(38, 38, Image.SCALE_SMOOTH));
            }
        } catch (Exception ignored) {}

        // Method 2: FileSystemView (fallback)
        try {
            javax.swing.filechooser.FileSystemView fsv =
                javax.swing.filechooser.FileSystemView.getFileSystemView();
            Icon sys = fsv.getSystemIcon(file);
            if (sys != null) return scaleIcon(sys, 38, 38);
        } catch (Exception ignored) {}

        return null;
    }

    private static void cacheAppIcon(String appName, String exePath) {
        if (appName == null) return;

        Icon real = null;

        // Try the exe path from PowerShell
        if (exePath != null && !exePath.isEmpty()) {
            File exe = new File(exePath);
            if (exe.exists()) {
                real = extractRealIcon(exe);
                if (real != null) { iconCache.put(appName, real); return; }
            }
        }

        // Search common install paths
        String appLower = appName.toLowerCase();
        String[] bases = {
            System.getenv("LOCALAPPDATA"),
            System.getenv("PROGRAMFILES"),
            System.getenv("PROGRAMFILES(X86)"),
            System.getenv("SystemRoot") + "\\System32"
        };
        for (String base : bases) {
            if (base == null || base.isEmpty()) continue;

            // Walk one level down to find matching exe (many apps are in subdirs)
            File baseDir = new File(base);
            if (!baseDir.isDirectory()) continue;

            // First check direct match
            File direct = new File(base, appName + ".exe");
            if (direct.exists()) {
                real = extractRealIcon(direct);
                if (real != null) { iconCache.put(appName, real); return; }
            }

            // Then search one subdirectory deep for .exe matching app name
            File[] subs = baseDir.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (!sub.isDirectory()) continue;
                    String subName = sub.getName().toLowerCase();
                    if (subName.contains(appLower) || appLower.contains(subName)) {
                        File[] exes = sub.listFiles((d, n) ->
                            n.toLowerCase().endsWith(".exe"));
                        if (exes != null) {
                            for (File exe : exes) {
                                real = extractRealIcon(exe);
                                if (real != null) { iconCache.put(appName, real); return; }
                            }
                        }
                    }
                }
            }
        }
    }

    private static Icon scaleIcon(Icon icon, int w, int h) {
        if (icon instanceof ImageIcon) {
            Image img = ((ImageIcon) icon).getImage();
            return new ImageIcon(img.getScaledInstance(w, h, Image.SCALE_SMOOTH));
        }
        return icon;
    }

    /** Build a colored circle with first-letter initials as fallback icon. */
    private static Icon createCircleIcon(String name) {
        int sz = 32;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

            Color bg = Color.getHSBColor(
                Math.abs(name.hashCode()) % 360 / 360f, 0.55f, 0.80f);
            g2.setColor(bg);
            g2.fillOval(0, 0, sz, sz);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 13));
            String letter = name.substring(0, 1).toUpperCase();
            FontMetrics fm = g2.getFontMetrics();
            int tx = (sz - fm.stringWidth(letter)) / 2;
            int ty = (sz + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(letter, tx, ty);
        } finally {
            g2.dispose();
        }
        return new ImageIcon(img);
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Data Refresh
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    private static void refreshData() {
        if (dbConn == null) return;

        try {
            String sql;
            PreparedStatement ps;
            String startDate, endDate;
            LocalDate today = LocalDate.now();

            switch (currentRange) {
                case "week":
                    LocalDate mon = today.with(DayOfWeek.MONDAY);
                    LocalDate sun = today.with(DayOfWeek.SUNDAY);
                    startDate = mon.toString();
                    endDate = sun.toString();
                    break;
                case "month":
                    startDate = today.withDayOfMonth(1).toString();
                    endDate = today.withDayOfMonth(today.lengthOfMonth()).toString();
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
                sql = "SELECT app_name,SUM(duration_sec) AS total_secs," +
                      "COUNT(*) AS sessions FROM usage_log " +
                      "GROUP BY app_name ORDER BY total_secs DESC LIMIT 20";
                ps = dbConn.prepareStatement(sql);
            } else {
                sql = "SELECT app_name,SUM(duration_sec) AS total_secs," +
                      "COUNT(*) AS sessions FROM usage_log " +
                      "WHERE date(start_time)>=? AND date(start_time)<=? " +
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
                int sess = rs.getInt("sessions");
                grandTotal += secs;

                String disp = getFriendlyName(app);
                // Store display name in col 0 (used by AppIconRenderer)
                tableModel.addRow(new Object[]{
                    disp, rank++, disp, formatDuration(secs), sess
                });
                chartData.add(new Object[]{disp, secs});
            }
            rs.close();
            ps.close();

            chartPanel.setData(chartData);

            String rangeLabel;
            switch (currentRange) {
                case "week":  rangeLabel = "\u672C\u5468"; break;
                case "month": rangeLabel = "\u672C\u6708"; break;
                case "all":   rangeLabel = "\u5168\u90E8"; break;
                default:      rangeLabel = "\u4ECA\u5929";
            }

            statusLabel.setText(String.format(
                "\u2705 \u8DDF\u8E2A\u4E2D | %s | \u603B\u65F6\u957F %s | \u66F4\u65B0 %s",
                rangeLabel, formatDuration(grandTotal),
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            ));

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void exportReport() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File out = new File(baseDir, DATA_DIR + "/report_" + ts + ".txt");
        out.getParentFile().mkdirs();

        try (PrintWriter w = new PrintWriter(out, "UTF-8")) {
            String title;
            switch (currentRange) {
                case "week":  title = "\u5468\u62A5\u544A"; break;
                case "month": title = "\u6708\u62A5\u544A"; break;
                case "all":   title = "\u5168\u90E8\u62A5\u544A"; break;
                default:      title = "\u65E5\u62A5\u544A";
            }
            w.println("=".repeat(52));
            w.printf("  %s\n", title);
            w.println("=".repeat(52));
            w.printf(" \u751F\u6210: %s\n\n",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            w.printf("%-4s %-30s %12s %8s\n", "\u6392\u540D", "\u5E94\u7528", "\u65F6\u957F", "\u6B21\u6570");
            w.println("-".repeat(52));
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                w.printf("%-4d %-30s %12s %8d\n",
                    tableModel.getValueAt(i, 1),
                    tableModel.getValueAt(i, 2),
                    tableModel.getValueAt(i, 3),
                    tableModel.getValueAt(i, 4));
            }
            System.out.println("\u5BFC\u51FA: " + out);
            JOptionPane.showMessageDialog(frame,
                "\u5BFC\u51FA\u6210\u529F\n" + out.getAbsolutePath(),
                "\u5BFC\u51FA\u5B8C\u6210", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame,
                "\u5BFC\u51FA\u5931\u8D25: " + e.getMessage(),
                "\u9519\u8BEF", JOptionPane.ERROR_MESSAGE);
        }
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Tracking Logic
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    private static void startTrackingBackground(String baseDir) {
        trackingActive = true;
        trackingThread = new Thread(() -> {
            while (trackingActive) {
                try { trackForeground(baseDir);
                      Thread.sleep(SCAN_INTERVAL_SECONDS * 1000); }
                catch (InterruptedException e) { break; }
            }
            flushCurrentApp(baseDir);
        }, "Track");
        trackingThread.setDaemon(true);
        trackingThread.start();
    }

    private static void stopTracking(String baseDir) {
        trackingActive = false;
        if (trackingThread != null && trackingThread.isAlive()) {
            try { trackingThread.join(3000); }
            catch (InterruptedException ignored) {}
        }
        flushCurrentApp(baseDir);
    }

    private static void trackForeground(String baseDir) {
        try {
            String[] info = getForegroundProcessInfo(baseDir);
            if (info == null) return;

            String app = info[0];
            String path = info.length > 1 ? info[1] : "";
            String friendly = getFriendlyName(app);

            if (app == null || shouldIgnore(app)) {
                if (currentAppStart != null) flushCurrentApp(baseDir);
                return;
            }

            // Cache icon
            if (!iconCache.containsKey(friendly)) {
                cacheAppIcon(friendly, path);
            }

            checkDateChange(baseDir);

            if (!app.equals(currentApp)) {
                flushCurrentApp(baseDir);
                currentApp = app;
                currentAppStart = Instant.now();
                System.out.printf("[%s] %s\n",
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    friendly);
            }
        } catch (Exception ignored) {}
    }

    private static String[] getForegroundProcessInfo(String baseDir) {
        try {
            String script = new File(baseDir, PS_SCRIPT).getAbsolutePath();
            Process p = new ProcessBuilder(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script)
                .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine(); r.close();
            p.waitFor(10, TimeUnit.SECONDS);
            if (p.isAlive()) p.destroyForcibly();
            if (line == null) return null;
            line = line.trim();
            int idx = line.indexOf('|');
            if (idx >= 0) return new String[]{line.substring(0, idx), line.substring(idx + 1)};
            return new String[]{line, ""};
        } catch (Exception e) { return null; }
    }

    private static void flushCurrentApp(String baseDir) {
        if (currentAppStart == null || currentApp.isEmpty()) return;
        Instant end = Instant.now();
        long secs = Math.max(1, Duration.between(currentAppStart, end).getSeconds());
        try {
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
        } catch (SQLException ex) { System.err.println("DB: " + ex.getMessage()); }
        currentApp = ""; currentAppStart = null;
    }

    private static void checkDateChange(String baseDir) {
        String today = LocalDate.now().toString();
        if (!today.equals(currentDate)) { flushCurrentApp(baseDir); currentDate = today; }
    }

    private static void initDb(String baseDir) {
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { System.err.println("SQLite driver missing"); System.exit(1); }
        try {
            new File(baseDir, DATA_DIR).mkdirs();
            String dbPath = new File(baseDir, DATA_DIR + "/" + DB_NAME).getAbsolutePath();
            dbConn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            dbConn.setAutoCommit(true);
            try (Statement s = dbConn.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS usage_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, app_name TEXT NOT NULL, " +
                    "start_time TEXT NOT NULL, end_time TEXT NOT NULL, duration_sec INTEGER NOT NULL)");
            }
            currentDate = LocalDate.now().toString();
            System.out.println("DB: " + dbPath);
        } catch (SQLException e) { System.err.println("DB init: " + e.getMessage()); System.exit(1); }
    }

    private static void closeDb() {
        try { if (dbConn != null) dbConn.close(); } catch (SQLException ignored) {}
    }

    private static String getBaseDir() {
        String d = System.getProperty("user.dir");
        File f = new File(d, PS_SCRIPT);
        if (!f.exists()) {
            File p = new File(d).getParentFile();
            if (p != null && new File(p, PS_SCRIPT).exists()) d = p.getAbsolutePath();
        }
        return d;
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Helpers
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    private static String getFriendlyName(String name) {
        if (name == null) return null;
        switch (name.toLowerCase()) {
            case "client-win64-shipping": case "krwebview": return "\u9E23\u6F6E";
            case "msedge": return "Microsoft Edge";
            case "qclaw":  return "QClaw";
            case "chrome": return "Google Chrome";
            default: return name;
        }
    }

    private static boolean shouldIgnore(String name) {
        if (name == null || name.isEmpty()) return true;
        String lower = name.toLowerCase();
        switch (lower) {
            case "explorer": case "searchapp": case "lockapp":
            case "system": case "system idle process":
            case "applicationframehost": case "startmenuexperiencehost":
            case "shellexperiencehost": case "textinputhost":
            case "desktopwindowmanager": case "windowsinternal": case "idle":
            case "windowsterminal": case "wt":
                return true;
        }
        if (lower.contains("setup") || lower.contains("install") || lower.contains("uninst")
            || lower.contains("wizard") || lower.endsWith(".tmp") || lower.endsWith(".log"))
            return true;
        return false;
    }

    private static String formatDuration(long secs) {
        if (secs < 0) secs = 0;
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return s + "s";
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Custom Table Renderers
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    /** Col 0: App icon 鈥?shows real system icon or colored-circle fallback. */
    static class AppIconRenderer extends DefaultTableCellRenderer {
        AppIconRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setVerticalAlignment(SwingConstants.CENTER);
        }

        public Component getTableCellRendererComponent(JTable t, Object val,
                boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, "", sel, focus, row, col);
            setText(null);

            String name = val != null ? val.toString() : "";
            if (!name.isEmpty()) {
                Icon cached = iconCache.get(name);
                if (cached != null) {
                    setIcon(cached);
                } else {
                    setIcon(createCircleIcon(name));
                }
            } else {
                setIcon(null);
            }

            if (!sel) setBackground(row % 2 == 0 ? ROW_EVEN : ROW_ODD);
            setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
            return this;
        }
    }

    /** Center-aligned text (rank, count). */
    static class CenterRenderer extends DefaultTableCellRenderer {
        CenterRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(new Font("SansSerif", Font.PLAIN, 14));
        }
        public Component getTableCellRendererComponent(JTable t, Object val,
                boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, val, sel, focus, row, col);
            if (!sel) {
                setBackground(row % 2 == 0 ? ROW_EVEN : ROW_ODD);
            }
            setForeground(sel ? TEXT_PRIMARY : TEXT_SECONDARY);
            return this;
        }
    }

    /** App name with bold + emoji indicator. */
    static class NameRenderer extends DefaultTableCellRenderer {
        NameRenderer() {
            setFont(new Font("SansSerif", Font.BOLD, 14));
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        }
        public Component getTableCellRendererComponent(JTable t, Object val,
                boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, val, sel, focus, row, col);
            if (!sel) {
                setBackground(row % 2 == 0 ? ROW_EVEN : ROW_ODD);
                setForeground(TEXT_PRIMARY);
            }
            return this;
        }
    }

    /** Duration with monospace feel. */
    static class DurationRenderer extends DefaultTableCellRenderer {
        DurationRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
            setFont(new Font("SansSerif", Font.PLAIN, 14));
            setForeground(TEXT_SECONDARY);
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 16));
        }
        public Component getTableCellRendererComponent(JTable t, Object val,
                boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, val, sel, focus, row, col);
            if (!sel) {
                setBackground(row % 2 == 0 ? ROW_EVEN : ROW_ODD);
            }
            return this;
        }
    }

    /** Alternating row colors as catch-all. */
    static class AlternatingRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object val,
                boolean sel, boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, val, sel, focus, row, col);
            if (!sel) setBackground(row % 2 == 0 ? ROW_EVEN : ROW_ODD);
            return this;
        }
    }

    /** Header renderer. */
    static class HeaderRenderer extends DefaultTableCellRenderer {
        HeaderRenderer() {
            setBackground(HEADER_BG);
            setForeground(Color.WHITE);
            setFont(new Font("SansSerif", Font.BOLD, 12));
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        }
    }

    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲
    //  Bar Chart Panel
    // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲

    static class BarChartPanel extends JPanel {
        private java.util.List<Object[]> data = new ArrayList<>();

        BarChartPanel() { setOpaque(true); }

        void setData(java.util.List<Object[]> d) { this.data = d; repaint(); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (data.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth(), h = getHeight();
                int pad = 60;
                int usableH = h - pad * 2;

                long maxVal = 1;
                for (Object[] row : data) {
                    long v = (Long) row[1];
                    if (v > maxVal) maxVal = v;
                }

                Color[] palette = {
                    new Color(65, 140, 235), new Color(235, 85, 75),
                    new Color(250, 180, 50), new Color(60, 185, 95),
                    new Color(160, 80, 240), new Color(255, 130, 70),
                    new Color(20, 180, 200), new Color(150, 100, 220),
                    new Color(130, 90, 80), new Color(100, 130, 150),
                };

                int n = Math.min(data.size(), 8);
                int barH = Math.min(32, (usableH - (n - 1) * 10) / n);
                int gap = 10;
                int startY = pad + 10;

                for (int i = 0; i < n; i++) {
                    Object[] row = data.get(i);
                    String name = (String) row[0];
                    long val = (Long) row[1];
                    int barW = (int) ((double) val / maxVal * (w - pad * 2 - 220));
                    barW = Math.max(barW, 8);

                    int y = startY + i * (barH + gap);
                    Color c = palette[i % palette.length];

                    // Gradient bar
                    GradientPaint grad = new GradientPaint(
                        pad, y, new Color(c.getRed(), c.getGreen(), c.getBlue(), 220),
                        pad + 8, y, c);
                    g2.setPaint(grad);
                    g2.fillRoundRect(pad, y, barW, barH, 8, 8);
                    g2.setColor(c);
                    g2.drawRoundRect(pad, y, barW, barH, 8, 8);

                    // Label
                    g2.setColor(TEXT_PRIMARY);
                    g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
                    String label = name.length() > 16 ? name.substring(0, 14) + ".." : name;
                    g2.drawString(label, pad + barW + 14, y + barH - 10);

                    // Value
                    g2.setColor(TEXT_SECONDARY);
                    g2.drawString(formatDuration(val),
                        pad + barW + 14 + 180, y + barH - 10);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
