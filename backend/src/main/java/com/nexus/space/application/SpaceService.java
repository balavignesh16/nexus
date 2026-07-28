package com.nexus.space.application;

import com.nexus.building.application.BuildingNotFoundException;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.dto.CreateSpaceRequest;
import com.nexus.space.dto.SpaceResponse;
import com.nexus.space.dto.UpdateSpaceRequest;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.space.domain.exception.SpaceHasDevicesException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SpaceService {

    private final SpaceRepository spaceRepository;
    private final BuildingRepository buildingRepository;
    private final DeviceRepository deviceRepository;

    public SpaceService(SpaceRepository spaceRepository, BuildingRepository buildingRepository, DeviceRepository deviceRepository) {
        this.spaceRepository = spaceRepository;
        this.buildingRepository = buildingRepository;
        this.deviceRepository = deviceRepository;
    }

    @Transactional
    public SpaceResponse createSpace(UUID buildingId, CreateSpaceRequest request) {
        Building building = buildingRepository.findById(buildingId)
                .orElseThrow(() -> new BuildingNotFoundException(buildingId));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Space space = new Space(UUID.randomUUID(), building, request.name(), request.description(), now, now);
        Space savedSpace = spaceRepository.save(space);

        return mapToResponse(savedSpace);
    }

    public List<SpaceResponse> getSpacesByBuildingId(UUID buildingId) {
        if (!buildingRepository.existsById(buildingId)) {
            throw new BuildingNotFoundException(buildingId);
        }

        return spaceRepository.findByBuildingId(buildingId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public SpaceResponse getSpace(UUID id) {
        Space space = spaceRepository.findById(id)
                .orElseThrow(() -> new SpaceNotFoundException(id));
        return mapToResponse(space);
    }

    @Transactional
    public SpaceResponse updateSpace(UUID id, UpdateSpaceRequest request) {
        Space space = spaceRepository.findById(id)
                .orElseThrow(() -> new SpaceNotFoundException(id));

        space.setName(request.name());
        space.setDescription(request.description());
        space.setUpdatedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));

        Space updatedSpace = spaceRepository.save(space);
        return mapToResponse(updatedSpace);
    }

    @Transactional
    public void deleteSpace(UUID id) {
        if (!spaceRepository.existsById(id)) {
            throw new SpaceNotFoundException(id);
        }
        if (deviceRepository.existsBySpaceId(id)) {
            throw new SpaceHasDevicesException(id);
        }
        spaceRepository.deleteById(id);
    }

    private SpaceResponse mapToResponse(Space space) {
        return new SpaceResponse(
                space.getId(),
                space.getBuilding().getId(),
                space.getName(),
                space.getDescription(),
                space.getCreatedAt(),
                space.getUpdatedAt()
        );
    }
}
