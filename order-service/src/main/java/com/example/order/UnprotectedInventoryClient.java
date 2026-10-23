package com.example.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Profile "unprotected": a plain HTTP call. No Circuit Breaker, no Retry, no Rate Limiter.
 * Every incoming request goes straight to the Inventory Service, and any error is thrown to the caller.
 */
@Service
@Profile("unprotected")
public class UnprotectedInventoryClient implements InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(UnprotectedInventoryClient.class);

    private final RestClient restClient;

    public UnprotectedInventoryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public InventoryResult checkInventory(String mode) {
        log.info("Calling inventory-service (unprotected) mode={}", mode);
        // RestClient throws HttpServerErrorException for 5xx and ResourceAccessException for timeouts/connection errors
        Map<?, ?> body = restClient.get()
                .uri(uri -> uri.path("/api/inventory/check").queryParamIfPresent("mode", Optional.ofNullable(mode)).build())
                .retrieve()
                .body(Map.class);
        return InventoryResult.fromInventory(Boolean.TRUE.equals(body.get("available")));
    }
}
