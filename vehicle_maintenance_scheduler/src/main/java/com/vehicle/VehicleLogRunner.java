package com.vehicle;

import com.vehicle.logging.LogMiddleware;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Standalone runner that sends one vehicle log per execution, in order.
 * Progress is tracked in vehicle_log_progress.txt at the project root.
 *
 * Run via Maven (from inside vehicle_maintenance_scheduler/):
 *   mvn exec:java -Dexec.mainClass="com.vehicle.VehicleLogRunner"
 */
public class VehicleLogRunner {

    private static final String PROGRESS_FILE = "vehicle_log_progress.txt";

    // All vehicle-related logs in order — { level, package, message }
    private static final List<String[]> LOGS = List.of(
            new String[]{"info",  "service", "Real Logging Starts"},
            // VehicleController — getSchedule()
            new String[]{"info",  "handler", "GET /api/v1/vehicles/schedule request received"},
            new String[]{"info",  "handler", "responded successfully with 5 depot schedules"},
            new String[]{"error", "handler", "GET /api/v1/vehicles/schedule failed: simulated error"},

            // VehicleService — computeSchedule()
            new String[]{"info",  "service", "computeSchedule started - fetching depots and vehicles"},

            // VehicleService — fetchDepots()
            new String[]{"debug", "service", "calling depots API"},
            new String[]{"info",  "service", "depots API call successful - received 5 depots"},
            new String[]{"error", "service", "failed to fetch depots: simulated error"},

            // VehicleService — fetchVehicles()
            new String[]{"debug", "service", "calling vehicles API"},
            new String[]{"info",  "service", "vehicles API call successful - received 24 vehicles"},
            new String[]{"error", "service", "failed to fetch vehicles: Real error"},

            // VehicleService — filtering
            new String[]{"info",  "service", "23 valid vehicles after filtering invalid entries"},

            // VehicleService — knapsack per depot
            new String[]{"debug", "service", "running knapsack for depot 1 with budget 60 hours"},
            new String[]{"info",  "service", "knapsack complete for depot 1 - total impact: 42"},
            new String[]{"debug", "service", "running knapsack for depot 2 with budget 135 hours"},
            new String[]{"info",  "service", "knapsack complete for depot 2 - total impact: 89"},
            new String[]{"debug", "service", "running knapsack for depot 3 with budget 188 hours"},
            new String[]{"info",  "service", "knapsack complete for depot 3 - total impact: 124"},
            new String[]{"debug", "service", "running knapsack for depot 4 with budget 97 hours"},
            new String[]{"info",  "service", "knapsack complete for depot 4 - total impact: 63"},
            new String[]{"debug", "service", "running knapsack for depot 5 with budget 164 hours"},
            new String[]{"info",  "service", "knapsack complete for depot 5 - total impact: 108"}
    );

    public static void main(String[] args) throws Exception {
        int index = readProgress();

        if (index >= LOGS.size()) {
            System.out.println("All " + LOGS.size() + " vehicle logs have been sent.");
            System.out.println("Delete " + PROGRESS_FILE + " to start over.");
            return;
        }

        String[] log     = LOGS.get(index);
        String   level   = log[0];
        String   pkg     = log[1];
        String   message = log[2];

        System.out.println("Sending vehicle log " + (index + 1) + " of " + LOGS.size() + ":");
        System.out.println("  level:   " + level);
        System.out.println("  package: " + pkg);
        System.out.println("  message: " + message);

        // Uses this project's own LogMiddleware — no external dependency needed
        LogMiddleware.Log("backend", level, pkg, message);

        // Small wait to let the async HTTP call dispatch before the JVM exits
        Thread.sleep(1500);

        int remaining = LOGS.size() - (index + 1);
        if (remaining > 0) {
            System.out.println("Done. " + remaining + " logs remaining. Run again to send next.");
        } else {
            System.out.println("Done. All vehicle logs sent.");
        }
    }

    private static int readProgress() {
        try {
            return Integer.parseInt(Files.readString(Path.of(PROGRESS_FILE)).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}