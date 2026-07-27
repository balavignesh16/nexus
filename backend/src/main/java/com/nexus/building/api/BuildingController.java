package com.nexus.building.api;

import com.nexus.building.application.BuildingService;
import com.nexus.building.dto.BuildingResponse;
import com.nexus.building.dto.CreateBuildingRequest;
import com.nexus.building.dto.UpdateBuildingRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class BuildingController {

    private final BuildingService buildingService;

    public BuildingController(BuildingService buildingService) {
        this.buildingService = buildingService;
    }

    @PostMapping("/sites/{siteId}/buildings")
    public ResponseEntity<BuildingResponse> createBuilding(
            @PathVariable UUID siteId,
            @Valid @RequestBody CreateBuildingRequest request) {
        BuildingResponse response = buildingService.createBuilding(siteId, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/sites/{siteId}/buildings")
    public ResponseEntity<List<BuildingResponse>> getBuildingsBySiteId(@PathVariable UUID siteId) {
        return ResponseEntity.ok(buildingService.getBuildingsBySiteId(siteId));
    }

    @GetMapping("/buildings/{buildingId}")
    public ResponseEntity<BuildingResponse> getBuilding(@PathVariable UUID buildingId) {
        return ResponseEntity.ok(buildingService.getBuilding(buildingId));
    }

    @PutMapping("/buildings/{buildingId}")
    public ResponseEntity<BuildingResponse> updateBuilding(
            @PathVariable UUID buildingId,
            @Valid @RequestBody UpdateBuildingRequest request) {
        return ResponseEntity.ok(buildingService.updateBuilding(buildingId, request));
    }

    @DeleteMapping("/buildings/{buildingId}")
    public ResponseEntity<Void> deleteBuilding(@PathVariable UUID buildingId) {
        buildingService.deleteBuilding(buildingId);
        return ResponseEntity.noContent().build();
    }
}
