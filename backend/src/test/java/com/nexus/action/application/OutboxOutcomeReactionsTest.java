package com.nexus.action.application;

import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.action.outbox.OutboxDeliveryOperations.Disposition;
import com.nexus.action.outbox.OutboxDeliveryOperations.Resolution;
import com.nexus.action.outbox.OutboxTerminalReason;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.rule.trigger.RuleSourceFacts;
import com.nexus.twin.application.VerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens AFTER the dispatcher committed an outcome: the twin is told once and the COMMAND_PUBLICATION_FAILED rule fact
 * is emitted once, only when this outcome changed ActionHistory; a publication produces neither; a failing reaction never
 * escapes into the committed outcome.
 */
class OutboxOutcomeReactionsTest {
    private final ActionHistoryRepository actions = mock(ActionHistoryRepository.class);
    private final RealtimePublisher realtime = mock(RealtimePublisher.class);
    private final PipelineService pipeline = mock(PipelineService.class);
    private final VerificationService verification = mock(VerificationService.class);
    private final RuleSourceFacts ruleFacts = mock(RuleSourceFacts.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final OutboxOutcomeReactions reactions = new OutboxOutcomeReactions(actions, realtime, pipeline);
    private final UUID command = UUID.randomUUID(), device = UUID.randomUUID();

    @BeforeEach
    void wire() {
        reactions.setVerification(verification);
        reactions.setRuleFacts(ruleFacts, transactions);
        ActionHistoryRecord record = new ActionHistoryRecord(command, null, device, ActionType.SEND_COMMAND, ExecutionStatus.FAILURE,
                Map.of("deviceId", device.toString()), Instant.now(), "message");
        when(actions.findById(command)).thenReturn(Optional.of(record));
    }

    private Resolution deadLetter(OutboxTerminalReason reason, boolean actionHistoryUpdated) {
        return new Resolution(Disposition.DEAD_LETTERED, command, device, reason, "CODE", "message", Instant.now(), actionHistoryUpdated, "corr");
    }

    @Test
    void aDeadLetterTellsTheTwinOnceEmitsTheRuleFactOnceAndBroadcastsAFailure() {
        reactions.deadLettered(deadLetter(OutboxTerminalReason.PERMANENT_FAILURE, true));
        verify(verification).commandFailed(device, command, "MQTT_PUBLICATION_FAILED");
        verify(ruleFacts).publicationFailed(eq(command), eq(device), any(Instant.class), anyString());
        ArgumentCaptor<com.nexus.action.domain.ActionExecutionResult> result = ArgumentCaptor.forClass(com.nexus.action.domain.ActionExecutionResult.class);
        verify(realtime).publishActionExecuted(result.capture(), eq("corr"));
        assertEquals(ExecutionStatus.FAILURE, result.getValue().status());
    }

    @Test
    void anUncertainDeadLetterIsNeverReportedAsADefinitePublicationFailure() {
        reactions.deadLettered(deadLetter(OutboxTerminalReason.UNCERTAIN_EXPIRED, true));
        verify(verification, never()).commandFailed(any(), any(), any());
        verify(ruleFacts, never()).publicationFailed(any(), any(), any(), any());
        ArgumentCaptor<com.nexus.pipeline.domain.PipelineTrace> trace = ArgumentCaptor.forClass(com.nexus.pipeline.domain.PipelineTrace.class);
        verify(pipeline).recordTrace(trace.capture());
        assertEquals("UNCERTAIN", trace.getValue().getStatus(), "the uncertainty stays visible in the pipeline trace");
        verify(realtime).publishActionExecuted(any(), eq("corr"));
    }

    @Test
    void aDeviceThatAlreadyAcknowledgedTheCommandSuppressesEveryFailureSignal() {
        Resolution acknowledged = new Resolution(Disposition.DEAD_LETTERED, command, device, OutboxTerminalReason.PERMANENT_FAILURE, "CODE", "message",
                Instant.now(), true, "corr", true);
        reactions.deadLettered(acknowledged);
        verify(verification, never()).commandFailed(any(), any(), any());
        verify(ruleFacts, never()).publicationFailed(any(), any(), any(), any());
    }

    @Test
    void recoveryReAppliesTheTwoDurableSignalsForARetryExhaustedDeadLetterWithoutBroadcastingAgain() {
        reactions.recoverFailureSignals(deadLetter(OutboxTerminalReason.RETRY_EXHAUSTED, true), true);
        verify(verification).commandFailed(device, command, "MQTT_PUBLICATION_FAILED");
        verify(ruleFacts).publicationFailed(eq(command), eq(device), any(Instant.class), anyString());
        verify(realtime, never()).publishActionExecuted(any(), anyString());
        verify(pipeline, never()).recordTrace(any());
    }

    @Test
    void recoveryRefusesAnUncertainOrAcknowledgedOutcomeAsASecondGuardBesideTheQuery() {
        reactions.recoverFailureSignals(deadLetter(OutboxTerminalReason.UNCERTAIN_EXPIRED, true), true);
        reactions.recoverFailureSignals(new Resolution(Disposition.DEAD_LETTERED, command, device, OutboxTerminalReason.RETRY_EXHAUSTED, "CODE", "message",
                Instant.now(), true, "corr", true), true);
        verify(verification, never()).commandFailed(any(), any(), any());
        verify(ruleFacts, never()).publicationFailed(any(), any(), any(), any());
    }

    @Test
    void recoveryDoesNotLockTheDeviceRowWhenTheTwinNeedsNoTelling() {
        reactions.recoverFailureSignals(deadLetter(OutboxTerminalReason.RETRY_EXHAUSTED, true), false);
        verify(ruleFacts).publicationFailed(eq(command), eq(device), any(Instant.class), anyString());
        verify(verification, never()).commandFailed(any(), any(), any());
    }

    @Test
    void aFailingRecoveryNeverEscapesAndTheOtherSignalIsStillApplied() {
        doThrow(new IllegalStateException("rule fact failed")).when(ruleFacts).publicationFailed(any(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> reactions.recoverFailureSignals(deadLetter(OutboxTerminalReason.EXPIRED, true), true));
        verify(verification).commandFailed(device, command, "MQTT_PUBLICATION_FAILED");
    }

    @Test
    void aHeldCommandIsTracedAsHeldWithItsHoldEnd() {
        Instant until = Instant.now().plusSeconds(20);
        reactions.held(new com.nexus.action.outbox.OutboxDeliveryOperations.Held(command, device, "corr", until));
        ArgumentCaptor<com.nexus.pipeline.domain.PipelineTrace> trace = ArgumentCaptor.forClass(com.nexus.pipeline.domain.PipelineTrace.class);
        verify(pipeline).recordTrace(trace.capture());
        assertEquals("HELD", trace.getValue().getStatus());
        assertEquals(until.toString(), trace.getValue().getMetadata().get("holdUntil"));
    }

    @Test
    void noRuleFactIsEmittedWhenTheOutcomeDidNotChangeActionHistory() {
        reactions.deadLettered(deadLetter(OutboxTerminalReason.EXPIRED, false));
        verify(ruleFacts, never()).publicationFailed(any(), any(), any(), any());
        verify(verification).commandFailed(device, command, "MQTT_PUBLICATION_FAILED");
    }

    @Test
    void aPublicationNeitherTellsTheTwinItFailedNorEmitsAFailureFact() {
        reactions.published(new Resolution(Disposition.PUBLISHED, command, device, null, null, "Command published to MQTT", Instant.now(), true, "corr"));
        verify(verification, never()).commandFailed(any(), any(), any());
        verify(ruleFacts, never()).publicationFailed(any(), any(), any(), any());
        verify(realtime).publishActionExecuted(any(), eq("corr"));
    }

    @Test
    void aFailingDerivedReactionNeverEscapesOrPreventsTheOthers() {
        doThrow(new IllegalStateException("rule storage down")).when(ruleFacts).publicationFailed(any(), any(), any(), any());
        doThrow(new IllegalStateException("twin down")).when(verification).commandFailed(any(), any(), any());
        assertDoesNotThrow(() -> reactions.deadLettered(deadLetter(OutboxTerminalReason.PERMANENT_FAILURE, true)));
        verify(realtime).publishActionExecuted(any(), any());
    }
}
