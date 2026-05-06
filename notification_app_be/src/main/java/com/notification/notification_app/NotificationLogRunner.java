package com.notification.notification_app;

import com.notification.notification_app.logging.LogMiddleware;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone runner that sends one notification log per execution, in order.
 * Progress is tracked in notification_log_progress.txt at the project root.
 *
 * Run via Maven (from inside notification_app_be/):
 *   mvn exec:java -Dexec.mainClass="com.notification.NotificationLogRunner"
 */
public class NotificationLogRunner {

    private static final String PROGRESS_FILE = "notification_log_progress.txt";

    // All notification-related logs in order — { level, package, message }
    private static final List<String[]> LOGS = List.of(

            // NotificationController — getPriorityInbox()
            new String[]{"info",  "handler", "GET /api/v1/notifications/priority-inbox request received"},
            new String[]{"error", "handler", "GET /api/v1/notifications/priority-inbox failed: error"},
            new String[]{"info",  "handler", "priority inbox responded with top 10 notifications"},


            // NotificationService — getTopNotifications()
            new String[]{"info",  "service", "getTopNotifications called - fetching tnew String[]{\"error\", \"handler\", \"GET /api/v1/notifications/priority-inbox failed: error\"},op 10 notifications"},

            // NotificationService — fetchNotifications()
            new String[]{"debug", "service", "calling notifications API"},
            new String[]{"info",  "service", "notifications API call successful - received 10 notifications"},

            // NotificationService — filtering
            new String[]{"info",  "service", "8 valid notifications after filtering"},

            // NotificationService — scoring
            new String[]{"debug", "service", "priority scoring complete for 8 notifications"},

            // NotificationService — heap
            new String[]{"info",  "service", "min heap processing complete - returning top 8 notifications"},

            // NotificationService — warn cases
            new String[]{"warn",  "service", "unknown notification type encountered: Unknown"},
            new String[]{"warn",  "service", "failed to parse timestamp for notification abc123 - defaulting recency to 0"}
    );

    public static void main(String[] args) throws Exception {
        int index = readProgress();

        if (index >= LOGS.size()) {
            System.out.println("All " + LOGS.size() + " notification logs have been sent.");
            System.out.println("Delete " + PROGRESS_FILE + " to start over.");
            return;
        }

        String[] log     = LOGS.get(index);
        String   level   = log[0];
        String   pkg     = log[1];
        String   message = log[2];

        System.out.println("Sending notification log " + (index + 1) + " of " + LOGS.size() + ":");
        System.out.println("  level:   " + level);
        System.out.println("  package: " + pkg);
        System.out.println("  message: " + message);

        // Uses this project's own LogMiddleware — no external dependency needed
        LogMiddleware.Log("backend", level, pkg, message);

        // Small wait to let the async HTTP call dispatch before the JVM exits
        Thread.sleep(1500);

        saveProgress(index + 1);

        int remaining = LOGS.size() - (index + 1);
        if (remaining > 0) {
            System.out.println("Done. " + remaining + " logs remaining. Run again to send next.");
        } else {
            System.out.println("Done. All notification logs sent.");
        }
    }

    private static int readProgress() {
        try {
            return Integer.parseInt(Files.readString(Path.of(PROGRESS_FILE)).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static void saveProgress(int nextIndex) throws IOException {
        Files.writeString(Path.of(PROGRESS_FILE), String.valueOf(nextIndex));
    }
}