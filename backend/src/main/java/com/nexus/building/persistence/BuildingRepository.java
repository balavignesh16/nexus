package com.nexus.building.persistence;

import com.nexus.building.domain.Building;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BuildingRepository extends JpaRepository<Building, UUID> {
    List<Building> findBySiteId(UUID siteId);
    boolean existsBySiteId(UUID siteId);
}
