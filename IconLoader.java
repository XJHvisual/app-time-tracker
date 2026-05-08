import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Windows application icon loader.
 * Extracts real icons from executables using multiple methods.
 */
public class IconLoader {
    
    private static final Map<String, Icon> cache = new ConcurrentHashMap<>();
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);
    
    // Common app name to exe mappings
    private static final Map<String, String> KNOWN_APPS = new HashMap<>();
    static {
        KNOWN_APPS.put("msedge", "Microsoft Edge");
        KNOWN_APPS.put("chrome", "Google Chrome");
        KNOWN_APPS.put("wechat", "WeChat");
        KNOWN_APPS.put("qq", "QQ");
        KNOWN_APPS.put("dingtalk", "DingTalk");
        KNOWN_APPS.put("cloudmusic", "NetEase Cloud Music");
        KNOWN_APPS.put("code", "Visual Studio Code");
        KNOWN_APPS.put("notepad++", "Notepad++");
        KNOWN_APPS.put("explorer", "File Explorer");
        KNOWN_APPS.put("taskmgr", "Task Manager");
        KNOWN_APPS.put("douyin", "Douyin");
        KNOWN_APPS.put("client-win64-shipping", "Wuthering Waves");
    }
    
    /**
     * Get icon for an application (cached).
     */
    public static Icon getIcon(String appName, String exePath) {
        if (appName == null || appName.isEmpty()) return null;
        
        String key = appName.toLowerCase();
        Icon cached = cache.get(key);
        if (cached != null) return cached;
        
        // Try to load icon
        if (!loading.contains(key)) {
            loading.add(key);
            executor.submit(() -> {
                try {
                    Icon icon = loadIcon(appName, exePath);
                    if (icon != null) {
                        cache.put(key, icon);
                    }
                } finally {
                    loading.remove(key);
                }
            });
        }
        return null;
    }
    
    /**
     * Load icon from exe file.
     */
    private static Icon loadIcon(String appName, String exePath) {
        // Method 1: Use provided exe path
        if (exePath != null && !exePath.isEmpty()) {
            File exe = new File(exePath);
            if (exe.exists()) {
                Icon icon = extractIcon(exe);
                if (icon != null) return icon;
            }
        }
        
        // Method 2: Search for exe
        File exeFile = findExeFile(appName);
        if (exeFile != null) {
            Icon icon = extractIcon(exeFile);
            if (icon != null) return icon;
        }
        
        return null;
    }
    
    /**
     * Extract icon from exe file using multiple methods.
     */
    private static Icon extractIcon(File file) {
        // Method 1: ShellFolder (best quality)
        try {
            Class<?> sfClass = Class.forName("sun.awt.shell.ShellFolder");
            java.lang.reflect.Method getSF = sfClass.getMethod("getShellFolder", File.class);
            Object sf = getSF.invoke(null, file);
            java.lang.reflect.Method getIcon = sfClass.getMethod("getIcon", boolean.class);
            Icon icon = (Icon) getIcon.invoke(sf, true);
            if (icon != null) {
                return scaleIcon(icon, 32, 32);
            }
        } catch (Exception ignored) {}
        
        // Method 2: FileSystemView
        try {
            FileSystemView fsv = FileSystemView.getFileSystemView();
            Icon icon = fsv.getSystemIcon(file);
            if (icon != null) {
                return scaleIcon(icon, 32, 32);
            }
        } catch (Exception ignored) {}
        
        return null;
    }
    
    /**
     * Find exe file for an application.
     */
    private static File findExeFile(String appName) {
        String lower = appName.toLowerCase();
        
        // Build search patterns
        String[] patterns = {
            appName + ".exe",
            lower + ".exe",
            appName.replaceAll("[^a-zA-Z0-9]", "") + ".exe"
        };
        
        // Search paths
        String[] searchPaths = {
            System.getenv("LOCALAPPDATA"),
            System.getenv("PROGRAMFILES"),
            System.getenv("PROGRAMFILES(X86)"),
            System.getenv("SystemRoot") + "\\System32"
        };
        
        for (String pattern : patterns) {
            for (String basePath : searchPaths) {
                if (basePath == null) continue;
                File base = new File(basePath);
                if (!base.isDirectory()) continue;
                
                // Direct match
                File direct = new File(base, pattern);
                if (direct.exists()) return direct;
                
                // Search subdirectories
                File found = searchExe(base, pattern, lower, 3);
                if (found != null) return found;
            }
        }
        
        return null;
    }
    
    private static File searchExe(File dir, String exeName, String appLower, int depth) {
        if (depth <= 0 || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        
        for (File f : children) {
            if (f.isFile() && f.getName().equalsIgnoreCase(exeName)) {
                return f;
            }
            if (f.isDirectory()) {
                String subName = f.getName().toLowerCase();
                if (subName.contains(appLower) || appLower.contains(subName)) {
                    File found = searchExe(f, exeName, appLower, depth - 1);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }
    
    private static Icon scaleIcon(Icon icon, int w, int h) {
        if (!(icon instanceof ImageIcon)) return icon;
        Image img = ((ImageIcon) icon).getImage();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return new ImageIcon(bi);
    }
    
    /**
     * Create fallback circle icon with initial letter.
     */
    public static Icon createFallbackIcon(String name) {
        int sz = 32;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        Color bg = Color.getHSBColor(Math.abs(name.hashCode()) % 360 / 360f, 0.55f, 0.80f);
        g.setColor(bg);
        g.fillOval(0, 0, sz, sz);
        
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        String letter = name.substring(0, 1).toUpperCase();
        FontMetrics fm = g.getFontMetrics();
        g.drawString(letter, (sz - fm.stringWidth(letter)) / 2, (sz + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
        return new ImageIcon(img);
    }
    
    public static void shutdown() {
        executor.shutdown();
    }
}
