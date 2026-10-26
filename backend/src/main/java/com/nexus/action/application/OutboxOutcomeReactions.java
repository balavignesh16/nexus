package com.nexus.action.application;

import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.outbox.OutboxDeliveryOperations;
import com.nexus.action.outbox.OutboxDeliveryOperations.Resolution;
import com.nexus.action.outbox.OutboxOutcomeListener;
import com.nexus.action.outbox.OutboxTerminalReason;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.twin.application.VerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * What the rest of the application does once the dispatcher has COMMITTED a delivery outcome. It mirrors, for outbox
 * commands, the reactions the legacy coordinator performs inline after its publish:
 * <ul>
 * <li>published: the realtime ActionExecuted message and a pipeline trace carry SUCCESS (broker publication only;
 *     never an ACK, never physical effect);</li>
 * <li>dead-lettered with a PROVEN non-publication (or expiry / integrity refusal): the Twin verification service is told
 *     ONCE ({@code commandFailed}), the COMMAND_PUBLICATION_FAILED rule fact is emitted ONCE and only when this outcome
 *     actually changed ActionHistory, and the realtime message and trace carry FAILURE;</li>
 * <li>dead-lettered UNCERTAIN (the command may already have reached, or may still reach, the device): NO definitive failure
 *     signal at all. No COMMAND_PUBLICATION_FAILED fact (a rule could otherwise issue a compensating command for a command that
 *     may execute), no Twin {@code commandFailed} (the verification keeps its own deadline), and the realtime message and trace
 *     say UNCERTAIN;</li>
 * <li>a device ACK recorded before the outcome suppresses every failure signal, since the device demonstrably received the
 *     command. Nothing is retried or resent by any reaction.</li>
 * </ul>
 * The device row lock taken by {@code commandFailed} happens here, after and outside the dispatcher's transaction, so the
 * dispatcher itself never locks a device row (contract R31.9).
 */
@Component
public class OutboxOutcomeReactions implements OutboxOutcomeListener {
    private static final Logger log = LoggerFactory.getLogger(OutboxOutcomeReactions.class);

    private final ActionHistoryRepository actions;
    private final RealtimePublisher realtime;
    private final PipelineService pipeline;
    private VerificationService verification;
    private com.nexus.rule.trigger.RuleSourceFacts ruleFacts;
    private PlatformTransactionManager transactions;

    @Autowired
    public OutboxOutcomeReactions(ActionHistoryRepository actions, RealtimePublisher realtime, PipelineService pipeline) {
        this.actions = actions;
        this.realtime = realtime;
        this.pipeline = pipeline;
    }

    @Autowired
    void setVerification(VerificationService verification) { this.verification = verification; }

    @Autowired
    void setRuleFacts(com.nexus.rule.trigger.RuleSourceFacts ruleFacts, PlatformTransactionManager transactions) {
        this.ruleFacts = ruleFacts;
        this.transactions = transactions;
    }

    @Override
    public void published(Resolution r) {
        broadcast(r);
    }

    @Override
    public void held(OutboxDeliveryOperations.Held held) {
        try {
            pipeline.recordTrace(PipelineTrace.of(held.correlationId() != null ? held.correlationId() : held.commandId().toString(),
                    held.deviceId() == null ? null : held.deviceId().toString(), PipelineStage.ACTION, System.currentTimeMillis(), "HELD",
                    "HELD_UNCERTAIN_PREDECESSOR", java.util.Map.of("holdUntil", held.holdUntil().toString())));
        } catch (RuntimeException e) {
            log.warn("Pipeline trace for the hold of command {} could not be recorded", held.commandId(), e);
        }
    }

    @Override
    public void deadLettered(Resolution r) {
        if (r.uncertain() || r.ackRecorded()) {
            // uncertainty (or an acknowledged command) is never reported as a definite publication failure
            broadcast(r);
            return;
        }
        String reason = "MQTT_PUBLICATION_FAILED";
        if (r.actionHistoryUpdated() && ruleFacts != null && transactions != null) {
            // The ActionHistory failure committed by the dispatcher is the authoritative fact. The derived rule fact is committed
            // on its own; if that fails no rule can fire (fail closed) and nothing is resent.
            try {
                new TransactionTemplate(transactions).executeWithoutResult(tx ->
                        ruleFacts.publicationFailed(r.commandId(), r.deviceId(), r.at(), r.message()));
            } catch (RuntimeException failure) {
                log.error("RULE_SOURCE_FACT_FAILED publication-failure command={}", r.commandId(), failure);
            }
        }
        if (verification != null) {
            try {
                verification.commandFailed(r.deviceId(), r.commandId(), reason);
            } catch (RuntimeException failure) {
                log.error("Twin verification could not be told that command {} failed", r.commandId(), failure);
            }
        }
        broadcast(r);
    }

    /**
     * Crash recovery: re-applies ONLY the two durable signals (no realtime message, no trace, which the normal reaction already sent or
     * never will). Both are idempotent: the rule fact is unique per command (ON CONFLICT DO NOTHING) and the Twin only moves a PENDING
     * verification to UNVERIFIED. An uncertain or acknowledged outcome never reaches this method (the query excludes them) and is refused
     * again here as a second guard.
     */
    @Override
    public void recoverFailureSignals(Resolution r, boolean twinPending) {
        if (r.uncertain() || r.ackRecorded()) return;
        if (ruleFacts != null && transactions != null) {
            try {
                new TransactionTemplate(transactions).executeWithoutResult(tx ->
                        ruleFacts.publicationFailed(r.commandId(), r.deviceId(), r.at(), r.message()));
            } catch (RuntimeException failure) {
                log.error("RULE_SOURCE_FACT_FAILED recovered publication-failure command={}", r.commandId(), failure);
            }
        }
        if (verification != null && twinPending) {          // commandFailed locks the device row: only when the Twin still needs telling
            try {
                verification.commandFailed(r.deviceId(), r.commandId(), "MQTT_PUBLICATION_FAILED");
            } catch (RuntimeException failure) {
                log.error("Twin verification could not be told (recovery) that command {} failed", r.commandId(), failure);
            }
        }
    }

    /**
     * The realtime ActionExecuted message. The pipeline trace of the outcome is NOT written here any more: the durable, idempotent and
     * crash-recoverable trace of every lifecycle fact is projected from the state history by {@code CommandTraceProjector} (contract
     * R37, P4-095), so an outcome whose process died right after commit still has its trace.
     */
    private void broadcast(Resolution r) {
        Optional<ActionHistoryRecord> record = actions.findById(r.commandId());
        if (record.isEmpty()) return;
        ActionExecutionResult result = record.get().toResult();
        String correlation = r.correlationId() != null ? r.correlationId() : r.commandId().toString();
        realtime.publishActionExecuted(result, correlation);
    }
}
