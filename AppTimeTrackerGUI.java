import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
/**
 * AppTimeTracker GUI v7.0
 * Modern Swing GUI with app icons, visual charts, and auto-refresh.
 * Auto-starts tracking on launch.
 *
 * DB: data/usagelog.db
 */
public class AppTimeTrackerGUI {

    // Tracking engine (replaces all tracking/DB state)
    private static TrackingCore engine;

    // Icon cache: app_name -> Icon
    private static final Map<String, Icon> iconCache = new HashMap<>();

    // Thread pool for icon background loading
    private static final ExecutorService iconLoader = Executors.newFixedThreadPool(2);

    // GUI components
    private static JFrame frame;
    private static JTable table;
    private static DefaultTableModel tableModel;
    private static BarChartPanel chartPanel;
    private static JLabel statusLabel;
    private static String currentRange = "today";

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
        // GUI-only mode: just view data, don't start tracking
        String baseDir = TrackingCore.resolveBaseDir();
        engine = new TrackingCore(baseDir) {
            @Override
            protected boolean shouldIgnore(String name) {
                if (name == null || name.isEmpty()) return true;
                String lower = name.toLowerCase();
                switch (lower) {
                    case "explorer": case "searchapp": case "lockapp":
                    case "system": case "system idle process":
                    case "applicationframehost": case "startmenuexperiencehost":
                    case "shellexperiencehost": case "textinputhost":
                    case "desktopwindowmanager": case "windowsinternal": case "idle":
                    case "nexus": case "nexusclient": case "nexus_mod":
                    case "windowsterminal": case "wt":
                        return true;
                }
                if (lower.contains("setup") || lower.contains("install") || lower.contains("uninst")
                    || lower.contains("wizard") || lower.endsWith(".tmp") || lower.endsWith(".log"))
                    return true;
                return false;
            }

            @Override
            protected String getFriendlyName(String name) {
                if (name == null) return null;
                switch (name.toLowerCase()) {
                    case "client-win64-shipping": case "krwebview": return "鸣潮";
                    case "msedge": return "Microsoft Edge";
                    case "qclaw":  return "QClaw";
                    case "chrome": return "Google Chrome";
                    default: return name;
                }
            }

            @Override
            protected void onAppDetected(String appName, String exePath) {
                String friendly = getFriendlyName(appName);
                if (!iconCache.containsKey(friendly)) {
                    cacheAppIcon(friendly, exePath);
                }
            }

            @Override
            protected void onAppChanged(String appName, String exePath) {
                // GUI mode: no tracking, just refresh data
                SwingUtilities.invokeLater(() -> refreshData());
            }
        };

        // 只初始化DB，不启动追踪（只读模式）
        engine.initDb();
        System.out.println("[GUI] Viewer mode - tracking runs separately");
        System.out.println("[GUI] Run AppTimeTracker.exe to start background tracking");

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
                // GUI mode doesn't own tracking — just close
                engine.closeDb();
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

        javax.swing.Timer timer = new javax.swing.Timer(10000, e -> refreshData());
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
        chartPanel.setPreferredSize(new Dimension(0, 280));
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

