package com.nexus.rule.trigger;

import com.nexus.rule.application.RuleCapabilityValidator;
import com.nexus.rule.application.RuleExclusionGroupService;
import com.nexus.rule.application.RuleMutationException;
import com.nexus.rule.domain.*;
import com.nexus.rule.plan.ActionDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * C16 mutual exclusion end to end (scenario 57): persistent groups with explicit integer ranks, deterministic fail-closed arbitration in
 * the real plan transaction, transactionally consistent configuration, durable evidence, and rejection of the safety-override and
 * approval configurations this increment does not support.
 */
@AutoConfigureMockMvc
class RuleMutualExclusionIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleExclusionGroupService groups;
    @Autowired RuleCapabilityValidator validator;
    @Autowired MockMvc mvc;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));
    private final ExecutorService other = Executors.newCachedThreadPool();

    /** Platform-wide rule: its scope is the triggering device, its command targets another device. */
    private UUID commandRule(String name, int priority, UUID target, boolean on) {
        UUID id = UUID.randomUUID();
        lifecycle.legacySave(new Rule(id, name, true, priority, List.of(temperatureAbove("10")), List.of(ledCommand(target, on)), Map.of(), null, null,
            new RuleRuntime(TELEMETRY, null, null)), null);
        return id;
    }
    private int commands(UUID device) { return count("SELECT count(*) FROM action_history WHERE device_id=? AND action_type='SEND_COMMAND'", device); }
    private Map<String, Object> candidate(UUID rule) {
        return sql.queryForMap("SELECT disposition,reason,winner_candidate_id,candidate_id,evidence::text AS evidence FROM rule_action_candidates WHERE rule_id=? ORDER BY action_ordinal LIMIT 1", rule);
    }
    private Set<String> codes(Rule rule) { var set = new TreeSet<String>(); validator.inspect(rule).forEach(e -> set.add(e.get("code").toString())); return set; }

    // ------------------------------------------------------------------ arbitration through the real plan transaction

    @Test void theConfiguredRankDecidesWhichRulesCommandActsAndTheEvidenceExplainsWhy() {
        UUID trigger = device(), x = device(), y = device();
        UUID a = commandRule("exclusion a", 1, x, true);
        UUID b = commandRule("exclusion b", 1, y, true);
        var group = groups.create("heater vs cooler " + UUID.randomUUID());
        groups.setMember(group.groupId(), a, 2);
        groups.setMember(group.groupId(), b, 1);
        ingest(trigger, 25, now);
        assertEquals("SELECTED", candidate(b).get("disposition"));
        assertEquals("SUPPRESSED_EXCLUSION", candidate(a).get("disposition"));
        assertEquals("EXCLUSION_LOWER_RANK", candidate(a).get("reason"));
        assertEquals(candidate(b).get("candidate_id"), candidate(a).get("winner_candidate_id"));
        assertEquals(1, commands(y), "the winner's command was accepted");
        assertEquals(0, commands(x), "the excluded rule's command never reached guarded acceptance");
        assertTrue(candidate(a).get("evidence").toString().contains(group.groupId().toString()), "the persisted evidence names the group");
        assertTrue(candidate(a).get("evidence").toString().contains("\"rank\": 2"));
        assertEquals(1, count("SELECT count(*) FROM rule_matches WHERE rule_id=?", a), "the excluded rule's match is still attributed history");
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void anEqualBestRankFailsClosedAndWithoutAGroupBothActAsBefore() {
        UUID trigger = device(), x = device(), y = device();
        UUID a = commandRule("tie a", 1, x, true);
        UUID b = commandRule("tie b", 1, y, true);
        var group = groups.create("tie " + UUID.randomUUID());
        groups.setMember(group.groupId(), a, 4);
        groups.setMember(group.groupId(), b, 4);
        ingest(trigger, 25, now);
        assertEquals("SUPPRESSED_EXCLUSION_CONFLICT", candidate(a).get("disposition"));
        assertEquals("SUPPRESSED_EXCLUSION_CONFLICT", candidate(b).get("disposition"));
        assertEquals(0, commands(x) + commands(y), "an equal rank is never resolved by arrival order or a random winner");

        UUID trigger2 = device(), x2 = device(), y2 = device();
        UUID c = commandRule("free c", 1, x2, true);
        UUID d = commandRule("free d", 1, y2, true);
        now = now.plusSeconds(1);
        ingest(trigger2, 25, now);
        assertEquals(1, commands(x2));
        assertEquals(1, commands(y2), "rules outside any group are independent");
    }

    @Test void removingTheLastMemberOnlyHappensExplicitlyAndRestrictionsSurviveRuleAndGroupDeletionAttempts() {
        UUID x = device();
        UUID a = commandRule("kept restriction", 1, x, true);
        var group = groups.create("durable " + UUID.randomUUID());
        groups.setMember(group.groupId(), a, 1);
        var blocked = assertThrows(RuleMutationException.class, () -> groups.delete(group.groupId()));
        assertEquals(409, blocked.getStatusCode().value(), "a group with members is never dropped as a side effect");
        assertThrows(Exception.class, () -> sql.update("DELETE FROM rule_exclusion_groups WHERE group_id=?", group.groupId()), "the database refuses it too");
        assertThrows(Exception.class, () -> sql.update("DELETE FROM rules WHERE rule_id=?", a), "and refuses to delete a member rule");
        assertThrows(Exception.class, () -> sql.update("INSERT INTO rule_exclusion_members(group_id,rule_id,rank) VALUES(?,?,-1)", group.groupId(), UUID.randomUUID()), "ranks must be explicit non-negative integers of existing rules");
        groups.removeMember(group.groupId(), a);
        groups.delete(group.groupId());
        assertThrows(RuleMutationException.class, () -> groups.get(group.groupId()));
    }

    @Test void groupConfigurationIsValidatedAndTransactional() {
        UUID x = device();
        UUID a = commandRule("validated", 1, x, true);
        var group = groups.create("validation " + UUID.randomUUID());
        assertEquals(400, assertThrows(RuleMutationException.class, () -> groups.setMember(group.groupId(), a, -1)).getStatusCode().value());
        assertEquals(400, assertThrows(RuleMutationException.class, () -> groups.setMember(group.groupId(), a, null)).getStatusCode().value());
        assertEquals(404, assertThrows(RuleMutationException.class, () -> groups.setMember(group.groupId(), UUID.randomUUID(), 1)).getStatusCode().value());
        assertEquals(404, assertThrows(RuleMutationException.class, () -> groups.setMember(UUID.randomUUID(), a, 1)).getStatusCode().value());
        assertEquals(400, assertThrows(RuleMutationException.class, () -> groups.create("  ")).getStatusCode().value());
        assertEquals(409, assertThrows(RuleMutationException.class, () -> groups.create(group.name())).getStatusCode().value());
        assertEquals(0, groups.get(group.groupId()).members().size(), "no failed call left a partial membership");
        lifecycle.transition(a, "archive", null);
        assertEquals(409, assertThrows(RuleMutationException.class, () -> groups.setMember(group.groupId(), a, 1)).getStatusCode().value(), "an archived rule cannot join");
    }

    @Test void aDecisionSeesTheWholeOldOrTheWholeNewConfigurationNeverAMixture() throws Exception {
        UUID trigger = device(), x = device(), y = device();
        UUID a = commandRule("config race a", 1, x, true);
        UUID b = commandRule("config race b", 1, y, true);
        var group = groups.create("race " + UUID.randomUUID());
        groups.setMember(group.groupId(), a, 5);
        groups.setMember(group.groupId(), b, 6);                       // old configuration: A wins
        try (var change = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
            change.setAutoCommit(false);
            try (var st = change.createStatement()) {                   // an in-flight configuration change: ranks are being swapped
                st.executeQuery("SELECT 1 FROM rule_exclusion_groups WHERE group_id='" + group.groupId() + "' FOR UPDATE");
                st.executeUpdate("UPDATE rule_exclusion_members SET rank=9 WHERE group_id='" + group.groupId() + "' AND rule_id='" + a + "'");
                st.executeUpdate("UPDATE rule_exclusion_members SET rank=1 WHERE group_id='" + group.groupId() + "' AND rule_id='" + b + "'");
            }
            Future<?> deciding = other.submit(() -> ingest(trigger, 25, now));
            long deadline = System.currentTimeMillis() + 20_000;
            boolean waiting = false;
            while (System.currentTimeMillis() < deadline && !waiting) {
                waiting = count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%rule_exclusion_members%FOR SHARE%'") > 0;
                if (!waiting) Thread.sleep(25);
            }
            assertTrue(waiting, "the plan decision waits for the configuration change instead of reading half of it");
            change.commit();
            deciding.get(30, TimeUnit.SECONDS);
        }
        assertEquals("SELECTED", candidate(b).get("disposition"), "the decision used the complete NEW configuration");
        assertEquals("SUPPRESSED_EXCLUSION", candidate(a).get("disposition"));
        assertEquals(1, commands(y));
        assertEquals(0, commands(x));
    }

    // ------------------------------------------------------------------ F2: persisted evidence is independent of random group ids

    private Map<String, String> persistedOutcome(Map<String, UUID> rules) {
        var out = new TreeMap<String, String>();
        var names = new HashMap<UUID, String>();
        rules.forEach((name, id) -> names.put(id, name));
        for (var entry : rules.entrySet()) {
            var row = sql.queryForMap("SELECT disposition,reason,winner_candidate_id,plan_id FROM rule_action_candidates WHERE rule_id=?", entry.getValue());
            String winner = "-";
            if (row.get("winner_candidate_id") != null) {
                var w = sql.queryForMap("SELECT disposition,rule_id,plan_id FROM rule_action_candidates WHERE candidate_id=?", row.get("winner_candidate_id"));
                assertEquals("SELECTED", w.get("disposition"), "a persisted winner pointer must name a SELECTED primary");
                assertEquals(row.get("plan_id"), w.get("plan_id"), "and one from the same plan");
                winner = names.get((UUID) w.get("rule_id"));
            } else if (row.get("disposition").toString().startsWith("SUPPRESSED_EXCLUSION") && !row.get("disposition").equals("SUPPRESSED_EXCLUSION_CONFLICT")) {
                assertTrue(row.get("reason").toString().endsWith("WINNER_EXCLUDED"), "no winner needs the truthful reason: " + row.get("reason"));
            }
            out.put(entry.getKey(), row.get("disposition") + "/" + row.get("reason") + "->" + winner);
        }
        return out;
    }

    @Test void theSamePersistedDecisionsAndEvidenceResultWhateverTheRandomGroupIds() {
        Map<String, String> first = null;
        for (int run = 0; run < 4; run++) {
            UUID trigger = device();
            var rules = new LinkedHashMap<String, UUID>();
            for (String name : List.of("A", "B", "C", "D")) rules.put(name, commandRule("order " + name + " " + run, 1, device(), true));
            // G1: A beats B.  G2: C beats A.  G3: C and D tie.  Groups are created in a different order each run so their random ids differ.
            var order = new ArrayList<>(List.of(0, 1, 2));
            Collections.rotate(order, run);
            for (int position : order) {
                var group = groups.create("order " + run + "-" + position + " " + UUID.randomUUID());
                switch (position) {
                    case 0 -> { groups.setMember(group.groupId(), rules.get("A"), 1); groups.setMember(group.groupId(), rules.get("B"), 2); }
                    case 1 -> { groups.setMember(group.groupId(), rules.get("A"), 5); groups.setMember(group.groupId(), rules.get("C"), 1); }
                    default -> { groups.setMember(group.groupId(), rules.get("C"), 3); groups.setMember(group.groupId(), rules.get("D"), 3); }
                }
            }
            now = now.plusSeconds(1);
            ingest(trigger, 25, now);
            var outcome = persistedOutcome(rules);
            if (first == null) first = outcome;
            else assertEquals(first, outcome, "run " + run + " differs from run 0");
            sql.update("UPDATE rules SET lifecycle='PAUSED', enabled=false WHERE lifecycle='ACTIVE'");   // keep this run's rules out of the next
        }
        assertEquals("SUPPRESSED_EXCLUSION_CONFLICT/EXCLUSION_EQUAL_RANK_CONFLICT->-", first.get("C"));
        assertEquals("SUPPRESSED_EXCLUSION_CONFLICT/EXCLUSION_EQUAL_RANK_CONFLICT->-", first.get("D"));
        assertEquals("SUPPRESSED_EXCLUSION/EXCLUSION_LOWER_RANK_WINNER_EXCLUDED->-", first.get("A"));
        assertEquals("SUPPRESSED_EXCLUSION/EXCLUSION_LOWER_RANK_WINNER_EXCLUDED->-", first.get("B"));
    }

    // ------------------------------------------------------------------ safety override and approval are rejected, never executed

    @Test void unsupportedSafetyOverrideAndApprovalConfigurationIsRejectedEverywhere() throws Exception {
        UUID d = device();
        var conditions = List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, d.toString()), temperatureAbove("10"));
        var runtime = new RuleRuntime(TELEMETRY, null, null);
        assertTrue(codes(new Rule(UUID.randomUUID(), "r", true, 1, conditions, List.of(alert()), Map.of("safetyOverride", "true"), null, null, runtime)).contains("UNSUPPORTED_SAFETY_OVERRIDE"));
        assertTrue(codes(new Rule(UUID.randomUUID(), "r", true, 1, conditions, List.of(alert()), Map.of("Override", "x"), null, null, runtime)).contains("UNSUPPORTED_SAFETY_OVERRIDE"));
        assertTrue(codes(new Rule(UUID.randomUUID(), "r", true, 1, conditions, List.of(alert()), Map.of("requiresApproval", "true"), null, null, runtime)).contains("UNSUPPORTED_APPROVAL"));
        assertTrue(codes(new Rule(UUID.randomUUID(), "r", true, 1, conditions, List.of(alert()), Map.of("manualApproval", "yes"), null, null, runtime)).contains("UNSUPPORTED_APPROVAL"));
        var action = new RuleAction("CREATE_ALERT", Map.of("severity", "INFO", "approvalRequired", true));
        assertTrue(codes(new Rule(UUID.randomUUID(), "r", true, 1, conditions, List.of(action), Map.of(), null, null, runtime)).contains("UNSUPPORTED_APPROVAL"));
        assertEquals(Set.of(), codes(new Rule(UUID.randomUUID(), "r", true, 1, conditions, List.of(alert()), Map.of("owner", "ops"), null, null, runtime)), "ordinary metadata is unaffected");

        for (String key : List.of("safetyOverride", "approvalRequired", "requiresApproval", "manualApproval", "approval", "approvalPolicy", "safetyPolicy", "override")) {
            String body = "{\"name\":\"x\",\"enabled\":false,\"priority\":1,\"conditions\":[{\"field\":\"DEVICE_ID\",\"operator\":\"EQ\",\"expectedValue\":\"" + d
                + "\"}],\"actions\":[{\"actionType\":\"CREATE_ALERT\",\"parameters\":{}}],\"" + key + "\":true}";
            mvc.perform(post("/api/v1/rules").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is4xxClientError());
        }
        assertEquals(0, count("SELECT count(*) FROM rules WHERE name='x'"), "nothing was persisted from a rejected configuration");
    }

    @Test void onlyASelectedCandidateEverDispatchesWhateverOtherDispositionExists() {
        for (ActionDecision.Disposition disposition : ActionDecision.Disposition.values())
            assertEquals(disposition == ActionDecision.Disposition.SELECTED, new ActionDecision(null, disposition, "r", null).dispatches(), disposition.name());
    }

    // ------------------------------------------------------------------ API and evidence

    @Test void theAdditiveApiConfiguresGroupsAndExposesPersistedArbitrationEvidence() throws Exception {
        UUID trigger = device(), x = device(), y = device();
        UUID a = commandRule("api a", 1, x, true);
        UUID b = commandRule("api b", 1, y, true);
        String created = mvc.perform(post("/api/v1/rule-exclusion-groups").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"api " + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String groupId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).get("groupId").asText();
        mvc.perform(put("/api/v1/rule-exclusion-groups/" + groupId + "/members/" + a).contentType(MediaType.APPLICATION_JSON).content("{\"rank\":1}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/rule-exclusion-groups/" + groupId + "/members/" + b).contentType(MediaType.APPLICATION_JSON).content("{\"rank\":2}")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/rule-exclusion-groups/" + groupId + "/members/" + b).contentType(MediaType.APPLICATION_JSON).content("{\"rank\":-3}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/rule-exclusion-groups/" + groupId)).andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(2));
        mvc.perform(get("/api/v1/rule-exclusion-groups/" + UUID.randomUUID())).andExpect(status().isNotFound());

        ingest(trigger, 25, now);
        UUID matchOfB = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, b);
        mvc.perform(get("/api/v1/rule-matches/history/" + matchOfB + "/arbitration")).andExpect(status().isOk())
            .andExpect(jsonPath("$.plans.length()").value(1))
            .andExpect(jsonPath("$.plans[0].status").value("APPROVED"))
            .andExpect(jsonPath("$.plans[0].source.kind").value("FACT"))
            .andExpect(jsonPath("$.plans[0].candidates.length()").value(2))
            .andExpect(jsonPath("$.plans[0].candidates[?(@.disposition=='SELECTED')].reason").value("IDENTICAL_COMMAND_COALESCED"))
            .andExpect(jsonPath("$.plans[0].candidates[?(@.disposition=='SUPPRESSED_EXCLUSION')].reason").value("EXCLUSION_LOWER_RANK"))
            .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.containsString("separate facts")));
        mvc.perform(get("/api/v1/rule-matches/history/" + UUID.randomUUID() + "/arbitration")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/rule-exclusion-groups/" + groupId)).andExpect(status().isConflict());
    }
}
