package com.nexus.device.template;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface DeviceTemplateRepository extends JpaRepository<DeviceTemplate,UUID> { boolean existsByKey(String key); }
