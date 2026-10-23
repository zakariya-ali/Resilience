package com.example.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    /** Default behaviour, from application.yml (inventory.mode) or env var INVENTORY_MODE. */
    @Value("${inventory.mode:NORMAL}")
    private Mode defaultMode;

    // Simple counters: they show HOW MANY calls actually reached this dependency.
    // Compare them between the protected and unprotected runs.
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong successCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();

    /**
     * GET /api/inventory/check?mode=FAILURE
     * The optional "mode" query parameter overrides the configured default for this one request.
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestParam(required = false) Mode mode)
            throws InterruptedException {
        Mode effective = (mode != null) ? mode : defaultMode;
        long callNumber = totalCalls.incrementAndGet();
        log.info("Inventory call #{} mode={}", callNumber, effective);

        boolean fail = switch (effective) {
            case NORMAL -> false;
            case SLOW -> {
                Thread.sleep(2000); // simulate a slow database / overloaded dependency
                yield false;
            }
            case FAILURE -> true;
            case RANDOM -> ThreadLocalRandom.current().nextBoolean();
        };

        if (fail) {
            failedCalls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Simulated inventory failure", "mode", effective, "call", callNumber));
        }
        successCalls.incrementAndGet();
        return ResponseEntity.ok(Map.of("available", true, "mode", effective, "call", callNumber));
    }

    /** GET /api/inventory/stats - how many requests reached the Inventory Service. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
                "totalCalls", totalCalls.get(),
                "successCalls", successCalls.get(),
                "failedCalls", failedCalls.get(),
                "defaultMode", defaultMode);
    }

    /** POST /api/inventory/stats/reset - reset counters before each k6 run. */
    @PostMapping("/stats/reset")
    public Map<String, Object> reset() {
        totalCalls.set(0);
        successCalls.set(0);
        failedCalls.set(0);
        log.info("Inventory counters reset");
        return stats();
    }
}
