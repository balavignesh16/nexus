package com.nexus.rule.trigger;

import com.nexus.rule.application.RuleMutationException;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.persistence.RuleVersionStore;
import com.nexus.rule.timer.RuleTimerService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

/**
 * MANUAL source (C12): a persisted, server-issued request identity addressed to one active rule version with an explicit
 * device scope. No DomainEvent, telemetry or command is fabricated; the request is the committed fact.
 */
@Service
public class RuleManualService {
    public record Issued(UUID requestId, UUID factId, UUID ruleVersionId) {}
    private final JdbcTemplate jdbc;
    private final RuleVersionStore versions;
    private final RuleTriggerFactRecorder recorder;
    private final Clock clock;
    public RuleManualService(JdbcTemplate jdbc, RuleVersionStore versions, RuleTriggerFactRecorder recorder, Clock clock) {
        this.jdbc = jdbc; this.versions = versions; this.recorder = recorder; this.clock = clock;
    }

    @Transactional
    public Issued request(UUID ruleId, UUID expectedVersionId) {
        var roots = jdbc.queryForList("SELECT lifecycle,active_version_id,reconciliation FROM rules WHERE rule_id=? FOR SHARE", ruleId);
        if (roots.isEmpty()) throw new RuleMutationException(404, "Rule not found");
        var root = roots.getFirst();
        UUID active = (UUID) root.get("active_version_id");
        if (!"ACTIVE".equals(root.get("lifecycle")) || active == null || !Set.of("PASSED", "NOT_REQUIRED").contains(String.valueOf(root.get("reconciliation"))))
            throw new RuleMutationException(409, "Rule is not ACTIVE; manual execution is rejected");
        if (expectedVersionId != null && !expectedVersionId.equals(active))
            throw new RuleMutationException(409, "The selected version is not the active version");
        Rule definition = versions.version(ruleId, active);
        if (!definition.subscribesTo(TriggerType.MANUAL) || definition.runtime() == null)
            throw new RuleMutationException(409, "Version has no explicit MANUAL subscription");
        UUID scope = RuleTimerService.explicitScope(definition);
        if (scope == null) throw new RuleMutationException(409, "Manual execution requires an explicit device scope");
        UUID requestId = UUID.randomUUID();
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("ruleId", ruleId.toString());
        attributes.put("ruleVersionId", active.toString());
        var fact = recorder.recordAndRoute(new RuleTriggerFactRecorder.Fact(TriggerType.MANUAL, requestId.toString(), scope, clock.instant(),
            Map.of("requestId", requestId.toString(), "channel", "api"), attributes, "manual:" + requestId));
        return new Issued(requestId, fact.orElseThrow(), active);
    }
}
