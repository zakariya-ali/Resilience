package com.example.inventory;

/** The four simulated behaviours of the Inventory Service. */
public enum Mode {
    NORMAL,   // HTTP 200 immediately
    SLOW,     // HTTP 200 after 2 seconds
    FAILURE,  // HTTP 500 always
    RANDOM    // HTTP 200 or HTTP 500 (50/50)
}
