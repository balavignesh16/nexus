package com.nexus.device.capability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DeviceCapabilityRepository extends JpaRepository<DeviceCapability, UUID> {

    List<DeviceCapability> findByDeviceId(UUID deviceId);

    /**
     * Batch lookup for collection endpoints (Tier 5C) - a single query
     * covering every device in the page, avoiding an N+1 query pattern
     * when embedding capabilities into a list of DeviceResponse objects.
     */
    List<DeviceCapability> findByDeviceIdIn(Collection<UUID> deviceIds);

    /**
     * Ownership-scoped lookup: a capability is only ever found through
     * this method when both its own id AND the requested deviceId match -
     * this is what makes cross-device deletion structurally impossible
     * (a capability belonging to a different device simply is not found).
     */
    Optional<DeviceCapability> findByIdAndDeviceId(UUID id, UUID deviceId);

    boolean existsByDeviceIdAndCapabilityTypeAndDirection(UUID deviceId, CapabilityType capabilityType, CapabilityDirection direction);

    default Map<UUID, List<DeviceCapability>> findByDeviceIdInGroupedByDevice(Collection<UUID> deviceIds) {
        return findByDeviceIdIn(deviceIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(DeviceCapability::getDeviceId));
    }
}
