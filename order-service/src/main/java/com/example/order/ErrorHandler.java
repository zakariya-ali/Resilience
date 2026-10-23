package com.example.order;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

/**
 * Turns exceptions into clear HTTP responses. Shared by both profiles.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    /** RATE LIMITER rejection (protected profile only) -> HTTP 429 Too Many Requests. */
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<Map<String, Object>> rateLimited(RequestNotPermitted ex) {
        log.warn("RATE LIMITED: request rejected - {}", ex.getMessage());
        return error(HttpStatus.TOO_MANY_REQUESTS, "Too many requests - rate limit exceeded");
    }

    /** Inventory Service returned 5xx (after all retries, in the protected profile) -> HTTP 500. */
    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<Map<String, Object>> downstreamError(HttpServerErrorException ex) {
        log.error("Inventory service error: {}", ex.getStatusCode());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Inventory service failed: " + ex.getStatusCode());
    }

    /** Timeout or connection refused -> HTTP 503. */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> downstreamUnreachable(ResourceAccessException ex) {
        log.error("Inventory service unreachable: {}", ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Inventory service unreachable or timed out");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", status.value(), "error", message));
    }
}
