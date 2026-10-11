package com.nexus.twin.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceReadbackMappingRepository extends JpaRepository<DeviceReadbackMapping,UUID> {
    Optional<DeviceReadbackMapping> findByDeviceIdAndWriteBindingIdAndWriteSubtargetKeyAndEnabledTrue(
            UUID deviceId,UUID writeBindingId,String writeSubtargetKey);
    List<DeviceReadbackMapping> findByDeviceIdAndReadBindingIdAndEnabledTrue(UUID deviceId,UUID readBindingId);
    List<DeviceReadbackMapping> findByDeviceId(UUID deviceId);
}
