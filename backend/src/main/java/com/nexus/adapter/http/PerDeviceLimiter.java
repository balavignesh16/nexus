package com.nexus.adapter.http;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** At most N requests in flight per device (adapter-contract.md section 9.6): the in-application limit protects the core, not the network. */
final class PerDeviceLimiter {
    private final int limit;
    private final ConcurrentHashMap<UUID, Semaphore> permits = new ConcurrentHashMap<>();

    PerDeviceLimiter(int limit) {
        this.limit = limit;
    }

    boolean tryAcquire(UUID device) {
        return permits.computeIfAbsent(device, d -> new Semaphore(limit)).tryAcquire();
    }

    void release(UUID device) {
        Semaphore semaphore = permits.get(device);
        if (semaphore == null) return;
        semaphore.release();
        if (semaphore.availablePermits() == limit) permits.remove(device, semaphore);
    }

    int tracked() {
        return permits.size();
    }
}
