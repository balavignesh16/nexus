package com.nexus.device.connectivity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeviceConnectivityRepository extends JpaRepository<DeviceConnectivity, UUID> {
}
