package com.nexus.intelligence.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounds every model call: a bulkhead (at most N calls in flight, the permit is held until the task has really finished, even after its caller timed out),
 * a per-attempt timeout, bounded retries for transient provider errors only, and a circuit breaker that opens after consecutive failures and half-opens
 * after a cool-down. A failure here never propagates as an exception: the caller gets a {@link RefusalCode}.
 */
public final class AiCallGuard implements AutoCloseable {
    public record Result(String text, RefusalCode refusal) {
        static Result ok(String text) {
            return new Result(text, null);
        }

        static Result refused(RefusalCode code) {
            return new Result(null, code);
        }
    }

    private final GuardSettings settings;
    private final Clock clock;
    private final Semaphore permits;
    private final ExecutorService executor;

    private int consecutiveFailures;
    private Instant openUntil;
    private boolean probing;

    public AiCallGuard(GuardSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.permits = new Semaphore(settings.maxConcurrent());
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "nexus-ai-call");
            t.setDaemon(true);
            return t;
        });
    }

    public Result call(Callable<String> task) {
        if (!admit()) return Result.refused(RefusalCode.CIRCUIT_OPEN);
        RefusalCode last = RefusalCode.PROVIDER_ERROR;
        for (int attempt = 0; attempt <= settings.maxRetries(); attempt++) {
            if (!permits.tryAcquire()) {
                release(false, true);
                return Result.refused(RefusalCode.BULKHEAD_FULL);
            }
            Future<String> future;
            try {
                future = executor.submit(() -> {
                    try {
                        return task.call();
                    } finally {
                        permits.release();
                    }
                });
            } catch (RuntimeException e) {
                permits.release();
                release(false, false);
                return Result.refused(RefusalCode.PROVIDER_ERROR);
            }
            try {
                String text = future.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
                release(true, false);
                return Result.ok(text);
            } catch (TimeoutException e) {
                future.cancel(true);
                last = RefusalCode.TIMEOUT;
                break; // a slow provider is not retried: it would only hold more capacity
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                release(false, false);
                return Result.refused(RefusalCode.PROVIDER_ERROR);
            } catch (ExecutionException e) {
                last = RefusalCode.PROVIDER_ERROR;
                boolean transientFailure = e.getCause() instanceof ModelProviderException m && m.transientFailure();
                if (!transientFailure) break;
            }
        }
        release(false, false);
        return Result.refused(last);
    }

    private synchronized boolean admit() {
        if (openUntil == null) return true;
        if (clock.instant().isBefore(openUntil)) return false;
        if (probing) return false;
        probing = true; // half-open: exactly one probe call
        return true;
    }

    private synchronized void release(boolean success, boolean neutral) {
        if (neutral) {
            probing = false;
            return;
        }
        if (success) {
            consecutiveFailures = 0;
            openUntil = null;
            probing = false;
            return;
        }
        consecutiveFailures++;
        if (probing || consecutiveFailures >= settings.breakerFailureThreshold()) {
            openUntil = clock.instant().plus(settings.breakerOpenFor());
        }
        probing = false;
    }

    public synchronized boolean circuitOpen() {
        return openUntil != null && clock.instant().isBefore(openUntil);
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    Duration timeout() {
        return settings.timeout();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
