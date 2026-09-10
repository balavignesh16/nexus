package com.nexus.infrastructure.retention;

import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.event.domain.DomainEventRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RetentionManager {
    private static final Logger log = LoggerFactory.getLogger(RetentionManager.class);

    private final EntityManager entityManager;

    @Value("${nexus.retention.telemetry-days:30}")
    private int telemetryDays;

    @Value("${nexus.retention.pipeline-days:7}")
    private int pipelineDays;

    @Value("${nexus.retention.events-days:90}")
    private int eventsDays;

    @Value("${nexus.retention.actions-days:90}")
    private int actionsDays;

    public RetentionManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Scheduled(cron = "0 0 2 * * *") // Run at 2 AM every day
    @Transactional
    public void cleanupOldData() {
        log.info("Starting retention cleanup job...");
        try {
            // Use native TimescaleDB drop_chunks for hypertables
            entityManager.createNativeQuery("SELECT drop_chunks('telemetry_history', INTERVAL '" + telemetryDays + " days')").getResultList();
            log.info("Cleaned up telemetry older than {} days", telemetryDays);
        } catch (Exception e) {
            log.warn("Failed to drop chunks for telemetry_history. It may not be a hypertable or no chunks to drop.", e);
        }

        try {
            entityManager.createNativeQuery("SELECT drop_chunks('pipeline_traces', INTERVAL '" + pipelineDays + " days')").getResultList();
            log.info("Cleaned up pipeline traces older than {} days", pipelineDays);
        } catch (Exception e) {
            log.warn("Failed to drop chunks for pipeline_traces. It may not be a hypertable or no chunks to drop.", e);
        }

        try {
            int eventsDeleted = entityManager.createNativeQuery("DELETE FROM domain_events WHERE timestamp < NOW() - INTERVAL '" + eventsDays + " days'").executeUpdate();
            log.info("Cleaned up {} domain events older than {} days", eventsDeleted, eventsDays);
        } catch (Exception e) {
            log.warn("Failed to clean up domain events", e);
        }

        try {
            int actionsDeleted = entityManager.createNativeQuery("DELETE FROM action_history WHERE timestamp < NOW() - INTERVAL '" + actionsDays + " days'").executeUpdate();
            log.info("Cleaned up {} action history records older than {} days", actionsDeleted, actionsDays);
        } catch (Exception e) {
            log.warn("Failed to clean up action history", e);
        }
        
        log.info("Retention cleanup job completed.");
    }
}
