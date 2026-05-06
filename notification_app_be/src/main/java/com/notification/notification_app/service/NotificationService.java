package com.notification.notification_app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.notification.notification_app.logging.LogMiddleware;
import com.notification.notification_app.model.Notification;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Core service for the Campus Notifications Priority Inbox.
 *
 * Scoring formula:
 *   priorityScore = (typeWeight * 10) + recencyScore
 *
 * Type weights:
 *   Placement = 3,  Result = 2,  Event = 1
 *
 * Recency score:
 *   recencyScore = max(0, 100 - hoursSinceCreation)
 *   Capped so type always dominates:
 *     Placement minimum (3*10 + 0) = 30 > Result maximum (2*10 + 99) = false
 *     Wait — type weight * 10: Placement=30, Result=20, Event=10
 *     RecencyScore max = 99 (would overflow type ordering)
 *     So we clamp: recencyScore = max(0, 10 - hoursSinceCreation) — keeps it 0..9
 *     This guarantees Placement (30+x) > Result (20+x) > Event (10+x) always.
 *
 * Top 10 selection:
 *   Min-heap of fixed size 10.
 *   O(log 10) per notification — effectively O(1).
 *   Total time O(n log 10) ≈ O(n).
 */
@Service
public class NotificationService {

    private static final String NOTIFICATIONS_API_URL =
            "http://20.207.122.201/evaluation-service/notifications";
    private static final String AUTH_TOKEN = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJNYXBDbGFpbXMiOnsiYXVkIjoiaHR0cDovLzIwLjI0NC41Ni4xNDQvZXZhbHVhdGlvbi1zZXJ2aWNlIiwiZW1haWwiOiJjaC5lbi51NGNjZTIzMDM4QGNoLnN0dWRlbnRzLmFtcml0YS5lZHUiLCJleHAiOjE3NzgwNjQ3MjEsImlhdCI6MTc3ODA2MzgyMSwiaXNzIjoiQWZmb3JkIE1lZGljYWwgVGVjaG5vbG9naWVzIFByaXZhdGUgTGltaXRlZCIsImp0aSI6ImZmZmQ3ODBjLWMwYzYtNGQ0My05YjZkLWZlYjQxNWQwMzI5NSIsImxvY2FsZSI6ImVuLUlOIiwibmFtZSI6InNhY2hpbiByYW1lc2giLCJzdWIiOiJjNTRlMzIyYy02NWIzLTQxN2QtOTY4Yi05YmMxMTA3NWM5NjEifSwiZW1haWwiOiJjaC5lbi51NGNjZTIzMDM4QGNoLnN0dWRlbnRzLmFtcml0YS5lZHUiLCJuYW1lIjoic2FjaGluIHJhbWVzaCIsInJvbGxObyI6ImNoLmVuLnU0Y2NlMjMwMzgiLCJhY2Nlc3NDb2RlIjoiUFRCTW1RIiwiY2xpZW50SUQiOiJjNTRlMzIyYy02NWIzLTQxN2QtOTY4Yi05YmMxMTA3NWM5NjEiLCJjbGllbnRTZWNyZXQiOiJ1Q2R1WFJqYXZ3am5DY1VhIn0.AWb-9eADSZeprjQNFxTT_cReEQYZ3HsBqB4j4fmZosU";

    private static final int TOP_N = 10;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Returns the top N most important unread notifications sorted by priorityScore descending.
     */
    public List<Notification> getTopNotifications() {
        LogMiddleware.Log("backend", "info", "service",
                "getTopNotifications called - fetching top " + TOP_N + " notifications");

        List<Notification> all = fetchNotifications();

        // Filter malformed entries
        List<Notification> valid = all.stream()
                .filter(Notification::isValid)
                .toList();

        LogMiddleware.Log("backend", "info", "service",
                valid.size() + " valid notifications after filtering");

        // Score each notification
        LocalDateTime now = LocalDateTime.now();
        for (Notification n : valid) {
            double score = computePriorityScore(n, now);
            n.setPriorityScore(score);
        }

        LogMiddleware.Log("backend", "debug", "service",
                "priority scoring complete for " + valid.size() + " notifications");

        // Min-heap of size TOP_N — keeps the highest scores
        // Comparator: smallest score at the top (so we can evict lowest easily)
        PriorityQueue<Notification> minHeap =
                new PriorityQueue<>(TOP_N + 1,
                        Comparator.comparingDouble(Notification::getPriorityScore));

        for (Notification n : valid) {
            minHeap.offer(n);
            if (minHeap.size() > TOP_N) {
                minHeap.poll(); // remove the lowest-scoring element
            }
        }

        LogMiddleware.Log("backend", "info", "service",
                "min heap processing complete - returning top " + minHeap.size() + " notifications");

        // Drain heap and sort descending for the response
        List<Notification> top = new ArrayList<>(minHeap);
        top.sort(Comparator.comparingDouble(Notification::getPriorityScore).reversed());

        return top;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scoring
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes a numeric priority score for a notification.
     *
     * typeWeight * 10 ensures type always dominates:
     *   Placement: 30 + recency
     *   Result:    20 + recency
     *   Event:     10 + recency
     *
     * recencyScore is clamped to [0, 9] so scores never bleed across type tiers.
     */
    private double computePriorityScore(Notification n, LocalDateTime now) {
        int typeWeight = getTypeWeight(n);

        long hoursSince = 0;
        try {
            LocalDateTime created = LocalDateTime.parse(n.getTimestamp(), TIMESTAMP_FORMAT);
            hoursSince = ChronoUnit.HOURS.between(created, now);
        } catch (Exception e) {
            LogMiddleware.Log("backend", "warn", "service",
                    "failed to parse timestamp for notification " + n.getId()
                            + " - defaulting recency to 0");
        }

        // Clamp recency to 0..9 to preserve type ordering
        double recencyScore = Math.max(0, 9 - hoursSince);

        return (typeWeight * 10.0) + recencyScore;
    }

    private int getTypeWeight(Notification n) {
        return switch (n.getType()) {
            case "Placement" -> 3;
            case "Result"    -> 2;
            case "Event"     -> 1;
            default -> {
                LogMiddleware.Log("backend", "warn", "service",
                        "unknown notification type encountered: " + n.getType());
                yield 0;
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API Fetch
    // ─────────────────────────────────────────────────────────────────────────

    private List<Notification> fetchNotifications() {
        LogMiddleware.Log("backend", "debug", "service", "calling notifications API");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(NOTIFICATIONS_API_URL))
                    .header("Authorization", AUTH_TOKEN)
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode root  = objectMapper.readTree(response.body());
            JsonNode array = root.get("notifications");

            List<Notification> notifications = new ArrayList<>();
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    Notification notif = objectMapper.treeToValue(node, Notification.class);
                    notifications.add(notif);
                }
            }

            LogMiddleware.Log("backend", "info", "service",
                    "notifications API call successful - received "
                            + notifications.size() + " notifications");

            return notifications;

        } catch (Exception e) {
            LogMiddleware.Log("backend", "error", "service",
                    "failed to fetch notifications: " + e.getMessage());
            throw new RuntimeException("Failed to fetch notifications", e);
        }
    }
}
