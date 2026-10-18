package com.nexus.intelligence.draft.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires drafts past their window (each gets an EXPIRED decision so the audit trail records what was never approved), then removes the bodies of
 * unapproved drafts older than the retention window. Approval decisions, their evidence references and approved drafts are never removed.
 */
@Component
public class DraftRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(DraftRetentionJob.class);
    private final DraftStore store;

    public DraftRetentionJob(DraftStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${nexus.ai.draft.retention-scan-ms:3600000}", initialDelayString = "${nexus.ai.draft.retention-scan-ms:3600000}")
    public void run() {
        try {
            int expired = store.expireDue();
            int purged = store.purge();
            if (expired > 0 || purged > 0) log.info("AI draft retention: {} expired, {} removed", expired, purged);
        } catch (RuntimeException failure) {
            log.warn("AI draft retention failed; it will run again: {}", failure.getClass().getSimpleName());
        }
    }
}
