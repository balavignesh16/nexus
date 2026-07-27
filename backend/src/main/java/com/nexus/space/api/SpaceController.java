package com.nexus.space.api;

import com.nexus.space.application.SpaceService;
import com.nexus.space.dto.CreateSpaceRequest;
import com.nexus.space.dto.SpaceResponse;
import com.nexus.space.dto.UpdateSpaceRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class SpaceController {

    private final SpaceService spaceService;

    public SpaceController(SpaceService spaceService) {
        this.spaceService = spaceService;
    }

    @PostMapping("/buildings/{buildingId}/spaces")
    public ResponseEntity<SpaceResponse> createSpace(
            @PathVariable UUID buildingId,
            @Valid @RequestBody CreateSpaceRequest request) {
        SpaceResponse response = spaceService.createSpace(buildingId, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping("/buildings/{buildingId}/spaces")
    public ResponseEntity<List<SpaceResponse>> getSpacesByBuildingId(@PathVariable UUID buildingId) {
        return ResponseEntity.ok(spaceService.getSpacesByBuildingId(buildingId));
    }

    @GetMapping("/spaces/{spaceId}")
    public ResponseEntity<SpaceResponse> getSpace(@PathVariable UUID spaceId) {
        return ResponseEntity.ok(spaceService.getSpace(spaceId));
    }

    @PutMapping("/spaces/{spaceId}")
    public ResponseEntity<SpaceResponse> updateSpace(
            @PathVariable UUID spaceId,
            @Valid @RequestBody UpdateSpaceRequest request) {
        return ResponseEntity.ok(spaceService.updateSpace(spaceId, request));
    }

    @DeleteMapping("/spaces/{spaceId}")
    public ResponseEntity<Void> deleteSpace(@PathVariable UUID spaceId) {
        spaceService.deleteSpace(spaceId);
        return ResponseEntity.noContent().build();
    }
}