        statusLabel = new JLabel("查看模式");
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
        // Method 1: ShellFolder.getIcon() returns Icon, NOT Image!
        try {
            Class<?> sfClass = Class.forName("sun.awt.shell.ShellFolder");
            java.lang.reflect.Method getSF = sfClass.getMethod("getShellFolder", File.class);
            Object sf = getSF.invoke(null, file);
            java.lang.reflect.Method getIcon = sfClass.getMethod("getIcon", boolean.class);
            // ShellFolder returns javax.swing.Icon (ImageIcon), not java.awt.Image
            Icon icon = (Icon) getIcon.invoke(sf, true);
            if (icon != null) return scaleIcon(icon, 38, 38);
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
        if (iconCache.containsKey(appName)) return;

        Map<String, String> exeMap = new HashMap<>();
        exeMap.put("Google Chrome", "chrome.exe");
        exeMap.put("Microsoft Edge", "msedge.exe");
        exeMap.put("javaw", "javaw.exe");
        exeMap.put("Task Manager", "Taskmgr.exe");
        exeMap.put("File Explorer", "explorer.exe");

        String exeName = exeMap.get(appName);
        if (exeName == null) {
            exeName = appName.toLowerCase().replaceAll("[^a-zA-Z0-9]", "") + ".exe";
        }

        Map<String, String[]> knownPaths = new HashMap<>();
        knownPaths.put("chrome.exe", new String[]{
            System.getenv("PROGRAMFILES") + "\\Google\\Chrome\\Application\\chrome.exe",
            System.getenv("PROGRAMFILES(X86)") + "\\Google\\Chrome\\Application\\chrome.exe"
        });
        knownPaths.put("msedge.exe", new String[]{
            System.getenv("PROGRAMFILES(X86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            System.getenv("PROGRAMFILES") + "\\Microsoft\\Edge\\Application\\msedge.exe"
        });
        knownPaths.put("javaw.exe", new String[]{
            System.getenv("JAVA_HOME") + "\\bin\\javaw.exe",
            "C:\\Program Files\\Zulu\\zulu-24\\bin\\javaw.exe"
        });
        knownPaths.put("Taskmgr.exe", new String[]{ System.getenv("SystemRoot") + "\\System32\\Taskmgr.exe" });
        knownPaths.put("explorer.exe", new String[]{ System.getenv("SystemRoot") + "\\explorer.exe" });

        Icon real = null;
        String[] known = knownPaths.get(exeName);
        if (known != null) {
            for (String p : known) {
                if (p != null) {
                    File exe = new File(p);
                    if (exe.exists()) {
                        real = extractRealIcon(exe);
                        if (real != null) { iconCache.put(appName, real); return; }
                    }
                }
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("where.exe", exeName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor(3, TimeUnit.SECONDS);
            if (line != null && !line.isEmpty()) {
                File exe = new File(line.trim());
                if (exe.exists()) {
                    real = extractRealIcon(exe);
                    if (real != null) { iconCache.put(appName, real); return; }
                }
            }
        } catch (Exception ignored) {}
    }

    private static Icon scaleIcon(Icon icon, int w, int h) {
        if (icon instanceof ImageIcon) {
            Image src = ((ImageIcon) icon).getImage();
            int sw = src.getWidth(null);
            int sh = src.getHeight(null);
            // Use Graphics2D with high-quality rendering hints for crisp scaling
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = bi.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return new ImageIcon(bi);
        }
        return icon;
    }

    // 异步加载缺失图标，避免阻塞UI
    private static void preloadMissingIcons(java.util.List<String> appNames) {
        for (String name : appNames) {
            if (!iconCache.containsKey(name)) {
                iconLoader.execute(() -> {
                    // 从注册表查找 exe 路径
                    String exePath = findExePath(name);
                    cacheAppIcon(name, exePath);
                    // 通知表格刷新图标
                    SwingUtilities.invokeLater(() -> {
                        tableModel.fireTableDataChanged();
                    });
                });
            }
        }
    }

    // 通过注册表查找应用的 exe 路径
    private static String findExePath(String appName) {
        // 常用应用映射
        java.util.Map<String, String> knownPaths = new java.util.HashMap<>();
        knownPaths.put("Microsoft Edge", "msedge.exe");
        knownPaths.put("Google Chrome", "chrome.exe");
        knownPaths.put("微信", "WeChat.exe");
        knownPaths.put("QQ", "QQ.exe");
        knownPaths.put("钉钉", "DingTalk.exe");
        knownPaths.put("网易云音乐", "cloudmusic.exe");
        knownPaths.put("Visual Studio Code", "Code.exe");
        knownPaths.put("Notepad++", "notepad++.exe");
        knownPaths.put("File Explorer", "explorer.exe");
        knownPaths.put("Task Manager", "Taskmgr.exe");

        String exe = knownPaths.get(appName);
        if (exe == null) {
            // 尝试将 appName 转为 exe 名（首字母大写）
            String base = appName.replaceAll("[^a-zA-Z0-9]", "");
            if (!base.isEmpty()) {
                exe = base.substring(0, 1).toUpperCase() + base.substring(1) + ".exe";
            }
        }

        if (exe != null) {
            // 在 Start Menu 快捷方式中搜索
            String[] searchRoots = {
                System.getenv("APPDATA") + "\\Microsoft\\Windows\\Start Menu\\Programs",
                "C:\\ProgramData\\Microsoft\\Windows\\Start Menu\\Programs",
                System.getenv("LOCALAPPDATA"),
                System.getenv("PROGRAMFILES"),
                System.getenv("PROGRAMFILES(X86)")
            };

            for (String root : searchRoots) {
                if (root == null) continue;
                File r = new File(root);
                if (!r.isDirectory()) continue;

                File result = searchForExe(r, exe, 4);
                if (result != null && result.exists()) {
                    return result.getAbsolutePath();
                }
            }
        }
        return "";
    }

    // 递归搜索 exe 文件
    private static File searchForExe(File dir, String exeName, int maxDepth) {
        if (maxDepth <= 0 || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;

        for (File f : children) {
            if (f.isFile() && f.getName().equalsIgnoreCase(exeName)) {
                return f;
            }
            if (f.isDirectory()) {
                File found = searchForExe(f, exeName, maxDepth - 1);
                if (found != null) return found;
            }
        }
        return null;
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
        if (engine == null || engine.getConnection() == null) return;

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
                ps = engine.getConnection().prepareStatement(sql);
            } else {
                sql = "SELECT app_name,SUM(duration_sec) AS total_secs," +
                      "COUNT(*) AS sessions FROM usage_log " +
                      "WHERE date(start_time)>=? AND date(start_time)<=? " +
                      "GROUP BY app_name ORDER BY total_secs DESC LIMIT 20";
                ps = engine.getConnection().prepareStatement(sql);
                ps.setString(1, startDate);
                ps.setString(2, endDate);
            }

            ResultSet rs = ps.executeQuery();

            tableModel.setRowCount(0);
            java.util.List<Object[]> chartData = new ArrayList<>();
            java.util.List<String> appNamesForIconPreload = new ArrayList<>();
            int rank = 1;
            long grandTotal = 0;

            while (rs.next()) {
                String app = rs.getString("app_name");
                if (engine.shouldIgnore(app)) continue;

                long secs = rs.getLong("total_secs");
                int sess = rs.getInt("sessions");
                grandTotal += secs;

                String disp = engine.getFriendlyName(app);
                appNamesForIconPreload.add(disp);
                // Store display name in col 0 (used by AppIconRenderer)
                tableModel.addRow(new Object[]{
                    disp, rank++, disp, TrackingCore.formatDuration(secs), sess
                });
                chartData.add(new Object[]{disp, secs});
            }
            rs.close();
            ps.close();

            // 异步加载缺失图标（为所有出现在结果中的应用加载图标）
            preloadMissingIcons(appNamesForIconPreload);

            chartPanel.setData(chartData);

            String rangeLabel;
            switch (currentRange) {
                case "week":  rangeLabel = "\u672C\u5468"; break;
                case "month": rangeLabel = "\u672C\u6708"; break;
                case "all":   rangeLabel = "\u5168\u90E8"; break;
                default:      rangeLabel = "\u4ECA\u5929";
            }

            statusLabel.setText(String.format(
                "\uD83D\uDCCB %s | \u603B\u65F6\u957F %s | \u66F4\u65B0 %s",
                rangeLabel, TrackingCore.formatDuration(grandTotal),
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            ));

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void exportReport() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File out = new File(engine.getBaseDir(), "data/report_" + ts + ".txt");
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
                int pad = 30;
                int nameW = 170;       // fixed space for app names on left
                int durW = 90;          // fixed space for durations on right
                int barStart = pad + nameW + 16;
                int barArea = w - barStart - durW - pad;
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
                int gap = 20;
                int barH = Math.max(44, (usableH - (n - 1) * gap) / n);
                int startY = pad + 10;

                Font nameFont = new Font("SansSerif", Font.PLAIN, 16);
                Font durFont = new Font("SansSerif", Font.PLAIN, 15);

                for (int i = 0; i < n; i++) {
                    Object[] row = data.get(i);
                    String name = (String) row[0];
                    long val = (Long) row[1];
                    int barW = (int) ((double) val / maxVal * barArea);
                    barW = Math.max(barW, 8);

                    int y = startY + i * (barH + gap);
                    Color c = palette[i % palette.length];

                    // App name — fixed left position, vertically centered
                    g2.setFont(nameFont);
                    g2.setColor(TEXT_PRIMARY);
                    FontMetrics fm = g2.getFontMetrics();
                    String label = name.length() > 10 ? name.substring(0, 9) + ".." : name;
                    int textY = y + (barH + fm.getAscent() - fm.getDescent()) / 2;
                    g2.drawString(label, pad, textY);

                    // Gradient bar
                    GradientPaint grad = new GradientPaint(
                        barStart, y, new Color(c.getRed(), c.getGreen(), c.getBlue(), 150),
                        barStart + 8, y, c);
                    g2.setPaint(grad);
                    g2.fillRoundRect(barStart, y, barW, barH, 8, 8);
                    g2.setColor(c);
                    g2.drawRoundRect(barStart, y, barW, barH, 8, 8);

                    // Duration — fixed right position, vertically centered
                    g2.setFont(durFont);
                    g2.setColor(TEXT_SECONDARY);
                    String dur = TrackingCore.formatDuration(val);
                    FontMetrics durFm = g2.getFontMetrics();
                    int durStrW = durFm.stringWidth(dur);
                    g2.drawString(dur, w - pad - durStrW, textY);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
