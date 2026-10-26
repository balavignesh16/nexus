package com.nexus.intelligence.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.intelligence.model.ModelProvider;
import com.nexus.intelligence.model.ProviderIdentity;
import com.nexus.rule.approval.DraftApprovalCli;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6.3 on a real PostgreSQL and the real Phase 3 validator and lifecycle service: a model proposal becomes a stored draft only, the draft is judged by the
 * deterministic policy and validator, nothing reaches the broker, and the only route from a draft to an active rule is the local approval command followed by
 * the normal activation. Ledger P6-038..P6-056.
 */
@AutoConfigureMockMvc
class DraftConflictApprovalIntegrationTest extends TypedTriggerSupport {
    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry r) {
        r.add("nexus.ai.send-device-evidence", () -> "true");
        r.add("nexus.ai.rate-limit.requests-per-minute", () -> "1000");
        r.add("nexus.ai.rate-limit.burst", () -> "1000");
    }

    @MockBean ModelProvider provider;
    @Autowired DraftService drafts;
    @org.springframework.boot.test.mock.mockito.SpyBean DraftConflictAnalyzer conflictAnalyzer;
    @Autowired com.nexus.rule.application.RuleExclusionGroupService exclusionGroups;
    @Autowired com.nexus.intelligence.draft.store.DraftStore store;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    private final AtomicReference<String> reply = new AtomicReference<>();
    private static boolean rolesCreated;

    @BeforeEach
    void setUpProvider() throws Exception {
        when(provider.available()).thenReturn(true);
        when(provider.identity()).thenReturn(new ProviderIdentity("fake", "fake-1", "cfg123"));
        when(provider.complete(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> {
            String r = reply.get();
            if (r == null) throw new com.nexus.intelligence.model.ModelProviderException("HTTP_500", false);
            return r;
        });
        if (!rolesCreated) {
            sql.execute("DO $$ BEGIN CREATE ROLE appr_it LOGIN PASSWORD 'appr'; CREATE ROLE plain_it LOGIN PASSWORD 'plain'; END $$");
            sql.execute("GRANT nexus_approver TO appr_it");
            sql.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO appr_it, plain_it");
            sql.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO appr_it, plain_it");
            rolesCreated = true;
        }
    }

    private String url() {
        return sql.execute((java.sql.Connection c) -> c.getMetaData().getURL());
    }

    private Map<String, String> env(String role) {
        return Map.of("NEXUS_APPROVER_DB_URL", url(), "NEXUS_APPROVER_DB_USER", role, "NEXUS_APPROVER_DB_PASSWORD", role.startsWith("appr") ? "appr" : "plain");
    }

    private record Cli(int code, String out, String err) {}

    private Cli cli(String role, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int code = DraftApprovalCli.run(args, env(role), new PrintStream(o), new PrintStream(e), "someone");
        return new Cli(code, o.toString(), e.toString());
    }

    private static String json(UUID device, String conditionExtra, String actions, String refs, int cooldown) {
        return "{\"name\":\"AI high temperature\",\"priority\":5,\"cooldownSeconds\":" + cooldown + ",\"rationale\":\"because\","
                + "\"conditions\":[{\"field\":\"DEVICE_ID\",\"operator\":\"EQ\",\"expectedValue\":\"" + device + "\"},"
                + "{\"field\":\"VALUE\",\"operator\":\"GT\",\"expectedValue\":\"" + conditionExtra + "\",\"capabilityType\":\"TEMPERATURE\"}],"
                + "\"actions\":" + actions + ",\"evidenceRefs\":" + refs + "}";
    }

    private static final String ALERT = "[{\"actionType\":\"CREATE_ALERT\",\"parameters\":{\"severity\":\"INFO\"}}]";

    private DraftOutcome.Created created(DraftOutcome o) {
        assertThat(o).isInstanceOf(DraftOutcome.Created.class);
        return (DraftOutcome.Created) o;
    }

    private void unchanged(int rules, int versions, int outbox, int commands) {
        assertThat(count("SELECT count(*) FROM rules")).isEqualTo(rules);
        assertThat(count("SELECT count(*) FROM rule_versions")).isEqualTo(versions);
        assertThat(count("SELECT count(*) FROM command_outbox")).isEqualTo(outbox);
        assertThat(count("SELECT count(*) FROM command_state_history")).isEqualTo(commands);
    }

    @Test
    void p6_043_aDraftOnAGovernedTargetIsStoredWithItsConflictsAndNeedsAnExplicitAcknowledgementToApprove() {
        UUID target = device();
        UUID existing = typedRule(device(), "Existing LED rule", 1, List.of(new com.nexus.rule.domain.TriggerSubscription(com.nexus.rule.domain.TriggerType.ACCEPTED_TELEMETRY)),
                List.of(temperatureAbove("40")), List.of(ledCommand(target, true)));
        var group = exclusionGroups.create("led-owner-" + UUID.randomUUID());
        exclusionGroups.setMember(group.groupId(), existing, 1);
        String command = "[{\"actionType\":\"SEND_COMMAND\",\"parameters\":{\"deviceId\":\"" + target + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":false}}]";
        reply.set(json(target, "30", command, "[\"device:" + target + "\"]", 60));
        DraftOutcome.Created c = created(drafts.propose("Turn the LED off when it is hot"));
        String id = c.draftId().toString();

        assertThat(c.valid()).as("a conflict is information for the approver, it does not make the draft invalid").isTrue();
        assertThat(sql.queryForObject("SELECT validation #>> '{conflictAnalysis,status}' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isEqualTo("CONFLICTS");
        assertThat(sql.queryForObject("SELECT validation::text FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).contains("MUTUAL_EXCLUSION_TARGET_GOVERNED").contains(existing.toString());
        assertThat(cli("appr_it", "show", "--draft", id).out()).contains("conflicts    CONFLICTS");

        Cli refused = cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "looks right");
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.err()).contains("conflict analysis").contains("--acknowledge-conflicts yes");
        assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions WHERE draft_id=?::uuid", id)).isZero();

        Cli approved = cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "owner agreed", "--acknowledge-conflicts", "yes");
        assertThat(approved.code()).isEqualTo(0);
        assertThat(sql.queryForObject("SELECT reason FROM ai_rule_draft_decisions WHERE draft_id=?::uuid", String.class, id)).contains("owner agreed").contains("conflict analysis CONFLICTS acknowledged");
        assertThat(sql.queryForObject("SELECT count(*) FROM rules WHERE rule_id=(SELECT (definition->>'ruleId')::uuid FROM ai_rule_drafts WHERE draft_id=?::uuid) AND lifecycle='ACTIVE'", Integer.class, id)).isZero();
    }

    @Test
    void p6_043_whenTheConflictAnalysisCannotRunTheDraftIsStoredInvalidNotSafe() {
        UUID target = device();
        String command = "[{\"actionType\":\"SEND_COMMAND\",\"parameters\":{\"deviceId\":\"" + target + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true}}]";
        reply.set(json(target, "30", command, "[\"device:" + target + "\"]", 60));
        org.mockito.Mockito.doThrow(new IllegalStateException("snapshot unavailable")).when(conflictAnalyzer).analyze(org.mockito.ArgumentMatchers.any());
        DraftOutcome.Created c;
        try {
            c = created(drafts.propose("Turn the LED on when it is hot"));
        } finally {
            org.mockito.Mockito.reset(conflictAnalyzer);
        }
        assertThat(c.valid()).isFalse();
        assertThat(c.state()).isEqualTo("INVALID");
        assertThat(c.diagnostics().toString()).contains("CONFLICT_ANALYSIS_UNRESOLVED");
        String id = c.draftId().toString();
        assertThat(sql.queryForObject("SELECT validation #>> '{conflictAnalysis,status}' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isEqualTo("UNRESOLVED");
        assertThat(cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "try", "--acknowledge-conflicts", "yes").code()).as("an invalid draft cannot be approved even when acknowledged").isEqualTo(1);
    }

    private UUID lastGroup;

    private DraftOutcome.Created conflictedDraft(UUID target) {
        UUID existing = typedRule(device(), "Existing LED rule", 1, List.of(new com.nexus.rule.domain.TriggerSubscription(com.nexus.rule.domain.TriggerType.ACCEPTED_TELEMETRY)),
                List.of(temperatureAbove("40")), List.of(ledCommand(target, true)));
        var group = exclusionGroups.create("stale-" + UUID.randomUUID());
        exclusionGroups.setMember(group.groupId(), existing, 1);
        lastGroup = group.groupId();
        String command = "[{\"actionType\":\"SEND_COMMAND\",\"parameters\":{\"deviceId\":\"" + target + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":false}}]";
        reply.set(json(target, "30", command, "[\"device:" + target + "\"]", 60));
        return created(drafts.propose("Turn the LED off when it is hot " + UUID.randomUUID()));
    }

    private void anotherRuleCommands(UUID target) {
        typedRule(device(), "Late LED rule " + UUID.randomUUID(), 3, List.of(new com.nexus.rule.domain.TriggerSubscription(com.nexus.rule.domain.TriggerType.ACCEPTED_TELEMETRY)),
                List.of(temperatureAbove("50")), List.of(ledCommand(target, false)));
    }

    @Test
    void p6_043_aChangeAfterDraftingMakesApprovalFailClosedWithAStaleAnalysisReason() {
        UUID target = device();
        DraftOutcome.Created c = conflictedDraft(target);
        String id = c.draftId().toString();
        assertThat(cli("appr_it", "show", "--draft", id).out()).contains("freshness    CURRENT");
        anotherRuleCommands(target);   // an active rule now also commands the target: the stored CONFLICTS result is outdated
        assertThat(cli("appr_it", "show", "--draft", id).out()).contains("freshness    STALE");

        Cli refused = cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "ok", "--acknowledge-conflicts", "yes");
        assertThat(refused.code()).isEqualTo(1);
        assertThat(refused.err()).contains("stale conflict analysis").contains("create the draft again");
        assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions WHERE draft_id=?::uuid", id)).isZero();
        assertThat(sql.queryForObject("SELECT state FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isEqualTo("PENDING_APPROVAL");
        assertThat(count("SELECT count(*) FROM rules WHERE rule_id=(SELECT (definition->>'ruleId')::uuid FROM ai_rule_drafts WHERE draft_id=?::uuid)", id)).isZero();
    }

    @Test
    void p6_043_anExclusionGroupChangeAlsoMakesTheAnalysisStaleAndAnUnrelatedChangeDoesNot() {
        UUID target = device();
        DraftOutcome.Created c = conflictedDraft(target);
        String id = c.draftId().toString();
        typedRule(device(), "Unrelated rule", 1, List.of(new com.nexus.rule.domain.TriggerSubscription(com.nexus.rule.domain.TriggerType.ACCEPTED_TELEMETRY)),
                List.of(temperatureAbove("40")), List.of(ledCommand(device(), true)));
        assertThat(sql.queryForObject("SELECT ai_conflict_stale(?::uuid)", Boolean.class, id)).as("a rule on another target is not part of the compared state").isFalse();
        UUID groupId = lastGroup;
        UUID joiner = typedRule(device(), "Joins the group", 9, List.of(new com.nexus.rule.domain.TriggerSubscription(com.nexus.rule.domain.TriggerType.ACCEPTED_TELEMETRY)),
                List.of(temperatureAbove("40")), List.of(ledCommand(device(), true)));
        exclusionGroups.setMember(groupId, joiner, 5);
        assertThat(sql.queryForObject("SELECT ai_conflict_stale(?::uuid)", Boolean.class, id)).as("a new member of a group that governs the target changes the compared state").isTrue();
    }

    @Test
    void p6_043_aChangeBetweenApprovalAndActivationRefusesActivation() {
        UUID target = device();
        DraftOutcome.Created c = conflictedDraft(target);
        String id = c.draftId().toString();
        assertThat(cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "ok", "--acknowledge-conflicts", "yes").code()).isEqualTo(0);
        anotherRuleCommands(target);
        UUID rule = UUID.fromString(sql.queryForObject("SELECT definition->>'ruleId' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id));
        assertThatThrownBy(() -> lifecycle.transition(rule, "activate", sql.queryForObject("SELECT row_revision FROM rules WHERE rule_id=?", Long.class, rule)))
                .hasMessageContaining("stale conflict analysis");
        assertThat(sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?", String.class, rule)).isNotEqualTo("ACTIVE");
    }

    @Test
    void p6_043_anApprovalRacingWithAChangeIsEitherApprovedOnTheAnalysedStateOrRefusedAsStale() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (int round = 0; round < 4; round++) {
                UUID target = device();
                DraftOutcome.Created c = conflictedDraft(target);
                String id = c.draftId().toString();
                var change = pool.submit(() -> anotherRuleCommands(target));
                Cli result = cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "race", "--acknowledge-conflicts", "yes");
                change.get();
                if (result.code() == 0) {
                    assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions WHERE draft_id=?::uuid AND decision='APPROVED'", id)).isEqualTo(1);
                } else {
                    assertThat(result.err()).contains("stale conflict analysis");
                    assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions WHERE draft_id=?::uuid", id)).isZero();
                    assertThat(sql.queryForObject("SELECT ai_conflict_stale(?::uuid)", Boolean.class, id)).isTrue();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the check-to-commit interval (V43 lock)

    private long rev(UUID rule) { return sql.queryForObject("SELECT row_revision FROM rules WHERE rule_id=?", Long.class, rule); }

    private UUID ruleOf(String draftId) { return UUID.fromString(sql.queryForObject("SELECT definition->>'ruleId' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, draftId)); }

    private void awaitBlockedOnConflictLock() throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            if (count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND objid=7066001") > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("no session is waiting for the conflict-state lock");
    }

    @Test
    void p6_043_aRelevantWriterInFlightBlocksTheApprovalCheckUntilItCommitsAndTheApprovalThenSeesItsChange() throws Exception {
        UUID target = device();
        DraftOutcome.Created c = conflictedDraft(target);
        String id = c.draftId().toString();
        UUID joiner = typedRule(device(), "Joins the group late", 9, List.of(new com.nexus.rule.domain.TriggerSubscription(com.nexus.rule.domain.TriggerType.ACCEPTED_TELEMETRY)),
                List.of(temperatureAbove("40")), List.of(ledCommand(device(), true)));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection writer = sql.getDataSource().getConnection()) {
            writer.setAutoCommit(false);
            try (java.sql.PreparedStatement p = writer.prepareStatement("INSERT INTO rule_exclusion_members(group_id, rule_id, rank) VALUES (?, ?, 5)")) {
                p.setObject(1, lastGroup);
                p.setObject(2, joiner);
                p.executeUpdate();           // uncommitted: the group's membership is changing, the writer holds the lock shared
            }
            var approval = pool.submit(() -> cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "race", "--acknowledge-conflicts", "yes"));
            awaitBlockedOnConflictLock();
            assertThat(approval.isDone()).as("the approval check waits for the writer in flight").isFalse();
            assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions WHERE draft_id=?::uuid", id)).isZero();
            writer.commit();
            Cli result = approval.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.code()).isEqualTo(1);
            assertThat(result.err()).contains("stale conflict analysis");
            assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions WHERE draft_id=?::uuid", id)).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void p6_043_anApprovalInFlightBlocksARelevantWriterUntilItCommits() throws Exception {
        UUID target = device();
        DraftOutcome.Created c = conflictedDraft(target);
        String id = c.draftId().toString();
        UUID joiner = typedRule(device(), "Joins the group late", 9, List.of(new com.nexus.rule.domain.TriggerSubscription(com.nexus.rule.domain.TriggerType.ACCEPTED_TELEMETRY)),
                List.of(temperatureAbove("40")), List.of(ledCommand(device(), true)));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection approver = java.sql.DriverManager.getConnection(url(), "appr_it", "appr")) {
            approver.setAutoCommit(false);
            try (java.sql.PreparedStatement p = approver.prepareStatement("SELECT ai_draft_decide(?::uuid, ?, 'APPROVED', 'in flight', 'someone')")) {
                p.setString(1, id);
                p.setString(2, c.definitionHash());
                p.executeQuery();            // decided, not committed: the check has passed and the approver holds the lock exclusively
            }
            var writer = pool.submit(() -> exclusionGroups.setMember(lastGroup, joiner, 5));
            awaitBlockedOnConflictLock();
            assertThat(writer.isDone()).as("a relevant change cannot commit between the check and the approval commit").isFalse();
            assertThat(count("SELECT count(*) FROM rule_exclusion_members WHERE rule_id=?", joiner)).isZero();
            approver.commit();
            writer.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions WHERE draft_id=?::uuid AND decision='APPROVED'", id)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM rule_exclusion_members WHERE rule_id=?", joiner)).isEqualTo(1);
            // the change landed after the approval, so the approved version can no longer be activated on the old analysis
            UUID rule = ruleOf(id);
            assertThatThrownBy(() -> lifecycle.transition(rule, "activate", rev(rule))).hasMessageContaining("stale conflict analysis");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void p6_043_anApprovedAiRuleIsRecheckedWhenItIsResumed() {
        UUID target = device();
        DraftOutcome.Created c = conflictedDraft(target);
        String id = c.draftId().toString();
        assertThat(cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "ok", "--acknowledge-conflicts", "yes").code()).isEqualTo(0);
        UUID rule = ruleOf(id);
        lifecycle.transition(rule, "activate", rev(rule));
        assertThat(sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?", String.class, rule)).isEqualTo("ACTIVE");
        lifecycle.transition(rule, "pause", rev(rule));
        lifecycle.transition(rule, "resume", rev(rule));                  // nothing relevant changed: allowed
        assertThat(sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?", String.class, rule)).isEqualTo("ACTIVE");

        lifecycle.transition(rule, "pause", rev(rule));
        anotherRuleCommands(target);                                      // a relevant change while the AI rule is paused
        assertThatThrownBy(() -> lifecycle.transition(rule, "resume", rev(rule))).hasMessageContaining("stale conflict analysis");
        assertThat(sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?", String.class, rule)).isEqualTo("PAUSED");
    }

    @Test
    void p6_043_mixedConcurrentApprovalsGroupChangesAndRuleSavesNeverDeadlock() throws Exception {
        List<DraftOutcome.Created> pending = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) pending.add(conflictedDraft(device()));
        UUID group = lastGroup;
        List<UUID> members = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) members.add(typedRule(device(), "Stress member " + i, 8, List.of(new com.nexus.rule.domain.TriggerSubscription(com.nexus.rule.domain.TriggerType.ACCEPTED_TELEMETRY)),
                List.of(temperatureAbove("40")), List.of(ledCommand(device(), true))));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        List<java.util.concurrent.Future<?>> work = new java.util.ArrayList<>();
        try {
            for (int t = 0; t < 2; t++) {
                int base = t * 3;
                work.add(pool.submit(() -> {
                    for (int i = 0; i < 3; i++) {
                        DraftOutcome.Created d = pending.get(base + i);
                        Cli r = cli("appr_it", "approve", "--draft", d.draftId().toString(), "--hash", d.definitionHash(), "--reason", "stress", "--acknowledge-conflicts", "yes");
                        assertThat(r.code() == 0 || r.err().contains("stale conflict analysis")).as(r.err()).isTrue();
                    }
                }));
            }
            for (int t = 0; t < 2; t++) {
                UUID mine = members.get(t);
                work.add(pool.submit(() -> {
                    while (!stop.get()) { exclusionGroups.setMember(group, mine, 5); exclusionGroups.removeMember(group, mine); }
                }));
            }
            for (int t = 2; t < 4; t++) {
                UUID mine = members.get(t);
                work.add(pool.submit(() -> {
                    while (!stop.get()) { lifecycle.transition(mine, "pause", rev(mine)); lifecycle.transition(mine, "resume", rev(mine)); }
                }));
            }
            for (int i = 0; i < 2; i++) work.get(i).get(120, java.util.concurrent.TimeUnit.SECONDS);   // the approvers finish
            stop.set(true);
            for (int i = 2; i < work.size(); i++) work.get(i).get(60, java.util.concurrent.TimeUnit.SECONDS);   // an exception here would be a deadlock or a failure
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted AND objid=7066001")).isZero();
    }

    @Test
    void p6_043_theActivationGuardRecomputesAfterItHoldsTheLockSoAWriterCommittingDuringItsWaitIsSeen() throws Exception {
        UUID target = device();
        DraftOutcome.Created c = conflictedDraft(target);
        String id = c.draftId().toString();
        assertThat(cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "ok", "--acknowledge-conflicts", "yes").code()).isEqualTo(0);
        UUID rule = ruleOf(id);
        UUID joiner = typedRule(device(), "Joins the group during the activation", 9, List.of(new com.nexus.rule.domain.TriggerSubscription(com.nexus.rule.domain.TriggerType.ACCEPTED_TELEMETRY)),
                List.of(temperatureAbove("40")), List.of(ledCommand(device(), true)));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection writer = sql.getDataSource().getConnection(); java.sql.Connection activator = sql.getDataSource().getConnection()) {
            writer.setAutoCommit(false);
            try (java.sql.PreparedStatement p = writer.prepareStatement("INSERT INTO rule_exclusion_members(group_id, rule_id, rank) VALUES (?, ?, 5)")) {
                p.setObject(1, lastGroup);
                p.setObject(2, joiner);
                p.executeUpdate();
            }
            // the raw statement bypasses the service lock: the database guard itself must wait for the writer and then see its change
            var activation = pool.submit(() -> {
                try (java.sql.PreparedStatement p = activator.prepareStatement(
                        "UPDATE rules SET active_version_id = working_version_id, enabled = true, lifecycle = 'ACTIVE' WHERE rule_id = ?")) {
                    p.setObject(1, rule);
                    p.executeUpdate();
                    return "ACTIVATED";
                } catch (java.sql.SQLException refused) {
                    return refused.getSQLState() + " " + refused.getMessage();
                }
            });
            awaitBlockedOnConflictLock();
            assertThat(activation.isDone()).isFalse();
            writer.commit();
            String outcome = activation.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(outcome).startsWith("P0A01").contains("stale conflict analysis");
            assertThat(sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?", String.class, rule)).isNotEqualTo("ACTIVE");
        } finally {
            pool.shutdownNow();
        }
    }
}
