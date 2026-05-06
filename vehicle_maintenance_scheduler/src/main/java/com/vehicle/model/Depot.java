package com.vehicle.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a vehicle depot with a daily mechanic-hour budget.
 * Field names use capital letters to match the evaluation API's JSON response exactly.
 */
public class Depot {

    @JsonProperty("ID")
    private int id;

    @JsonProperty("MechanicHours")
    private int mechanicHours;

    public Depot() {}

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getMechanicHours() {
        return mechanicHours;
    }

    public void setMechanicHours(int mechanicHours) {
        this.mechanicHours = mechanicHours;
    }
}
