package com.nexus.rule.temporal;

import com.nexus.rule.domain.*;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** H3: a COUNT_WITHIN at the maximum supported count stores its full evidence and completes; nothing is silently truncated. */
class RuleCountCapacityIntegrationTest extends TypedTriggerSupport {
    @Test void aCountAtTheMaximumIsStorableAndCompletesOnTheLastSample() {
        int max = TemporalSpec.MAX_COUNT;
        UUID device = device();
        UUID rule = temporalRule(device, "max count", new TemporalSpec(TemporalOperator.COUNT_WITHIN, null, 3600L, max, null, null), true, RuleOperator.GT, "10", List.of(alert()));
        Instant t0 = now;
        for (int i = 0; i < max - 1; i++) { now = t0.plusSeconds(i); ingest(device, 25 + (i % 5), now); }
        assertEquals(max - 1, sql.queryForObject("SELECT jsonb_array_length(members) FROM rule_temporal_state WHERE rule_id=?", Integer.class, rule), "all evidence is kept");
        assertEquals(0, matches(rule));
        assertEquals(0, temporalEvents(rule, "RESET"));
        now = t0.plusSeconds(max);
        ingest(device, 30, now);
        assertEquals(1, matches(rule), "completes exactly at the configured count");
        String members = sql.queryForObject("SELECT details->'members' ->> 0 FROM rule_temporal_evidence WHERE rule_id=? AND event='SATISFIED'", String.class, rule);
        assertNotNull(members);
        assertEquals(max, sql.queryForObject("SELECT jsonb_array_length(details->'members') FROM rule_temporal_evidence WHERE rule_id=? AND event='SATISFIED'", Integer.class, rule), "the satisfying evidence lists every counted member");
    }
}
