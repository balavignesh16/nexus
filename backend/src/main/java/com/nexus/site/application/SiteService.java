package com.nexus.site.application;

import com.nexus.site.domain.Site;
import com.nexus.site.dto.CreateSiteRequest;
import com.nexus.site.dto.SiteResponse;
import com.nexus.site.dto.UpdateSiteRequest;
import com.nexus.site.persistence.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class SiteService {

    private final SiteRepository siteRepository;

    public SiteService(SiteRepository siteRepository) {
        this.siteRepository = siteRepository;
    }

    @Transactional
    public SiteResponse createSite(CreateSiteRequest request) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Site site = new Site(UUID.randomUUID(), request.name(), request.description(), now, now);
        Site savedSite = siteRepository.save(site);
        return mapToResponse(savedSite);
    }

    public List<SiteResponse> getAllSites() {
        return siteRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public SiteResponse getSite(UUID id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException(id));
        return mapToResponse(site);
    }

    @Transactional
    public SiteResponse updateSite(UUID id, UpdateSiteRequest request) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new SiteNotFoundException(id));
        
        site.setName(request.name());
        site.setDescription(request.description());
        site.setUpdatedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        
        Site updatedSite = siteRepository.save(site);
        return mapToResponse(updatedSite);
    }

    @Transactional
    public void deleteSite(UUID id) {
        if (!siteRepository.existsById(id)) {
            throw new SiteNotFoundException(id);
        }
        siteRepository.deleteById(id);
    }

    private SiteResponse mapToResponse(Site site) {
        return new SiteResponse(
                site.getId(),
                site.getName(),
                site.getDescription(),
                site.getCreatedAt(),
                site.getUpdatedAt()
        );
    }
}
