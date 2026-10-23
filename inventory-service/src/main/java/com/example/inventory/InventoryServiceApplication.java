package com.example.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory Service (port 8081) - the "downstream dependency" that Order Service calls.
 * Its behaviour can be switched between NORMAL, SLOW, FAILURE and RANDOM to simulate problems.
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
