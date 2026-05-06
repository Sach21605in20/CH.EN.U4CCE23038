package com.vehicle.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a vehicle maintenance task.
 * Some tasks in the API response have missing Duration or Impact — those are intentionally invalid.
 * Call isValid() before using a vehicle in the knapsack algorithm.
 */
public class Vehicle {

    @JsonProperty("TaskID")
    private String taskId;

    @JsonProperty("Duration")
    private Integer duration;  // Integer (nullable) — some API entries omit this field

    @JsonProperty("Impact")
    private Integer impact;    // Integer (nullable) — some API entries omit this field

    public Vehicle() {}

    /**
     * A vehicle task is valid only when both Duration and Impact are present and positive.
     * Invalid entries are silently skipped before running the knapsack algorithm.
     */
    public boolean isValid() {
        return taskId != null
                && duration != null
                && impact != null
                && duration > 0
                && impact > 0;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public Integer getImpact() {
        return impact;
    }

    public void setImpact(Integer impact) {
        this.impact = impact;
    }
}
