package com.vehicle.controller;

import com.vehicle.logging.LogMiddleware;
import com.vehicle.service.VehicleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the Vehicle Maintenance Scheduler.
 * Exposes a single endpoint:  GET /api/v1/vehicles/schedule
 *
 * The endpoint fetches depots and vehicles from the evaluation service,
 * runs 0/1 Knapsack for each depot, and returns optimal task selections.
 */
@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    /**
     * GET /api/v1/vehicles/schedule
     *
     * Returns for every depot:
     *   - depotId
     *   - mechanicHours (available budget)
     *   - selectedTasks (list of TaskIDs chosen to maximise impact)
     *   - totalImpact (combined impact score of selected tasks)
     */
    @GetMapping("/schedule")
    public ResponseEntity<Map<String, Object>> getSchedule() {
        LogMiddleware.Log("backend", "info", "handler",
                "GET /api/v1/vehicles/schedule request received");

        try {
            List<Map<String, Object>> schedules = vehicleService.computeSchedule();

            LogMiddleware.Log("backend", "info", "handler",
                    "responded successfully with " + schedules.size() + " depot schedules");

            return ResponseEntity.ok(Map.of("depotSchedules", schedules));

        } catch (Exception e) {
            LogMiddleware.Log("backend", "error", "handler",
                    "GET /api/v1/vehicles/schedule failed: " + e.getMessage());

            return ResponseEntity
                    .internalServerError()
                    .body(Map.of("error", "Failed to compute schedule: " + e.getMessage()));
        }
    }
}
