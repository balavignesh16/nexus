package com.nexus.action.api;

import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.application.ActionExecutorRegistry;
import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ActionController's device-scoped history filter (Phase 8) -
 * the same "belongs to this device" correctness gap already closed for
 * /rules is closed here for /actions/history.
 */
class ActionControllerTest {

    private ActionHistoryRepository historyRepository;
    private ActionController controller;

    @BeforeEach
    void setUp() {
        historyRepository = mock(ActionHistoryRepository.class);
        ActionExecutorRegistry registry = mock(ActionExecutorRegistry.class);
        controller = new ActionController(historyRepository, registry);
    }

    private ActionHistoryRecord record(UUID deviceId, AcknowledgementStatus ackStatus) {
        ActionHistoryRecord record = new ActionHistoryRecord(
                UUID.randomUUID(), null, deviceId, ActionType.SEND_COMMAND, ExecutionStatus.SUCCESS,
                Map.of(), Instant.now(), "Published");
        if (ackStatus != null) {
            record.applyAcknowledgement(ackStatus, Instant.now(), "device ack");
        }
        return record;
    }

    @Test
    void getActionHistory_noDeviceIdFilter_usesFindAll() {
        when(historyRepository.findAllByOrderByTimestampDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record(UUID.randomUUID(), null))));

        ResponseEntity<List<ActionHistoryResponse>> response = controller.getActionHistory(100, null);

        assertEquals(1, response.getBody().size());
        verify(historyRepository).findAllByOrderByTimestampDesc(any(Pageable.class));
    }

    @Test
    void getActionHistory_deviceIdFilter_returnsOnlyThatDevicesActions() {
        UUID deviceA = UUID.randomUUID();
        when(historyRepository.findByDeviceIdOrderByTimestampDesc(eq(deviceA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record(deviceA, AcknowledgementStatus.ACCEPTED))));

        ResponseEntity<List<ActionHistoryResponse>> response = controller.getActionHistory(100, deviceA);

        assertEquals(1, response.getBody().size());
        assertEquals(deviceA, response.getBody().get(0).deviceId());
        verify(historyRepository).findByDeviceIdOrderByTimestampDesc(eq(deviceA), any(Pageable.class));
    }

    @Test
    void getActionHistory_deviceIdFilter_neverCallsFindAll() {
        UUID deviceA = UUID.randomUUID();
        when(historyRepository.findByDeviceIdOrderByTimestampDesc(eq(deviceA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.getActionHistory(100, deviceA);

        verify(historyRepository, org.mockito.Mockito.never()).findAllByOrderByTimestampDesc(any(Pageable.class));
    }

    @Test
    void getActionHistory_ackStatusNull_meansNoAckYetNotFailure() {
        UUID deviceA = UUID.randomUUID();
        when(historyRepository.findByDeviceIdOrderByTimestampDesc(eq(deviceA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record(deviceA, null))));

        ResponseEntity<List<ActionHistoryResponse>> response = controller.getActionHistory(100, deviceA);

        ActionHistoryResponse action = response.getBody().get(0);
        assertEquals(ExecutionStatus.SUCCESS, action.status());
        assertEquals(null, action.ackStatus());
    }

    @Test
    void getActionHistory_ackStatusAccepted_isDistinctFromNoAckYet() {
        UUID deviceA = UUID.randomUUID();
        when(historyRepository.findByDeviceIdOrderByTimestampDesc(eq(deviceA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record(deviceA, AcknowledgementStatus.ACCEPTED))));

        ResponseEntity<List<ActionHistoryResponse>> response = controller.getActionHistory(100, deviceA);

        assertEquals(AcknowledgementStatus.ACCEPTED, response.getBody().get(0).ackStatus());
    }
}
