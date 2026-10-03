package com.nexus.device.binding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface DeviceCapabilityBindingRepository extends JpaRepository<DeviceCapabilityBinding,UUID> {
 List<DeviceCapabilityBinding> findByDeviceId(UUID deviceId);
 Optional<DeviceCapabilityBinding> findByDeviceIdAndDefinitionKeyAndEnabledTrue(UUID deviceId,String key);
}
