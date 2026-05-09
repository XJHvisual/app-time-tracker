import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import java.util.Optional;

/**
 * JNA-based foreground window detection — replaces get-foreground.ps1.
 * Eliminates ~200-500ms PowerShell startup overhead per scan cycle.
 */
public class ForegroundDetector {

    private interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);
        Pointer GetForegroundWindow();
        int GetWindowThreadProcessId(Pointer hWnd, IntByReference lpdwProcessId);
    }

    /**
     * Returns [processName, exePath] or null if detection fails.
     * processName = executable name without .exe (e.g. "chrome", "msedge").
     * exePath     = full path to the executable (may be empty).
     */
    public static String[] detect() {
        try {
            IntByReference pidRef = new IntByReference();
            Pointer hwnd = User32.INSTANCE.GetForegroundWindow();
            if (hwnd == null) return null;
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pidRef);
            int pid = pidRef.getValue();
            if (pid <= 0) return null;

            // Use ProcessHandle (Java 9+) to get exe path
            Optional<ProcessHandle> ph = ProcessHandle.of(pid);
            String cmd = ph.flatMap(h -> h.info().command()).orElse("");

            // Extract process name from path (e.g. "C:\...\chrome.exe" → "chrome")
            String name = extractProcessName(cmd);
            return new String[]{name, cmd};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractProcessName(String exePath) {
        if (exePath.isEmpty()) return "";
        int sep = Math.max(exePath.lastIndexOf('\\'), exePath.lastIndexOf('/'));
        String fileName = sep >= 0 ? exePath.substring(sep + 1) : exePath;
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
