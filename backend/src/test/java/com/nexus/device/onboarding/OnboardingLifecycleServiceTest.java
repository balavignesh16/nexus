package com.nexus.device.onboarding;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnboardingLifecycleServiceTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final DeviceOnboardingSessionRepository sessions = mock(DeviceOnboardingSessionRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final OnboardingLifecycleService service = new OnboardingLifecycleService(sessions, devices, clock);

    @Test void onlyRegisteredDeviceWaitingForTelemetryGetsNarrowAdmission() {
        UUID id = UUID.randomUUID();
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(id);
        when(device.getStatus()).thenReturn(DeviceStatus.REGISTERED);
        var session = session(id, now.plusSeconds(10));
        when(sessions.findByDeviceId(id)).thenReturn(Optional.of(session));
        assertFalse(service.admitsTelemetry(device));
        service.observedOnline(id);
        assertEquals(OnboardingState.WAITING_FOR_TELEMETRY, session.getState());
        assertTrue(service.admitsTelemetry(device));
        when(device.getStatus()).thenReturn(DeviceStatus.MAINTENANCE);
        assertFalse(service.admitsTelemetry(device));
        when(device.getStatus()).thenReturn(DeviceStatus.ACTIVE);
        assertTrue(service.admitsTelemetry(device));
    }

    @Test void duplicateStatusIsIdempotentAndTimeoutPersists() {
        UUID id = UUID.randomUUID();
        var session = session(id, now.plusSeconds(10));
        when(sessions.findByDeviceId(id)).thenReturn(Optional.of(session));
        service.observedOnline(id);
        Instant first = session.getFirstStatusAt();
        service.observedOnline(id);
        assertEquals(first, session.getFirstStatusAt());
        UUID late = UUID.randomUUID();
        var expired = session(late, now.minusSeconds(1));
        when(sessions.findByStateAndStatusDeadlineBefore(OnboardingState.WAITING_FOR_STATUS, now))
                .thenReturn(List.of(expired));
        service.expireStatusDeadlines(now);
        assertEquals(OnboardingState.STATUS_TIMEOUT, expired.getState());
        assertEquals("STATUS_TIMEOUT", expired.getFailureCode());
        expired.retry(now, now.plusSeconds(60));
        assertEquals(OnboardingState.WAITING_FOR_STATUS, expired.getState());
    }

    @Test void firstTelemetryActivatesOnceAndInvalidInputRequiresRetry() {
        UUID id = UUID.randomUUID();
        var session = session(id, now.plusSeconds(10));
        session.observedOnline(now, now.plusSeconds(60));
        Device device = mock(Device.class);
        when(device.getStatus()).thenReturn(DeviceStatus.REGISTERED);
        when(sessions.findByDeviceId(id)).thenReturn(Optional.of(session));
        when(devices.findById(id)).thenReturn(Optional.of(device));
        service.rejectInvalidTelemetry(id, "CAPABILITY_TYPE_MISMATCH", "Expected NUMBER");
        assertEquals(OnboardingState.TELEMETRY_REJECTED, session.getState());
        assertEquals("CAPABILITY_TYPE_MISMATCH", session.getFailureCode());
        session.retry(now, now.plusSeconds(60));
        service.completeFirstTelemetry(id);
        assertEquals(OnboardingState.OPERATIONAL, session.getState());
        verify(device, times(1)).activate();
        service.completeFirstTelemetry(id);
        verify(device, times(1)).activate();
    }

    private DeviceOnboardingSession session(UUID deviceId, Instant deadline) {
        return new DeviceOnboardingSession(UUID.randomUUID(), deviceId, UUID.randomUUID(),
                "req-" + deviceId, "hash", deadline, now);
    }
}
