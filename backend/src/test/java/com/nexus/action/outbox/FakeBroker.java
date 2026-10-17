package com.nexus.action.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** A scriptable stand-in for the broker publisher that records every call, byte for byte, and the per-topic concurrency. */
public final class FakeBroker implements BrokerCommandPublisher {
    public record Call(String topic, byte[] bytes) {
        public String text() { return new String(bytes, StandardCharsets.UTF_8); }
    }

    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();
    final Map<String, AtomicInteger> maxActive = new ConcurrentHashMap<>();
    public volatile BiFunction<String, byte[], Attempt> behaviour = (topic, bytes) -> Attempt.confirmed();

    @Override
    public Attempt publish(String topic, byte[] payload, Duration timeout) {
        int now = active.computeIfAbsent(topic, t -> new AtomicInteger()).incrementAndGet();
        maxActive.computeIfAbsent(topic, t -> new AtomicInteger()).accumulateAndGet(now, Math::max);
        calls.add(new Call(topic, payload.clone()));
        try {
            return behaviour.apply(topic, payload);
        } finally {
            active.get(topic).decrementAndGet();
        }
    }

    /** Clears the call log and restores the default behaviour (confirm everything). */
    public void reset() {
        calls.clear();
        behaviour = (topic, bytes) -> Attempt.confirmed();
    }

    int maxConcurrencyOnAnyTopic() {
        return maxActive.values().stream().mapToInt(AtomicInteger::get).max().orElse(0);
    }

    long callsFor(String commandId) {
        synchronized (calls) {
            return calls.stream().filter(c -> c.text().contains(commandId)).count();
        }
    }

    static ObjectProvider<OutboxOutcomeListener> listenerProvider(OutboxOutcomeListener... listeners) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        int i = 0;
        for (OutboxOutcomeListener l : listeners) bf.registerSingleton("listener" + i++, l);
        return bf.getBeanProvider(OutboxOutcomeListener.class);
    }

    static ObjectProvider<MeterRegistry> noMeters() {
        return new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class);
    }

    static CommandOutboxSettings settings(int workers, Duration attemptTimeout, Duration lease) {
        return settings(workers, attemptTimeout, lease, 10);
    }

    static CommandOutboxSettings settings(int workers, Duration attemptTimeout, Duration lease, int batch) {
        return settings(true, workers, attemptTimeout, lease, batch);
    }

    /** @param enabled the single authoritative flag; the dispatcher never consults it, so false models a rolled-back system */
    static CommandOutboxSettings settings(boolean enabled, int workers, Duration attemptTimeout, Duration lease, int batch) {
        return new CommandOutboxSettings(enabled, Duration.ofSeconds(15), 3, attemptTimeout, Duration.ofMillis(500), 2.0, Duration.ofSeconds(4),
                0.2, lease, batch, Duration.ofMillis(250), Duration.ofSeconds(1), workers);
    }

    /** Counts reactions; optionally throws to prove a reaction failure never changes a committed outcome. */
    static final class RecordingListener implements OutboxOutcomeListener {
        final List<OutboxDeliveryOperations.Resolution> published = Collections.synchronizedList(new ArrayList<>());
        final List<OutboxDeliveryOperations.Resolution> deadLettered = Collections.synchronizedList(new ArrayList<>());
        final List<OutboxDeliveryOperations.Held> held = Collections.synchronizedList(new ArrayList<>());
        volatile boolean failing;

        @Override public void published(OutboxDeliveryOperations.Resolution r) { published.add(r); if (failing) throw new IllegalStateException("reaction failed"); }
        @Override public void deadLettered(OutboxDeliveryOperations.Resolution r) { deadLettered.add(r); if (failing) throw new IllegalStateException("reaction failed"); }
        @Override public void held(OutboxDeliveryOperations.Held h) { held.add(h); }
    }
}
