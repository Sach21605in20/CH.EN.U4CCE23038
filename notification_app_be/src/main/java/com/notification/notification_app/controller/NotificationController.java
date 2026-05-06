package com.notification.notification_app.controller;

import com.notification.notification_app.logging.LogMiddleware;
import com.notification.notification_app.model.Notification;
import com.notification.notification_app.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the Campus Notification Priority Inbox.
 * Exposes a single endpoint:  GET /api/v1/notifications/priority-inbox
 *
 * Returns the top 10 notifications sorted by priority score (type + recency),
 * with Placement always ranked above Result, and Result always above Event.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * GET /api/v1/notifications/priority-inbox
     *
     * Response body:
     * {
     *   "topNotifications": [
     *     { "id": "...", "type": "Placement", "message": "...",
     *       "timestamp": "...", "priorityScore": 39.0 },
     *     ...
     *   ]
     * }
     */
    @GetMapping("/priority-inbox")
    public ResponseEntity<Map<String, Object>> getPriorityInbox() {
        LogMiddleware.Log("backend", "info", "handler",
                "GET /api/v1/notifications/priority-inbox request received");

        try {
            List<Notification> top = notificationService.getTopNotifications();

            LogMiddleware.Log("backend", "info", "handler",
                    "priority inbox responded with top " + top.size() + " notifications");

            return ResponseEntity.ok(Map.of("topNotifications", top));

        } catch (Exception e) {
            LogMiddleware.Log("backend", "error", "handler",
                    "GET /api/v1/notifications/priority-inbox failed: " + e.getMessage());

            return ResponseEntity
                    .internalServerError()
                    .body(Map.of("error", "Failed to retrieve priority inbox: " + e.getMessage()));
        }
    }
}
