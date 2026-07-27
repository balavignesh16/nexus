package com.nexus.building.application;

import com.nexus.building.domain.Building;
import com.nexus.building.dto.BuildingResponse;
import com.nexus.building.dto.CreateBuildingRequest;
import com.nexus.building.dto.UpdateBuildingRequest;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.site.application.SiteNotFoundException;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class BuildingService {

    private final BuildingRepository buildingRepository;
    private final SiteRepository siteRepository;

    public BuildingService(BuildingRepository buildingRepository, SiteRepository siteRepository) {
        this.buildingRepository = buildingRepository;
        this.siteRepository = siteRepository;
    }

    @Transactional
    public BuildingResponse createBuilding(UUID siteId, CreateBuildingRequest request) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException(siteId));
        
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Building building = new Building(UUID.randomUUID(), site, request.name(), request.description(), now, now);
        Building savedBuilding = buildingRepository.save(building);
        
        return mapToResponse(savedBuilding);
    }

    public List<BuildingResponse> getBuildingsBySiteId(UUID siteId) {
        if (!siteRepository.existsById(siteId)) {
            throw new SiteNotFoundException(siteId);
        }
        
        return buildingRepository.findBySiteId(siteId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public BuildingResponse getBuilding(UUID id) {
        Building building = buildingRepository.findById(id)
                .orElseThrow(() -> new BuildingNotFoundException(id));
        return mapToResponse(building);
    }

    @Transactional
    public BuildingResponse updateBuilding(UUID id, UpdateBuildingRequest request) {
        Building building = buildingRepository.findById(id)
                .orElseThrow(() -> new BuildingNotFoundException(id));
        
        building.setName(request.name());
        building.setDescription(request.description());
        building.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        
        Building updatedBuilding = buildingRepository.save(building);
        return mapToResponse(updatedBuilding);
    }

    @Transactional
    public void deleteBuilding(UUID id) {
        if (!buildingRepository.existsById(id)) {
            throw new BuildingNotFoundException(id);
        }
        buildingRepository.deleteById(id);
    }

    private BuildingResponse mapToResponse(Building building) {
        return new BuildingResponse(
                building.getId(),
                building.getSite().getId(),
                building.getName(),
                building.getDescription(),
                building.getCreatedAt(),
                building.getUpdatedAt()
        );
    }
}
