package com.bramhastra.baton.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Opens after N consecutive failures and stays open for a cool-down (H9: 4 failures, 60 s). */
public class CircuitBreaker {

    private final int threshold;
    private final Duration coolDown;
    private final Clock clock;
    private int failures;
    private Instant openUntil = Instant.MIN;

    public CircuitBreaker(int threshold, Duration coolDown, Clock clock) {
        this.threshold = threshold;
        this.coolDown = coolDown;
        this.clock = clock;
    }

    public synchronized void check() {
        if (clock.instant().isBefore(openUntil)) {
            throw new LlmClient.LlmUnavailableException("AI service is paused after repeated failures; retry shortly");
        }
    }

    public synchronized void recordSuccess() {
        failures = 0;
    }

    public synchronized void recordFailure() {
        if (++failures >= threshold) {
            openUntil = clock.instant().plus(coolDown);
            failures = 0;
        }
    }

    public synchronized boolean isOpen() {
        return clock.instant().isBefore(openUntil);
    }
}
