package com.nexus.device.onboarding;

import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.onboarding.api.DeviceOnboardingSessionResponse;
import com.nexus.shared.exception.ResourceNotFoundException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OnboardingLifecycleService {
    private final DeviceOnboardingSessionRepository sessions;
    private final DeviceRepository devices;
    private final Clock clock;

    @Autowired
    public OnboardingLifecycleService(DeviceOnboardingSessionRepository sessions, DeviceRepository devices) {
        this(sessions, devices, Clock.systemUTC());
    }

    OnboardingLifecycleService(DeviceOnboardingSessionRepository sessions, DeviceRepository devices, Clock clock) {
        this.sessions=sessions; this.devices=devices; this.clock=clock;
    }

    /** The sole exception to the established ACTIVE telemetry admission rule. */
    @Transactional(readOnly = true)
    public boolean admitsTelemetry(Device device) {
        if (device.getStatus() == DeviceStatus.ACTIVE) return true;
        if (device.getStatus() != DeviceStatus.REGISTERED) return false;
        return sessions.findByDeviceId(device.getId())
                .map(s -> s.getState() == OnboardingState.WAITING_FOR_TELEMETRY)
                .orElse(false);
    }

    @Transactional
    public void observedOnline(UUID deviceId) {
        sessions.findByDeviceId(deviceId).ifPresent(session -> {
            Instant now = clock.instant();
            if (session.getState() == OnboardingState.WAITING_FOR_STATUS
                    && session.getStatusDeadline() != null && now.isAfter(session.getStatusDeadline())) {
                session.statusTimeout(now);
            } else {
                session.observedOnline(now, now.plus(Duration.ofMinutes(5)));
            }
        });
    }

    /** Called only after normal typed telemetry persistence and Twin update succeeded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeFirstTelemetry(UUID deviceId) {
        var found = sessions.findByDeviceId(deviceId);
        if (found.isEmpty() || found.get().getState() != OnboardingState.WAITING_FOR_TELEMETRY) return;
        Device device = devices.findById(deviceId).orElseThrow();
        if (device.getStatus() != DeviceStatus.REGISTERED) throw new IllegalStateException("Onboarding device is not REGISTERED");
        Instant now = clock.instant();
        found.get().operational(now);
        device.activate();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejectInvalidTelemetry(UUID deviceId, String code, String message) {
        sessions.findByDeviceId(deviceId).ifPresent(s -> s.rejectTelemetry(code, message, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<DeviceOnboardingSessionResponse> all() {
        return sessions.findAll().stream().map(DeviceOnboardingSessionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public DeviceOnboardingSessionResponse get(UUID id) {
        return DeviceOnboardingSessionResponse.from(sessions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceOnboardingSession", id.toString())));
    }

    @Transactional
    public DeviceOnboardingSessionResponse retry(UUID id) {
        var session = sessions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceOnboardingSession", id.toString()));
        Instant now = clock.instant();
        session.retry(now, now.plus(Duration.ofMinutes(5)));
        return DeviceOnboardingSessionResponse.from(session);
    }

    @Transactional
    public DeviceOnboardingSessionResponse disable(UUID id) {
        var session = sessions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DeviceOnboardingSession", id.toString()));
        session.disable(clock.instant());
        return DeviceOnboardingSessionResponse.from(session);
    }

    @Scheduled(fixedDelayString = "${nexus.onboarding.timeout-scan-ms:30000}")
    @Transactional
    public void expireStatusDeadlines() {
        expireStatusDeadlines(clock.instant());
    }

    void expireStatusDeadlines(Instant now) {
        sessions.findByStateAndStatusDeadlineBefore(OnboardingState.WAITING_FOR_STATUS, now)
                .forEach(session -> session.statusTimeout(now));
    }
}
