package com.nexus.site.application;

import com.nexus.building.persistence.BuildingRepository;
import com.nexus.site.domain.Site;
import com.nexus.site.dto.CreateSiteRequest;
import com.nexus.site.dto.SiteResponse;
import com.nexus.site.dto.UpdateSiteRequest;
import com.nexus.site.persistence.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class SiteServiceTest {

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private BuildingRepository buildingRepository;

    private SiteService siteService;

    @BeforeEach
    void setUp() {
        siteService = new SiteService(siteRepository, buildingRepository);
    }

    @Test
    void createSite_shouldReturnSiteResponse_andSetTimestamps() {
        // Arrange
        CreateSiteRequest request = new CreateSiteRequest("Test Site", "Test Description");
        
        when(siteRepository.save(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        SiteResponse response = siteService.createSite(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Test Site");
        assertThat(response.description()).isEqualTo("Test Description");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
        assertThat(response.id()).isNotNull();

        verify(siteRepository).save(any(Site.class));
    }

    @Test
    void getSite_shouldReturnSite_whenFound() {
        // Arrange
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Site site = new Site(id, "Existing Site", "Desc", now, now);
        
        when(siteRepository.findById(id)).thenReturn(Optional.of(site));

        // Act
        SiteResponse response = siteService.getSite(id);

        // Assert
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("Existing Site");
    }

    @Test
    void getSite_shouldThrowException_whenNotFound() {
        // Arrange
        UUID id = UUID.randomUUID();
        when(siteRepository.findById(id)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> siteService.getSite(id))
                .isInstanceOf(SiteNotFoundException.class)
                .hasMessageContaining("Site not found with identifier: " + id);
    }

    @Test
    void updateSite_shouldUpdateFields_andModifyUpdatedAt() throws InterruptedException {
        // Arrange
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z");
        Instant oldUpdatedAt = Instant.parse("2020-01-01T00:00:00Z");
        Site existingSite = new Site(id, "Old Name", "Old Desc", createdAt, oldUpdatedAt);
        
        when(siteRepository.findById(id)).thenReturn(Optional.of(existingSite));
        when(siteRepository.save(any(Site.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateSiteRequest request = new UpdateSiteRequest("New Name", "New Desc");

        // Act
        // Small delay to ensure timestamp difference if it was using Instant.now() immediately
        Thread.sleep(10);
        SiteResponse response = siteService.updateSite(id, request);

        // Assert
        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.description()).isEqualTo("New Desc");
        assertThat(response.createdAt()).isEqualTo(createdAt); // Should not change
        assertThat(response.updatedAt()).isAfter(oldUpdatedAt); // Should change
    }

    @Test
    void deleteSite_shouldCallRepository_whenSiteExists_andHasNoBuildings() {
        // Arrange
        UUID id = UUID.randomUUID();
        when(siteRepository.existsById(id)).thenReturn(true);
        when(buildingRepository.existsBySiteId(id)).thenReturn(false);

        // Act
        siteService.deleteSite(id);

        // Assert
        verify(siteRepository).deleteById(id);
    }

    @Test
    void deleteSite_shouldThrowException_whenSiteHasBuildings() {
        // Arrange
        UUID id = UUID.randomUUID();
        when(siteRepository.existsById(id)).thenReturn(true);
        when(buildingRepository.existsBySiteId(id)).thenReturn(true);

        // Act & Assert
        assertThatThrownBy(() -> siteService.deleteSite(id))
                .isInstanceOf(SiteHasBuildingsException.class);
        verify(siteRepository, never()).deleteById(id);
    }

    @Test
    void deleteSite_shouldThrowException_whenSiteDoesNotExist() {
        // Arrange
        UUID id = UUID.randomUUID();
        when(siteRepository.existsById(id)).thenReturn(false);

        // Act & Assert
        assertThatThrownBy(() -> siteService.deleteSite(id))
                .isInstanceOf(SiteNotFoundException.class);
        verify(siteRepository, never()).deleteById(id);
    }
}
