package com.example.order;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Profile "protected": EXACTLY the same HTTP call as {@link UnprotectedInventoryClient},
 * wrapped by three Resilience4j annotations. All settings live in application.yml under the name "inventory".
 *
 * <p>Execution order (configured with *-aspect-order in application.yml), outermost first:
 * <pre>
 *   RateLimiter  ->  Retry  ->  CircuitBreaker  ->  HTTP call
 * </pre>
 * <ul>
 *   <li>RateLimiter first: excess requests are rejected immediately (HTTP 429) and never retried.</li>
 *   <li>Retry wraps CircuitBreaker: every attempt is counted by the circuit breaker, and once it is
 *       OPEN, retries stop because CallNotPermittedException is not retryable.</li>
 * </ul>
 *
 * <p>IMPORTANT: the annotations only work because the controller calls this bean through its Spring proxy
 * (it is injected as {@link InventoryClient}). Calling an annotated method from inside the same class
 * (this.checkInventory(...)) would bypass the proxy and the patterns would silently do nothing.
 */
@Service
@Profile("protected")
public class ProtectedInventoryClient implements InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(ProtectedInventoryClient.class);

    private final RestClient restClient;

    public ProtectedInventoryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    @RateLimiter(name = "inventory")                                    // RATE LIMITER: ~10 req/s, extra -> RequestNotPermitted -> 429
    @Retry(name = "inventory")                                          // RETRY: up to 3 attempts, 500ms apart
    @CircuitBreaker(name = "inventory", fallbackMethod = "circuitOpenFallback") // CIRCUIT BREAKER: opens at 50% failures
    public InventoryResult checkInventory(String mode) {
        log.info("Calling inventory-service (protected) mode={}", mode); // printed once per ATTEMPT -> shows retries
        Map<?, ?> body = restClient.get()
                .uri(uri -> uri.path("/api/inventory/check").queryParamIfPresent("mode", Optional.ofNullable(mode)).build())
                .retrieve()
                .body(Map.class);
        return InventoryResult.fromInventory(Boolean.TRUE.equals(body.get("available")));
    }

    /**
     * FALLBACK - only called when the circuit is OPEN (or HALF_OPEN and full).
     * Resilience4j picks the fallback whose exception parameter matches the thrown exception.
     * Other exceptions (e.g. HTTP 500 after all retries) have no matching fallback, so they are re-thrown
     * and the caller sees a real error.
     * The parameters must match the original method + the exception as last parameter.
     */
    private InventoryResult circuitOpenFallback(String mode, CallNotPermittedException ex) {
        log.warn("FALLBACK: circuit breaker is OPEN - inventory-service NOT called ({})", ex.getMessage());
        return InventoryResult.fallback("Inventory service temporarily unavailable (circuit breaker OPEN)");
    }
}
