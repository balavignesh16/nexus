package com.nexus.device.template;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface DeviceTemplateRepository extends JpaRepository<DeviceTemplate,UUID> {
 boolean existsByKey(String key);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select t from DeviceTemplate t where t.id=:id")
 Optional<DeviceTemplate> findByIdForUpdate(@Param("id") UUID id);
}
