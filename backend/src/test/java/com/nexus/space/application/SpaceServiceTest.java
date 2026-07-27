package com.nexus.space.application;

import com.nexus.building.application.BuildingNotFoundException;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.site.domain.Site;
import com.nexus.space.domain.Space;
import com.nexus.space.dto.CreateSpaceRequest;
import com.nexus.space.dto.SpaceResponse;
import com.nexus.space.dto.UpdateSpaceRequest;
import com.nexus.space.persistence.SpaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SpaceServiceTest {

    @Mock
    private SpaceRepository spaceRepository;

    @Mock
    private BuildingRepository buildingRepository;

    private SpaceService spaceService;

    @BeforeEach
    void setUp() {
        spaceService = new SpaceService(spaceRepository, buildingRepository);
    }

    @Test
    void createSpace_shouldReturnResponse_andSetTimestamps() {
        UUID buildingId = UUID.randomUUID();
        CreateSpaceRequest request = new CreateSpaceRequest("Space A", "Desc A");
        Site site = new Site(UUID.randomUUID(), "Site Name", "Desc", Instant.now(), Instant.now());
        Building building = new Building(buildingId, site, "Building Name", "Desc", Instant.now(), Instant.now());

        when(buildingRepository.findById(buildingId)).thenReturn(Optional.of(building));
        when(spaceRepository.save(any(Space.class))).thenAnswer(inv -> inv.getArgument(0));

        SpaceResponse response = spaceService.createSpace(buildingId, request);

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Space A");
        assertThat(response.buildingId()).isEqualTo(buildingId);
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void createSpace_shouldThrowException_whenBuildingNotFound() {
        UUID buildingId = UUID.randomUUID();
        CreateSpaceRequest request = new CreateSpaceRequest("Space A", "Desc A");

        when(buildingRepository.findById(buildingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spaceService.createSpace(buildingId, request))
                .isInstanceOf(BuildingNotFoundException.class);
    }

    @Test
    void getSpacesByBuildingId_shouldReturnList() {
        UUID buildingId = UUID.randomUUID();
        Site site = new Site(UUID.randomUUID(), "Site Name", "Desc", Instant.now(), Instant.now());
        Building building = new Building(buildingId, site, "Building Name", "Desc", Instant.now(), Instant.now());
        Space s1 = new Space(UUID.randomUUID(), building, "S1", "Desc", Instant.now(), Instant.now());
        Space s2 = new Space(UUID.randomUUID(), building, "S2", "Desc", Instant.now(), Instant.now());

        when(buildingRepository.existsById(buildingId)).thenReturn(true);
        when(spaceRepository.findByBuildingId(buildingId)).thenReturn(List.of(s1, s2));

        List<SpaceResponse> responses = spaceService.getSpacesByBuildingId(buildingId);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).name()).isEqualTo("S1");
        assertThat(responses.get(1).name()).isEqualTo("S2");
    }

    @Test
    void getSpacesByBuildingId_shouldThrowException_whenBuildingNotFound() {
        UUID buildingId = UUID.randomUUID();
        when(buildingRepository.existsById(buildingId)).thenReturn(false);

        assertThatThrownBy(() -> spaceService.getSpacesByBuildingId(buildingId))
                .isInstanceOf(BuildingNotFoundException.class);
    }

    @Test
    void getSpace_shouldReturnResponse_whenFound() {
        UUID spaceId = UUID.randomUUID();
        Site site = new Site(UUID.randomUUID(), "Site", "Desc", Instant.now(), Instant.now());
        Building building = new Building(UUID.randomUUID(), site, "Building", "Desc", Instant.now(), Instant.now());
        Space space = new Space(spaceId, building, "S1", "Desc", Instant.now(), Instant.now());

        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(space));

        SpaceResponse response = spaceService.getSpace(spaceId);

        assertThat(response.id()).isEqualTo(spaceId);
        assertThat(response.name()).isEqualTo("S1");
    }

    @Test
    void getSpace_shouldThrowException_whenNotFound() {
        UUID spaceId = UUID.randomUUID();
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spaceService.getSpace(spaceId))
                .isInstanceOf(SpaceNotFoundException.class);
    }

    @Test
    void updateSpace_shouldUpdateFields_andModifyUpdatedAt() throws InterruptedException {
        UUID spaceId = UUID.randomUUID();
        Site site = new Site(UUID.randomUUID(), "Site", "Desc", Instant.now(), Instant.now());
        Building building = new Building(UUID.randomUUID(), site, "Building", "Desc", Instant.now(), Instant.now());
        Instant oldTime = Instant.now().minusSeconds(10);
        Space existing = new Space(spaceId, building, "Old", "Old Desc", oldTime, oldTime);

        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(existing));
        when(spaceRepository.save(any(Space.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateSpaceRequest request = new UpdateSpaceRequest("New", "New Desc");
        Thread.sleep(10);

        SpaceResponse response = spaceService.updateSpace(spaceId, request);

        assertThat(response.name()).isEqualTo("New");
        assertThat(response.createdAt()).isEqualTo(oldTime);
        assertThat(response.updatedAt()).isAfter(oldTime);
    }

    @Test
    void deleteSpace_shouldCallRepository_whenExists() {
        UUID id = UUID.randomUUID();
        when(spaceRepository.existsById(id)).thenReturn(true);

        spaceService.deleteSpace(id);

        verify(spaceRepository).deleteById(id);
    }

    @Test
    void deleteSpace_shouldThrowException_whenDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(spaceRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> spaceService.deleteSpace(id))
                .isInstanceOf(SpaceNotFoundException.class);
        verify(spaceRepository, never()).deleteById(any());
    }
}
