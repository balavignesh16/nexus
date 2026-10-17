package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.AttemptResult;
import com.nexus.action.outbox.OutboxDeliveryOperations.Claimed;
import com.nexus.action.outbox.OutboxDeliveryOperations.Resolution;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single publisher of outbox commands (contract R07 to R12). It is ALWAYS a bean, whatever the flag says, and it never
 * consults the flag: the flag only decides whether acceptance CREATES new obligations ({@link CommandOutboxSettings#enabled()},
 * the single authoritative setting). Existing obligations are serviced (delivered, expired or recovered) whether the flag is on
 * or has been turned off again, so enabling can never create work nobody services and rolling back is drain-only (contract R22).
 * An idle system costs one indexed EXISTS probe per poll.
 *
 * One pass: expire due rows and recover expired leases (so nothing sits at the head of a device queue forever), then claim
 * as many eligible commands as there are free workers, each claim in its own short transaction, and publish each on a
 * worker thread OUTSIDE any transaction with the bounded publisher. The outcome is written by a fenced transaction.
 *
 * Safety properties, each proven by tests:
 * <ul>
 * <li>The dispatcher never authorises, validates or re-evaluates connectivity (R08): a durably accepted command is
 *     delivered or dead-lettered, nothing else.</li>
 * <li>The payload bytes are verified against the stored hash before every attempt and published verbatim; the stored
 *     timestamp is never refreshed.</li>
 * <li>No attempt starts for a row at or after {@code expires_at} (database clock).</li>
 * <li>Until retries exist (P4.3) there is exactly one attempt per command; a failed attempt is dead-lettered with an
 *     honest reason. Nothing is re-claimed, so a failure cannot cause repeated publication.</li>
 * <li>A worker that crashes or stalls leaves an IN_FLIGHT row; lease recovery turns it into an uncertain dead letter.</li>
 * </ul>
 * This class does NOT claim strict per-device FIFO or exactly-once publication; see the contract (R03, R09).
 */
@Component
public class CommandOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(CommandOutboxDispatcher.class);
    private static final int SWEEP_BATCH = 50;

    private final OutboxDeliveryOperations operations;
    private final BrokerCommandPublisher publisher;
    private final CommandOutboxSettings settings;
    private final ObjectProvider<OutboxOutcomeListener> listeners;
    private final MeterRegistry meters;
    private final boolean scheduledLoops;
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private final AtomicInteger claimSequence = new AtomicInteger();
    private final Semaphore freeWorkers;
    private final ExecutorService workers;
    private final ExecutorService claimer;
    private final AtomicBoolean claimPassRunning = new AtomicBoolean();

    /** Convenience for tests: the scheduled loops are on. */
    public CommandOutboxDispatcher(OutboxDeliveryOperations operations, BrokerCommandPublisher publisher, CommandOutboxSettings settings,
                                   ObjectProvider<OutboxOutcomeListener> listeners, ObjectProvider<MeterRegistry> meters) {
        this(operations, publisher, settings, listeners, meters, true);
    }

    /**
     * @param scheduledLoops {@code nexus.command.outbox.dispatcher-enabled} (default true). It switches the SCHEDULED loops and the
     *     after-commit nudge off for contexts that fabricate unresolved obligations (tests), and it cannot be false while the
     *     outbox is enabled: that would create obligations nobody services, so startup fails instead.
     */
    @Autowired
    public CommandOutboxDispatcher(OutboxDeliveryOperations operations, BrokerCommandPublisher publisher, CommandOutboxSettings settings,
                                   ObjectProvider<OutboxOutcomeListener> listeners, ObjectProvider<MeterRegistry> meters,
                                   @org.springframework.beans.factory.annotation.Value("${nexus.command.outbox.dispatcher-enabled:true}") boolean scheduledLoops) {
        if (!scheduledLoops && settings.enabled())
            throw new IllegalStateException("nexus.command.outbox.dispatcher-enabled=false is not allowed while nexus.command.outbox.enabled=true:"
                    + " accepted commands would never be delivered, expired or recovered");
        this.scheduledLoops = scheduledLoops;
        this.operations = operations;
        this.publisher = publisher;
        this.settings = settings;
        this.listeners = listeners;
        this.meters = meters.getIfAvailable();
        this.freeWorkers = new Semaphore(settings.workers());
        this.workers = Executors.newFixedThreadPool(settings.workers(), threads("outbox-publish"));
        this.claimer = Executors.newSingleThreadExecutor(threads("outbox-claim"));
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdown() {
        claimer.shutdownNow();
        workers.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${nexus.command.outbox.poll-interval:PT0.25S}")
    public void poll() {
        if (scheduledLoops && anyWork()) runPass();
    }

    @Scheduled(fixedDelayString = "${nexus.command.outbox.expiry-scan-interval:PT1S}")
    public void sweep() {
        if (scheduledLoops && anyWork()) sweepOnce();
    }

    private final AtomicInteger probeFailures = new AtomicInteger();

    private boolean anyWork() {
        try {
            int failed = probeFailures.get();
            boolean work = operations.hasWork();
            if (failed > 0) probeFailures.compareAndSet(failed, 0);
            return work;
        } catch (RuntimeException e) {
            // a database outage repeats this every poll: log the first failure and then one in every 120 (about every 30 s)
            if (probeFailures.getAndIncrement() % 120 == 0) log.error("Outbox work probe failed (repeated while the failure lasts)", e);
            count("probe_error");
            return false;
        }
    }

    /** After-commit nudge from acceptance: only triggers the normal claim pass, it can never bypass it. */
    public void nudge() {
        if (!scheduledLoops || claimPassRunning.get()) return;
        try {
            claimer.execute(this::runPass);
        } catch (RuntimeException ignored) {
            // shutting down; the scheduled poll or the next start recovers
        }
    }

    /** Expiry and lease recovery. Returns the number of commands resolved. */
    public int sweepOnce() {
        int resolved = 0;
        try {
            for (Resolution r : operations.recoverExpiredLeases(SWEEP_BATCH)) { resolved++; react(r); }
            for (Resolution r : operations.expireDue(SWEEP_BATCH)) { resolved++; react(r); }
            for (OutboxDeliveryOperations.Held h : operations.markHeld(SWEEP_BATCH)) listeners.stream().forEach(l -> {
                try { l.held(h); } catch (RuntimeException e) { log.error("Post-commit hold reaction for command {} failed", h.commandId(), e); }
            });
        } catch (RuntimeException e) {
            log.error("Outbox sweep failed; it is repeated on the next scan", e);
            count("sweep_error");
        }
        return resolved;
    }

    /** One claim pass. Returns the number of commands claimed (published asynchronously on the worker pool). */
    public int runPass() {
        if (!claimPassRunning.compareAndSet(false, true)) return 0;
        int claimed = 0;
        try {
            sweepOnce();
            if (freeWorkers.availablePermits() == 0) return 0;
            List<UUID> candidates = operations.candidates(Math.min(settings.claimBatchSize(), freeWorkers.availablePermits()));
            for (UUID id : candidates) {
                if (!freeWorkers.tryAcquire()) break;
                boolean handedOver = false;
                try {
                    var claim = operations.claim(id, instanceId + "/" + claimSequence.incrementAndGet(), settings.leaseDuration());
                    if (claim.isEmpty()) continue;
                    claimed++;
                    Claimed c = claim.get();
                    workers.execute(() -> {
                        try { deliver(c); } finally { freeWorkers.release(); }
                    });
                    handedOver = true;
                } catch (RuntimeException e) {
                    log.error("Outbox claim failed for a candidate; it stays queued and is retried on the next pass", e);
                    count("claim_error");
                } finally {
                    if (!handedOver) freeWorkers.release();
                }
            }
        } catch (RuntimeException e) {
            log.error("Outbox claim pass failed", e);
            count("pass_error");
        } finally {
            claimPassRunning.set(false);
        }
        return claimed;
    }

    /** Publish (outside any transaction) and write the fenced outcome. Never throws. */
    void deliver(Claimed c) {
        try {
            AttemptResult result;
            String code = null;
            if (!c.payload().verify()) {
                result = AttemptResult.PAYLOAD_INTEGRITY;   // never published
            } else {
                BrokerCommandPublisher.Attempt attempt = publisher.publish(c.topic(), c.payload().bytes(), settings.attemptTimeout());
                code = attempt.code();
                result = switch (attempt.result()) {
                    case CONFIRMED -> AttemptResult.CONFIRMED;
                    case NOT_SENT -> AttemptResult.NOT_SENT;
                    case UNCERTAIN -> AttemptResult.UNCERTAIN;
                };
            }
            Resolution resolution = operations.complete(c, result, code);
            if (resolution.disposition() == OutboxDeliveryOperations.Disposition.FENCED) {
                log.warn("Outbox outcome for command {} attempt {} was fenced out; nothing was written", c.commandId(), c.attempt());
                count("fenced");
                return;
            }
            count(resolution.published() ? "published" : "dead_letter");
            react(resolution);
        } catch (Throwable e) {
            // The row stays IN_FLIGHT; lease recovery records it as an uncertain dead letter. Nothing is dropped silently.
            log.error("Outbox delivery of command {} failed before its outcome was recorded; lease recovery will resolve it", c.commandId(), e);
            count("deliver_error");
        }
    }

    private void react(Resolution r) {
        listeners.stream().forEach(l -> {
            try {
                if (r.published()) l.published(r); else l.deadLettered(r);
            } catch (RuntimeException e) {
                log.error("Post-commit reaction to the outbox outcome of command {} failed; the committed outcome stands", r.commandId(), e);
            }
        });
    }

    private void count(String outcome) {
        if (meters != null) meters.counter("nexus.command.outbox.dispatch", "outcome", outcome).increment();
    }
}
