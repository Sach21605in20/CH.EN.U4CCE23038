package com.vehicle.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vehicle.logging.LogMiddleware;
import com.vehicle.model.Depot;
import com.vehicle.model.Vehicle;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Core service for the Vehicle Maintenance Scheduler.
 *
 * Responsibilities:
 *   1. Fetch depots from evaluation API
 *   2. Fetch vehicle tasks from evaluation API
 *   3. Filter out invalid tasks
 *   4. Run 0/1 Knapsack DP for each depot to maximise impact within mechanic-hour budget
 *   5. Return per-depot schedules (selected task IDs + total impact)
 */
@Service
public class VehicleService {

    private static final String DEPOTS_API_URL  = "http://20.207.122.201/evaluation-service/depots";
    private static final String VEHICLES_API_URL = "http://20.207.122.201/evaluation-service/vehicles";
    private static final String AUTH_TOKEN       = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJNYXBDbGFpbXMiOnsiYXVkIjoiaHR0cDovLzIwLjI0NC41Ni4xNDQvZXZhbHVhdGlvbi1zZXJ2aWNlIiwiZW1haWwiOiJjaC5lbi51NGNjZTIzMDM4QGNoLnN0dWRlbnRzLmFtcml0YS5lZHUiLCJleHAiOjE3NzgwNjIyMzUsImlhdCI6MTc3ODA2MTMzNSwiaXNzIjoiQWZmb3JkIE1lZGljYWwgVGVjaG5vbG9naWVzIFByaXZhdGUgTGltaXRlZCIsImp0aSI6Ijg5MGI5NWNjLWUwYmMtNGVhOC05NzY1LTBiNjEwN2UxYTRjYyIsImxvY2FsZSI6ImVuLUlOIiwibmFtZSI6InNhY2hpbiByYW1lc2giLCJzdWIiOiJjNTRlMzIyYy02NWIzLTQxN2QtOTY4Yi05YmMxMTA3NWM5NjEifSwiZW1haWwiOiJjaC5lbi51NGNjZTIzMDM4QGNoLnN0dWRlbnRzLmFtcml0YS5lZHUiLCJuYW1lIjoic2FjaGluIHJhbWVzaCIsInJvbGxObyI6ImNoLmVuLnU0Y2NlMjMwMzgiLCJhY2Nlc3NDb2RlIjoiUFRCTW1RIiwiY2xpZW50SUQiOiJjNTRlMzIyYy02NWIzLTQxN2QtOTY4Yi05YmMxMTA3NWM5NjEiLCJjbGllbnRTZWNyZXQiOiJ1Q2R1WFJqYXZ3am5DY1VhIn0.t3jfP8geDWFb9mYBMjWP6X2dHvlo8Jm-HsxBo9X225A";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Entry point called by the controller.
     * Returns a list of maps, one per depot, each containing:
     *   - depotId
     *   - mechanicHours (budget)
     *   - selectedTasks (list of TaskIDs chosen by knapsack)
     *   - totalImpact (sum of impact scores for chosen tasks)
     */
    public List<Map<String, Object>> computeSchedule() {
        LogMiddleware.Log("backend", "info", "service",
                "computeSchedule started - fetching depots and vehicles");

        List<Depot>   depots   = fetchDepots();
        List<Vehicle> vehicles = fetchVehicles();

        // Filter out tasks that are missing Duration or Impact
        List<Vehicle> validVehicles = vehicles.stream()
                .filter(Vehicle::isValid)
                .toList();

        LogMiddleware.Log("backend", "info", "service",
                validVehicles.size() + " valid vehicles after filtering invalid entries");

        List<Map<String, Object>> schedules = new ArrayList<>();

        for (Depot depot : depots) {
            LogMiddleware.Log("backend", "debug", "service",
                    "running knapsack for depot " + depot.getId()
                            + " with budget " + depot.getMechanicHours() + " hours");

            Map<String, Object> result = runKnapsack(depot, validVehicles);

            LogMiddleware.Log("backend", "info", "service",
                    "knapsack complete for depot " + depot.getId()
                            + " - total impact: " + result.get("totalImpact"));

            schedules.add(result);
        }

        return schedules;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 0/1 Knapsack — standard bottom-up DP
    // Time:  O(n * W) where n = number of valid tasks, W = mechanic-hour budget
    // Space: O(n * W) for the DP table (can be reduced to O(W) with 1D array)
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, Object> runKnapsack(Depot depot, List<Vehicle> vehicles) {
        int n      = vehicles.size();
        int budget = depot.getMechanicHours();

        // dp[i][w] = max impact using first i items with capacity w
        int[][] dp = new int[n + 1][budget + 1];

        for (int i = 1; i <= n; i++) {
            Vehicle v = vehicles.get(i - 1);
            int duration = v.getDuration();
            int impact   = v.getImpact();

            for (int w = 0; w <= budget; w++) {
                // Option 1: skip this task
                dp[i][w] = dp[i - 1][w];

                // Option 2: include this task (only if it fits)
                if (duration <= w) {
                    int withItem = dp[i - 1][w - duration] + impact;
                    if (withItem > dp[i][w]) {
                        dp[i][w] = withItem;
                    }
                }
            }
        }

        // Backtrack to find which tasks were selected
        List<String> selectedTasks = new ArrayList<>();
        int w = budget;
        for (int i = n; i >= 1; i--) {
            if (dp[i][w] != dp[i - 1][w]) {
                // This task was included
                Vehicle v = vehicles.get(i - 1);
                selectedTasks.add(v.getTaskId());
                w -= v.getDuration();
            }
        }

        int totalImpact = dp[n][budget];

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("depotId",       depot.getId());
        result.put("mechanicHours", depot.getMechanicHours());
        result.put("selectedTasks", selectedTasks);
        result.put("totalImpact",   totalImpact);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API Fetch Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<Depot> fetchDepots() {
        LogMiddleware.Log("backend", "debug", "service", "calling depots API");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DEPOTS_API_URL))
                    .header("Authorization", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Depots status: " + response.statusCode());
            System.out.println("Depots body: " + response.body());
            if (response.statusCode() != 200) {
                LogMiddleware.Log("backend", "error", "service",
                        "depots API returned status " + response.statusCode()
                                + " - body: " + response.body());
                throw new RuntimeException("Depots API failed with status " + response.statusCode());
            }

            JsonNode root   = objectMapper.readTree(response.body());
            JsonNode array  = root.get("depots");

            List<Depot> depots = new ArrayList<>();
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    Depot depot = objectMapper.treeToValue(node, Depot.class);
                    depots.add(depot);
                }
            }

