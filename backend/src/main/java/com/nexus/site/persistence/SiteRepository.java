package com.nexus.site.persistence;

import com.nexus.site.domain.Site;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SiteRepository extends JpaRepository<Site, UUID> {
}
