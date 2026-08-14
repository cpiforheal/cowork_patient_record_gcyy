package com.coshare.patientrecord.ai.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AiCallGuard {

    private final Semaphore permits;
    private final int maxPermits;
    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong rejectedCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private volatile Instant circuitOpenUntil = Instant.EPOCH;

    public AiCallGuard(
        @Value("${clinic.ai.max-concurrent-calls:4}") int maxConcurrentCalls,
        @Value("${clinic.ai.circuit-breaker.failure-threshold:5}") int failureThreshold,
        @Value("${clinic.ai.circuit-breaker.open-seconds:30}") long openSeconds
    ) {
        this.maxPermits = Math.max(1, maxConcurrentCalls);
        this.permits = new Semaphore(maxPermits, true);
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = Duration.ofSeconds(Math.max(1, openSeconds));
    }

    public <T> T execute(CheckedSupplier<T> supplier) throws IOException, InterruptedException {
        if (Instant.now().isBefore(circuitOpenUntil)) {
            rejectedCalls.incrementAndGet();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务熔断中，请稍后重试");
        }
        if (!permits.tryAcquire()) {
            rejectedCalls.incrementAndGet();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI 任务繁忙，请稍后重试");
        }
        try {
            T result = supplier.get();
            consecutiveFailures.set(0);
            circuitOpenUntil = Instant.EPOCH;
            return result;
        } catch (IOException | InterruptedException error) {
            failedCalls.incrementAndGet();
            if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
                circuitOpenUntil = Instant.now().plus(openDuration);
            }
            throw error;
        } finally {
            permits.release();
        }
    }

    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeCalls", Math.max(0, maxPermits - permits.availablePermits()));
        result.put("availablePermits", permits.availablePermits());
        result.put("consecutiveFailures", consecutiveFailures.get());
        result.put("failedCalls", failedCalls.get());
        result.put("rejectedCalls", rejectedCalls.get());
        result.put("circuitOpen", Instant.now().isBefore(circuitOpenUntil));
        return result;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws IOException, InterruptedException;
    }
}
