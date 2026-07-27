package com.nexus.space.persistence;

import com.nexus.space.domain.Space;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpaceRepository extends JpaRepository<Space, UUID> {
    List<Space> findByBuildingId(UUID buildingId);
    boolean existsByBuildingId(UUID buildingId);
}
