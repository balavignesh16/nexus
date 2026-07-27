package com.nexus.building.application;

import com.nexus.building.domain.Building;
import com.nexus.building.dto.BuildingResponse;
import com.nexus.building.dto.CreateBuildingRequest;
import com.nexus.building.dto.UpdateBuildingRequest;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.site.application.SiteNotFoundException;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
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
class BuildingServiceTest {

    @Mock
    private BuildingRepository buildingRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private SpaceRepository spaceRepository;

    private BuildingService buildingService;

    @BeforeEach
    void setUp() {
        buildingService = new BuildingService(buildingRepository, siteRepository, spaceRepository);
    }

    @Test
    void createBuilding_shouldReturnResponse_andSetTimestamps() {
        UUID siteId = UUID.randomUUID();
        CreateBuildingRequest request = new CreateBuildingRequest("Building A", "Desc A");
        Site site = new Site(siteId, "Site Name", "Desc", Instant.now(), Instant.now());

        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
        when(buildingRepository.save(any(Building.class))).thenAnswer(inv -> inv.getArgument(0));

        BuildingResponse response = buildingService.createBuilding(siteId, request);

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Building A");
        assertThat(response.siteId()).isEqualTo(siteId);
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void createBuilding_shouldThrowException_whenSiteNotFound() {
        UUID siteId = UUID.randomUUID();
        CreateBuildingRequest request = new CreateBuildingRequest("Building A", "Desc A");

        when(siteRepository.findById(siteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> buildingService.createBuilding(siteId, request))
                .isInstanceOf(SiteNotFoundException.class);
    }

    @Test
    void getBuildingsBySiteId_shouldReturnList() {
        UUID siteId = UUID.randomUUID();
        Site site = new Site(siteId, "Site", "Desc", Instant.now(), Instant.now());
        Building b1 = new Building(UUID.randomUUID(), site, "B1", "Desc", Instant.now(), Instant.now());
        Building b2 = new Building(UUID.randomUUID(), site, "B2", "Desc", Instant.now(), Instant.now());

        when(siteRepository.existsById(siteId)).thenReturn(true);
        when(buildingRepository.findBySiteId(siteId)).thenReturn(List.of(b1, b2));

        List<BuildingResponse> responses = buildingService.getBuildingsBySiteId(siteId);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).name()).isEqualTo("B1");
        assertThat(responses.get(1).name()).isEqualTo("B2");
    }

    @Test
    void getBuildingsBySiteId_shouldThrowException_whenSiteNotFound() {
        UUID siteId = UUID.randomUUID();
        when(siteRepository.existsById(siteId)).thenReturn(false);

        assertThatThrownBy(() -> buildingService.getBuildingsBySiteId(siteId))
                .isInstanceOf(SiteNotFoundException.class);
    }

    @Test
    void getBuilding_shouldReturnResponse_whenFound() {
        UUID buildingId = UUID.randomUUID();
        Site site = new Site(UUID.randomUUID(), "Site", "Desc", Instant.now(), Instant.now());
        Building b = new Building(buildingId, site, "B1", "Desc", Instant.now(), Instant.now());

        when(buildingRepository.findById(buildingId)).thenReturn(Optional.of(b));

        BuildingResponse response = buildingService.getBuilding(buildingId);

        assertThat(response.id()).isEqualTo(buildingId);
        assertThat(response.name()).isEqualTo("B1");
    }

    @Test
    void getBuilding_shouldThrowException_whenNotFound() {
        UUID buildingId = UUID.randomUUID();
        when(buildingRepository.findById(buildingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> buildingService.getBuilding(buildingId))
                .isInstanceOf(BuildingNotFoundException.class);
    }

    @Test
    void updateBuilding_shouldUpdateFields_andModifyUpdatedAt() throws InterruptedException {
        UUID buildingId = UUID.randomUUID();
        Site site = new Site(UUID.randomUUID(), "Site", "Desc", Instant.now(), Instant.now());
        Instant oldTime = Instant.now().minusSeconds(10);
        Building existing = new Building(buildingId, site, "Old", "Old Desc", oldTime, oldTime);

        when(buildingRepository.findById(buildingId)).thenReturn(Optional.of(existing));
        when(buildingRepository.save(any(Building.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateBuildingRequest request = new UpdateBuildingRequest("New", "New Desc");
        Thread.sleep(10); // ensure time difference

        BuildingResponse response = buildingService.updateBuilding(buildingId, request);

        assertThat(response.name()).isEqualTo("New");
        assertThat(response.createdAt()).isEqualTo(oldTime);
        assertThat(response.updatedAt()).isAfter(oldTime);
    }

    @Test
    void deleteBuilding_shouldCallRepository_whenExists_andHasNoSpaces() {
        UUID id = UUID.randomUUID();
        when(buildingRepository.existsById(id)).thenReturn(true);
        when(spaceRepository.existsByBuildingId(id)).thenReturn(false);

        buildingService.deleteBuilding(id);

        verify(buildingRepository).deleteById(id);
    }

    @Test
    void deleteBuilding_shouldThrowException_whenBuildingHasSpaces() {
        UUID id = UUID.randomUUID();
        when(buildingRepository.existsById(id)).thenReturn(true);
        when(spaceRepository.existsByBuildingId(id)).thenReturn(true);

        assertThatThrownBy(() -> buildingService.deleteBuilding(id))
                .isInstanceOf(BuildingHasSpacesException.class);
        verify(buildingRepository, never()).deleteById(id);
    }

    @Test
    void deleteBuilding_shouldThrowException_whenDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(buildingRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> buildingService.deleteBuilding(id))
                .isInstanceOf(BuildingNotFoundException.class);
        verify(buildingRepository, never()).deleteById(any());
    }
}
