package com.example.order;

/**
 * Result of asking the Inventory Service.
 *
 * @param available whether inventory is available
 * @param fallback  true when this is NOT a real answer but a fallback (e.g. circuit open).
 *                  The controller turns fallbacks into HTTP 503 so failures are never hidden.
 * @param message   human-readable explanation
 */
public record InventoryResult(boolean available, boolean fallback, String message) {

    public static InventoryResult fromInventory(boolean available) {
        return new InventoryResult(available, false, "Answer from inventory-service");
    }

    public static InventoryResult fallback(String message) {
        return new InventoryResult(false, true, message);
    }
}
