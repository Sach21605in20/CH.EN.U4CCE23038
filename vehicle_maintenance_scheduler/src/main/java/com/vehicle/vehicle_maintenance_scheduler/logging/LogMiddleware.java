package com.vehicle.vehicle_maintenance_scheduler.logging;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Reusable logging middleware.
 * Sends structured log entries to the central evaluation log API.
 * This is the only mechanism for logging — no System.out.println or language loggers allowed.
 *
 * Usage:
 *   LogMiddleware.Log("backend", "info", "handler", "request received");
 */
public class LogMiddleware {

    private static final String LOG_API_URL = "http://20.207.122.201/evaluation-service/logs";
    private static final String AUTH_TOKEN = "Bearer YOUR_AUTH_TOKEN_HERE";

    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Sends a log entry to the central log API.
     *
     * @param stack   "backend" or "frontend"
     * @param level   "debug" | "info" | "warn" | "error" | "fatal"
     * @param pkg     "cache" | "controller" | "cron_job" | "db" | "domain" |
     *                "handler" | "repository" | "route" | "service"
     * @param message human-readable log message
     */
    public static void Log(String stack, String level, String pkg, String message) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("stack", stack);
            body.put("level", level);
            body.put("package", pkg);
            body.put("message", message);

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LOG_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", AUTH_TOKEN)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        } catch (Exception e) {
            // Intentionally silent — logging must never crash the application
        }
    }
}