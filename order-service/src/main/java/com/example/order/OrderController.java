package com.example.order;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final InventoryClient inventoryClient; // protected or unprotected - decided by the Spring profile
    private final String profile;

    public OrderController(InventoryClient inventoryClient, Environment env) {
        this.inventoryClient = inventoryClient;
        // Active profiles, or the default profile ("protected") when none was set explicitly
        String[] active = env.getActiveProfiles().length > 0 ? env.getActiveProfiles() : env.getDefaultProfiles();
        this.profile = String.join(",", Arrays.asList(active));
    }

    /**
     * GET /api/orders/check?mode=FAILURE
     * "mode" is optional and forwarded to Inventory Service to choose its behaviour.
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestParam(required = false) String mode) {
        long start = System.currentTimeMillis();

        InventoryResult result = inventoryClient.checkInventory(mode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profile", profile);
        body.put("inventoryAvailable", result.available());
        body.put("fallback", result.fallback());
        body.put("message", result.message());
        body.put("durationMs", System.currentTimeMillis() - start);

        // A fallback is still a failure: return 503 so clients and k6 can see it.
        HttpStatus status = result.fallback() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }
}
