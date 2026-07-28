package com.nexus.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findBySpaceId(UUID spaceId);
    boolean existsBySpaceId(UUID spaceId);
    boolean existsBySerialNumber(String serialNumber);
    boolean existsBySerialNumberAndIdNot(String serialNumber, UUID id);
}
