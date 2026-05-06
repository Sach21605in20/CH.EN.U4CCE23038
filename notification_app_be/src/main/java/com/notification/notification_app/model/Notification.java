package com.notification.notification_app.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a campus notification fetched from the evaluation API.
 *
 * Some notifications in the API response have malformed IDs — those are intentionally invalid.
 * Call isValid() before scoring or including a notification in the priority inbox.
 *
 * A valid UUID matches the pattern: 8-4-4-4-12 hex characters separated by hyphens.
 */
public class Notification {

    @JsonProperty("ID")
    private String id;

    @JsonProperty("Type")
    private String type;

    @JsonProperty("Message")
    private String message;

    @JsonProperty("Timestamp")
    private String timestamp;

    // Populated by NotificationService after scoring — not from the API
    private double priorityScore;

    public Notification() {}

    /**
     * A notification is valid when:
     *   - ID is a well-formed UUID (8-4-4-4-12)
     *   - Type is one of: Placement, Result, Event
     *   - Message is non-null and non-blank
     *   - Timestamp is non-null and non-blank
     */
    public boolean isValid() {
        if (id == null || type == null || message == null || timestamp == null) {
            return false;
        }
        if (message.isBlank() || timestamp.isBlank()) {
            return false;
        }
        if (!type.equals("Placement") && !type.equals("Result") && !type.equals("Event")) {
            return false;
        }
        // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        return id.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public double getPriorityScore() { return priorityScore; }
    public void setPriorityScore(double priorityScore) { this.priorityScore = priorityScore; }
}
