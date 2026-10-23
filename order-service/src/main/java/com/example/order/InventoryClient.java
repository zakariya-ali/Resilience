package com.example.order;

/**
 * One interface, two implementations - Spring picks one based on the active profile:
 * <ul>
 *   <li>{@link UnprotectedInventoryClient} - profile "unprotected": plain HTTP call</li>
 *   <li>{@link ProtectedInventoryClient}   - profile "protected": same call + Circuit Breaker, Retry, Rate Limiter</li>
 * </ul>
 * The controller only knows this interface, so the business logic is identical in both profiles.
 */
public interface InventoryClient {

    /**
     * @param mode optional Inventory Service behaviour (NORMAL, SLOW, FAILURE, RANDOM), forwarded as ?mode=
     */
    InventoryResult checkInventory(String mode);
}
