package com.nexus.site.api;

import com.nexus.site.application.SiteService;
import com.nexus.site.dto.CreateSiteRequest;
import com.nexus.site.dto.SiteResponse;
import com.nexus.site.dto.UpdateSiteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sites")
public class SiteController {

    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @PostMapping
    public ResponseEntity<SiteResponse> createSite(@Valid @RequestBody CreateSiteRequest request) {
        SiteResponse response = siteService.createSite(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<SiteResponse>> getAllSites() {
        return ResponseEntity.ok(siteService.getAllSites());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SiteResponse> getSite(@PathVariable UUID id) {
        return ResponseEntity.ok(siteService.getSite(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SiteResponse> updateSite(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSiteRequest request) {
        return ResponseEntity.ok(siteService.updateSite(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSite(@PathVariable UUID id) {
        siteService.deleteSite(id);
        return ResponseEntity.noContent().build();
    }
}
