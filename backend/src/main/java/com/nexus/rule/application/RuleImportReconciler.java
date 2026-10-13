package com.nexus.rule.application;

import com.nexus.rule.persistence.RuleVersionStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Each attempt owns a separate transaction; DB eligibility stays closed on failure. */
@Component
public class RuleImportReconciler {
    private final RuleVersionStore store;
    private final RuleLifecycleService lifecycle;
    public RuleImportReconciler(RuleVersionStore store,RuleLifecycleService lifecycle) { this.store=store;this.lifecycle=lifecycle; }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { reconcileBatch(); }
    @Scheduled(fixedDelayString="${nexus.rule.reconciliation-delay-ms:30000}")
    public void reconcileBatch() {
        store.pendingBatch(100).stream().filter(v->"PENDING".equals(v.reconciliation()))
            .forEach(v->lifecycle.reconcile(v.ruleId(),v.rowRevision()));
    }
}
