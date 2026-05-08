import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * CLI background tracker with single-instance lock.
 * Auto-starts tracking on launch, runs until Ctrl+C.
 */
public class AppTimeTracker {

    public static void main(String[] args) {
        String baseDir = TrackingCore.resolveBaseDir();
        TrackingCore engine = new TrackingCore(baseDir) {
            @Override
            protected boolean shouldIgnore(String name) {
                if (name == null || name.isEmpty()) return true;
                String lower = name.toLowerCase();
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

            @Override
            protected void onAppChanged(String appName, String exePath) {
                String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                System.out.println("[" + time + "] " + getFriendlyName(appName));
            }

            @Override
            protected void onFlush(long secs) {
                System.out.println("  >> " + getFriendlyName(currentApp) + " recorded: " + formatDuration(secs));
            }
        };

        // Single instance lock
        if (!engine.tryAcquireLock()) {
            System.exit(0);
        }

        engine.initDb();
        engine.startTracking();
        System.out.println("[Auto] Tracking started on launch.");
        System.out.println("Press Ctrl+C to stop.\n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            engine.stopTracking();
            engine.closeDb();
            engine.releaseLock();
            System.out.println("Tracking stopped. Bye!");
        }));

        engine.waitForTracking();
    }
}
