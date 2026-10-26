package com.nexus.adapter.spi;

/** Owns the adapter's connection or listener. Both calls are idempotent; neither throws for an already-started or already-stopped adapter. */
public interface AdapterLifecycle {
    void start();

    void stop();
}
