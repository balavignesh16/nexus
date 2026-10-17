package com.nexus.device.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.action.outbox.CommandAckFacts;
import com.nexus.realtime.RealtimePublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The backend receipt time of an ACK is the DATABASE clock (ledger scenario "the ACK deadline and the receipt time share one clock"):
 * with an application clock that is wrong by a day, the instant stored with the ACK and used for lateness is still the database's.
 */
class DeviceCommandAckMessageHandlerClockTest {

    @Test
    void theBackendReceiptTimeStoredWithAnAckComesFromTheDatabaseClockNotTheApplicationClock() {
        UUID device = UUID.randomUUID(), command = UUID.randomUUID();
        Instant databaseNow = Instant.parse("2026-10-18T10:00:00.123456Z");
        Clock applicationClockADayAhead = Clock.fixed(databaseNow.plusSeconds(86_400), ZoneOffset.UTC);

        ActionHistoryRepository repository = mock(ActionHistoryRepository.class);
        ActionHistoryRecord record = new ActionHistoryRecord(command, null, device, ActionType.SEND_COMMAND, ExecutionStatus.SUCCESS,
                Map.of("deviceId", device.toString()), databaseNow, "published");
        when(repository.findById(command)).thenReturn(Optional.of(record));
        when(repository.updateAcknowledgement(any(), any(), any(), any(), any(), any())).thenReturn(1);
        CommandAckFacts facts = mock(CommandAckFacts.class);
        when(facts.databaseNow()).thenReturn(databaseNow);
        when(facts.snapshot(command)).thenReturn(new CommandAckFacts.Snapshot("PUBLISHED", 1, false, null, 0));

        DeviceCommandAckMessageHandler handler = new DeviceCommandAckMessageHandler(repository, mock(RealtimePublisher.class), new ObjectMapper(),
                applicationClockADayAhead);
        handler.setAckFacts(facts);
        String json = "{\"commandId\":\"" + command + "\",\"status\":\"ACCEPTED\",\"timestamp\":" + databaseNow.getEpochSecond() + "}";
        handler.handle("nexus/devices/" + device + "/commands/ack", json.getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<Instant> backendAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).updateAcknowledgement(any(), any(), any(), any(), backendAt.capture(), any());
        assertEquals(databaseNow, backendAt.getValue(), "the receipt time is the database clock, whatever the application clock says");
        assertNotEquals(applicationClockADayAhead.instant(), backendAt.getValue());
        verify(facts).appendFirstAck(any(), org.mockito.ArgumentMatchers.eq(false), any(), any(), org.mockito.ArgumentMatchers.eq(databaseNow), anyString());
    }

    @Test
    void withoutTheFactsComponentTheLegacyBehaviourAndTheApplicationClockApplyUnchanged() {
        UUID device = UUID.randomUUID(), command = UUID.randomUUID();
        Instant app = Instant.parse("2026-10-18T10:00:00Z");
        ActionHistoryRepository repository = mock(ActionHistoryRepository.class);
        ActionHistoryRecord record = new ActionHistoryRecord(command, null, device, ActionType.SEND_COMMAND, ExecutionStatus.SUCCESS,
                Map.of("deviceId", device.toString()), app, "published");
        when(repository.findById(command)).thenReturn(Optional.of(record));
        when(repository.updateAcknowledgement(any(), any(), any(), any(), any(), any())).thenReturn(1);
        DeviceCommandAckMessageHandler handler = new DeviceCommandAckMessageHandler(repository, mock(RealtimePublisher.class), new ObjectMapper(),
                Clock.fixed(app, ZoneOffset.UTC));
        String json = "{\"commandId\":\"" + command + "\",\"status\":\"REJECTED\",\"timestamp\":" + app.getEpochSecond() + ",\"reason\":\"x\"}";
        handler.handle("nexus/devices/" + device + "/commands/ack", json.getBytes(StandardCharsets.UTF_8));
        verify(repository).updateAcknowledgement(command, device, AcknowledgementStatus.REJECTED, Instant.ofEpochSecond(app.getEpochSecond()), app, "x");
    }
}
