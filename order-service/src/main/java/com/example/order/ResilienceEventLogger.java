package com.example.order;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Prints Resilience4j events to the console so you can SEE the patterns working during a demo:
 * retry attempts, circuit breaker state changes (CLOSED -> OPEN -> HALF_OPEN) and rate-limit rejections.
 */
@Component
@Profile("protected")
public class ResilienceEventLogger {

    private static final Logger log = LoggerFactory.getLogger(ResilienceEventLogger.class);

    public ResilienceEventLogger(CircuitBreakerRegistry circuitBreakers,
                                 RetryRegistry retries,
                                 RateLimiterRegistry rateLimiters) {

        // CIRCUIT BREAKER state transitions
        circuitBreakers.circuitBreaker("inventory").getEventPublisher()
                .onStateTransition(event -> log.warn(">>> CIRCUIT BREAKER: {}",
                        event.getStateTransition())); // e.g. CLOSED_TO_OPEN

        // RETRY attempts (fires before each retry wait) and final give-up
        retries.retry("inventory").getEventPublisher()
                .onRetry(event -> log.warn(">>> RETRY attempt #{} after error: {} (waiting {} ms)",
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable().getClass().getSimpleName(),
                        event.getWaitInterval().toMillis()))
                .onError(event -> log.error(">>> RETRY gave up after {} attempts",
                        event.getNumberOfRetryAttempts()));

        // RATE LIMITER rejections
        rateLimiters.rateLimiter("inventory").getEventPublisher()
                .onFailure(event -> log.warn(">>> RATE LIMITER rejected a request"));
    }
}
