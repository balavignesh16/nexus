package com.nexus.device.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceOnboardingSessionRepository extends JpaRepository<DeviceOnboardingSession, UUID> {
    Optional<DeviceOnboardingSession> findByDeviceId(UUID deviceId);
    Optional<DeviceOnboardingSession> findByRequestKey(String requestKey);
    List<DeviceOnboardingSession> findByStateAndStatusDeadlineBefore(OnboardingState state, Instant deadline);
}
