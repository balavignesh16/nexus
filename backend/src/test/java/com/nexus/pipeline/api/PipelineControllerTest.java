package com.nexus.pipeline.api;

import com.nexus.pipeline.api.dto.PipelineTraceResponse;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.domain.PipelineTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PipelineController's DTO conversion (no more raw JPA
 * entity leaked to the API) and device-scoped filter (Phase 3).
 */
class PipelineControllerTest {

    private PipelineService pipelineService;
    private PipelineController controller;

    @BeforeEach
    void setUp() {
        pipelineService = mock(PipelineService.class);
        controller = new PipelineController(pipelineService);
    }

    private PipelineTrace trace(String deviceId) {
        return PipelineTrace.of("corr-1", deviceId, PipelineStage.TELEMETRY,
                System.currentTimeMillis(), "SUCCESS", "payload", Map.of("key", "value"));
    }

    @Test
    void getHistory_noDeviceIdFilter_returnsDtoNotRawEntity() {
        when(pipelineService.getHistory(100)).thenReturn(List.of(trace("dev-1")));

        List<PipelineTraceResponse> response = controller.getHistory(100, null);

        assertEquals(1, response.size());
        assertEquals("dev-1", response.get(0).deviceId());
        assertEquals("TELEMETRY", response.get(0).stage());
        verify(pipelineService, never()).getHistoryForDevice(anyString(), anyInt());
    }

    @Test
    void getHistory_timestampsRemainEpochMillis_notIsoStrings() {
        // Regression: the DTO must preserve the raw entity's original
        // epoch-millisecond wire format - PipelineCanvas.tsx does real
        // numeric arithmetic on these fields (endTimestamp > Date.now() -
        // 5000). Switching to Instant would silently break that comparison.
        long before = System.currentTimeMillis();
        when(pipelineService.getHistory(100)).thenReturn(List.of(trace("dev-1")));

        List<PipelineTraceResponse> response = controller.getHistory(100, null);

        assertTrue(response.get(0).startTimestamp() >= before);
        assertTrue(response.get(0).endTimestamp() >= response.get(0).startTimestamp());
    }

    @Test
    void getHistory_deviceIdFilter_usesDeviceScopedQuery() {
        when(pipelineService.getHistoryForDevice(eq("dev-a"), eq(100))).thenReturn(List.of(trace("dev-a")));

        List<PipelineTraceResponse> response = controller.getHistory(100, "dev-a");

        assertEquals(1, response.size());
        assertEquals("dev-a", response.get(0).deviceId());
        verify(pipelineService).getHistoryForDevice(eq("dev-a"), eq(100));
    }

    @Test
    void getHistory_deviceIdWithNoTraces_returnsEmptyListNotError() {
        when(pipelineService.getHistoryForDevice(eq("dev-unknown"), eq(100))).thenReturn(List.of());

        List<PipelineTraceResponse> response = controller.getHistory(100, "dev-unknown");

        assertEquals(0, response.size());
    }

    @Test
    void getHistory_blankDeviceId_rejectedAsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> controller.getHistory(100, "   "));
    }

    @Test
    void getByCorrelationId_returnsDtoList() {
        when(pipelineService.getByCorrelationId("corr-real")).thenReturn(List.of(trace("dev-1")));

        List<PipelineTraceResponse> response = controller.getByCorrelationId("corr-real");

        assertEquals(1, response.size());
        assertEquals("dev-1", response.get(0).deviceId());
    }

    @Test
    void getByCorrelationId_unknownCorrelation_returnsEmptyListNotError() {
        when(pipelineService.getByCorrelationId("corr-missing")).thenReturn(List.of());

        List<PipelineTraceResponse> response = controller.getByCorrelationId("corr-missing");

        assertEquals(0, response.size());
    }
}