            LogMiddleware.Log("backend", "info", "service",
                    "depots API call successful - received " + depots.size() + " depots");

            return depots;

        } catch (Exception e) {
            LogMiddleware.Log("backend", "error", "service",
                    "failed to fetch depots: " + e.getMessage());
            throw new RuntimeException("Failed to fetch depots", e);
        }
    }

    private List<Vehicle> fetchVehicles() {
        LogMiddleware.Log("backend", "debug", "service", "calling vehicles API");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VEHICLES_API_URL))
                    .header("Authorization", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LogMiddleware.Log("backend", "error", "service",
                        "depots API returned status " + response.statusCode()
                                + " - body: " + response.body());
                throw new RuntimeException("Depots API failed with status " + response.statusCode());
            }


            JsonNode root  = objectMapper.readTree(response.body());
            JsonNode array = root.get("vehicles");

            List<Vehicle> vehicles = new ArrayList<>();
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    Vehicle vehicle = objectMapper.treeToValue(node, Vehicle.class);
                    vehicles.add(vehicle);
                }
            }

            LogMiddleware.Log("backend", "info", "service",
                    "vehicles API call successful - received " + vehicles.size() + " vehicles");

            return vehicles;

        } catch (Exception e) {
            LogMiddleware.Log("backend", "error", "service",
                    "failed to fetch vehicles: " + e.getMessage());
            throw new RuntimeException("Failed to fetch vehicles", e);
        }
    }
}
