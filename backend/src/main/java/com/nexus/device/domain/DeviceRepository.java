package com.nexus.device.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d where d.id = :id")
    Optional<Device> findByIdForUpdate(@Param("id") UUID id);
    List<Device> findBySpaceId(UUID spaceId);
    boolean existsBySpaceId(UUID spaceId);
    boolean existsBySerialNumber(String serialNumber);
    boolean existsBySerialNumberAndIdNot(String serialNumber, UUID id);
    List<Device> findByStatus(DeviceStatus status);
    boolean existsByDeviceTemplateId(UUID templateId);
}
