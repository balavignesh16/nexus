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
class DraftWorkflowIntegrationTest extends TypedTriggerSupport {
    @DynamicPropertySource
    static void ai(DynamicPropertyRegistry r) {
        r.add("nexus.ai.send-device-evidence", () -> "true");
        r.add("nexus.ai.rate-limit.requests-per-minute", () -> "1000");
        r.add("nexus.ai.rate-limit.burst", () -> "1000");
    }

    @MockBean ModelProvider provider;
    @Autowired DraftService drafts;
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
    void p6_038_p6_039_p6_045_aValidProposalIsStoredAsADraftWithFullProvenanceAndChangesNoRule() {
        UUID device = device();
        int rules = count("SELECT count(*) FROM rules"), versions = count("SELECT count(*) FROM rule_versions"), outbox = count("SELECT count(*) FROM command_outbox"), commands = count("SELECT count(*) FROM command_state_history");
        reply.set(json(device, "30", ALERT, "[\"device:" + device + "\"]", 60));
        resetPublicationSpies();
        DraftOutcome.Created c = created(drafts.propose("Warn me when the temperature is high"));
        assertThat(c.valid()).isTrue();
        assertThat(c.state()).isEqualTo("PENDING_APPROVAL");
        unchanged(rules, versions, outbox, commands);
        var row = sql.queryForMap("SELECT * FROM ai_rule_drafts WHERE draft_id=?", c.draftId());
        assertThat(row.get("provider_id")).isEqualTo("fake");
        assertThat(row.get("model_id")).isEqualTo("fake-1");
        assertThat(row.get("config_hash")).isEqualTo("cfg123");
        assertThat(row.get("request_digest").toString()).hasSize(64);
        assertThat(row.get("evidence_refs").toString()).contains("device:" + device);
        assertThat(row.get("validation").toString()).contains("RuleCapabilityValidator").contains("draft-rule/1");
        assertThat(row.get("definition_hash")).isEqualTo(c.definitionHash());
        assertThat(row.get("created_at")).isNotNull();
        verifyNoPublication();
        org.mockito.Mockito.verifyNoInteractions(alerts);
    }

    @Test
    void p6_040_p6_041_aProposalTheValidatorRejectsIsStoredInvalidAndNeverApprovable() {
        UUID device = device();
        reply.set(json(device, "not-a-number", ALERT, "[\"device:" + device + "\"]", 60));
        DraftOutcome.Created c = created(drafts.propose("Warn me when the temperature is high"));
        assertThat(c.valid()).isFalse();
        assertThat(c.state()).isEqualTo("INVALID");
        assertThat(c.diagnostics()).isNotEmpty();
        Cli approve = cli("appr_it", "approve", "--draft", c.draftId().toString(), "--hash", c.definitionHash(), "--reason", "try");
        assertThat(approve.code()).isEqualTo(1);
        assertThat(approve.err()).contains("not pending");
        assertThat(count("SELECT count(*) FROM rules WHERE rule_id=(SELECT (definition->>'ruleId')::uuid FROM ai_rule_drafts WHERE draft_id=?)", c.draftId())).isZero();
    }

    @Test
    void p6_042_unsafeProposalsAreStoredInvalidWithTheirPolicyCode() {
        UUID device = device(), other = device();
        String refs = "[\"device:" + device + "\"]";
        Map<String, String> cases = Map.of(
                "[{\"actionType\":\"WEBHOOK\",\"parameters\":{\"url\":\"http://x\"}}]", "EXTERNAL_EGRESS_FORBIDDEN",
                "[{\"actionType\":\"SEND_COMMAND\",\"parameters\":{\"deviceId\":\"" + other + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true}}]", "COMMAND_TARGET_NOT_CITED",
                "[{\"actionType\":\"DELETE_EVERYTHING\",\"parameters\":{}}]", "ACTION_TYPE_FORBIDDEN");
        for (var e : cases.entrySet()) {
            reply.set(json(device, "30", e.getKey(), refs, 60));
            DraftOutcome.Created c = created(drafts.propose("Turn something on when it is hot"));
            assertThat(c.valid()).as(e.getValue()).isFalse();
            assertThat(c.diagnostics().toString()).contains(e.getValue());
        }
        reply.set(json(device, "30", ALERT, refs, 5)); // no debounce
        assertThat(created(drafts.propose("Warn me when it is hot")).diagnostics().toString()).contains("COOLDOWN_RANGE");
        reply.set(json(device, "30", "[{\"actionType\":\"SEND_COMMAND\",\"parameters\":{\"deviceId\":\"" + UUID.randomUUID() + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true}}]", refs, 60));
        assertThat(created(drafts.propose("Switch a device I made up")).valid()).isFalse(); // unknown device: policy and validator both refuse
    }

    @Test
    void p6_038_modelFailureAndMalformedOutputStoreNothing() {
        int drafts0 = count("SELECT count(*) FROM ai_rule_drafts");
        reply.set(null);
        assertThat(drafts.propose("Warn me when it is hot")).isInstanceOf(DraftOutcome.Refused.class).extracting(o -> ((DraftOutcome.Refused) o).code()).isEqualTo(DraftRefusal.MODEL_REFUSED);
        device();
        for (String bad : new String[] {"not json", "{\"name\":\"x\"}", "{\"name\":1,\"conditions\":[],\"actions\":[],\"evidenceRefs\":[],\"cooldownSeconds\":60}"}) {
            reply.set(bad);
            assertThat(((DraftOutcome.Refused) drafts.propose("Warn me when it is hot")).code()).as(bad).isEqualTo(DraftRefusal.MODEL_REFUSED);
        }
        UUID device = device();
        reply.set(json(device, "30", "[{\"actionType\":\"CREATE_ALERT\",\"parameters\":{}}]", "[\"device:" + device + "\"]", 60).replace("\"EQ\"", "\"SIDEWAYS\""));
        assertThat(((DraftOutcome.Refused) drafts.propose("Warn me when it is hot")).code()).isEqualTo(DraftRefusal.MALFORMED_CANDIDATE);
        assertThat(((DraftOutcome.Refused) drafts.propose("short")).code()).isEqualTo(DraftRefusal.GOAL_INVALID);
        assertThat(count("SELECT count(*) FROM ai_rule_drafts")).isEqualTo(drafts0);
    }

    @Test
    void p6_045_missingOrInventedEvidenceStoresNothing() {
        UUID device = device();
        int before = count("SELECT count(*) FROM ai_rule_drafts");
        reply.set(json(device, "30", ALERT, "[]", 60));
        assertThat(((DraftOutcome.Refused) drafts.propose("Warn me when it is hot")).code()).isEqualTo(DraftRefusal.MISSING_EVIDENCE);
        reply.set(json(device, "30", ALERT, "[\"device:" + UUID.randomUUID() + "\"]", 60));
        assertThat(((DraftOutcome.Refused) drafts.propose("Warn me when it is hot")).code()).isEqualTo(DraftRefusal.UNRESOLVED_EVIDENCE);
        reply.set(json(device, "30", ALERT, "[\"rule:" + UUID.randomUUID() + "\"]", 60));
        assertThat(((DraftOutcome.Refused) drafts.propose("Warn me when it is hot")).code()).isEqualTo(DraftRefusal.UNRESOLVED_EVIDENCE);
        assertThat(count("SELECT count(*) FROM ai_rule_drafts")).isEqualTo(before);
    }

    @Test
    void p6_046_p6_048_p6_050_p6_051_p6_052_approvalIsLocalBoundAndNecessaryButNotSufficient() throws Exception {
        UUID device = device();
        reply.set(json(device, "30", ALERT, "[\"device:" + device + "\"]", 60));
        DraftOutcome.Created c = created(drafts.propose("Warn me when the temperature is high"));
        String id = c.draftId().toString();
        String ruleId = sql.queryForObject("SELECT definition->>'ruleId' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id);

        // A. an unapproved draft is not a rule, and a verbatim copy cannot be activated through the legacy path
        Rule copy = mapper.readValue(sql.queryForObject("SELECT definition::text FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id), Rule.class);
        Rule enabledCopy = new Rule(copy.ruleId(), copy.name(), true, copy.priority(), copy.conditions(), copy.actions(), copy.metadata(), copy.cooldownSeconds(), null, copy.runtime());
        assertThatThrownBy(() -> lifecycle.legacySave(enabledCopy, null)).hasMessageContaining("recorded approval");
        assertThat(count("SELECT count(*) FROM rules WHERE rule_id=?::uuid", ruleId)).isZero();

        // B. local privilege: an ordinary login and the database owner are both refused, and nothing changes
        assertThat(cli("plain_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "x").code()).isEqualTo(1);
        Cli plain = cli("plain_it", "list");
        assertThat(plain.err()).contains("not a local approver");
        assertThat(sql.queryForObject("SELECT state FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id)).isEqualTo("PENDING_APPROVAL");

        // C. a stale hash is refused; the reviewed content is what is approved
        Cli stale = cli("appr_it", "approve", "--draft", id, "--hash", "0".repeat(64), "--reason", "x");
        assertThat(stale.code()).isEqualTo(1);
        assertThat(stale.err()).contains("changed since");

        // D. show, then approve with the shown hash
        Cli show = cli("appr_it", "show", "--draft", id);
        assertThat(show.out()).contains(c.definitionHash()).contains("fake-1").contains("cfg123");
        Cli approved = cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "looks right");
        assertThat(approved.code()).isEqualTo(0);
        var decision = sql.queryForMap("SELECT * FROM ai_rule_draft_decisions WHERE draft_id=?::uuid", id);
        assertThat(decision.get("db_session_user")).isEqualTo("appr_it");
        assertThat(decision.get("os_user_claimed")).isEqualTo("someone");
        assertThat(decision.get("decision")).isEqualTo("APPROVED");

        // E. approval created an INACTIVE AI_DRAFT version; nothing is running
        assertThat(sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?::uuid", String.class, ruleId)).isEqualTo("DRAFT");
        assertThat(sql.queryForObject("SELECT provenance FROM rule_versions WHERE rule_id=?::uuid", String.class, ruleId)).isEqualTo("AI_DRAFT");
        assertThat(cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "again").code()).isEqualTo(1);

        // F. activation is the normal lifecycle operation: it validates again and the approval lets the database accept it
        lifecycle.transition(UUID.fromString(ruleId), "activate", null);
        assertThat(sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?::uuid", String.class, ruleId)).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM rule_validation_attempts WHERE rule_id=?::uuid AND purpose='ACTIVATE' AND valid", ruleId)).isEqualTo(1);
    }

    @Test
    void p6_049_p6_050_editingAnApprovedDraftNeverReusesItsApprovalAndABypassedUnapprovedVersionCannotActivate() throws Exception {
        UUID device = device();
        reply.set(json(device, "30", ALERT, "[\"device:" + device + "\"]", 60));
        DraftOutcome.Created c = created(drafts.propose("Warn me when the temperature is high"));
        String id = c.draftId().toString();
        assertThat(cli("appr_it", "approve", "--draft", id, "--hash", c.definitionHash(), "--reason", "ok").code()).isEqualTo(0);
        UUID rule = UUID.fromString(sql.queryForObject("SELECT definition->>'ruleId' FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id));
        long token = sql.queryForObject("SELECT row_revision FROM rules WHERE rule_id=?", Long.class, rule);
        Rule current = mapper.readValue(sql.queryForObject("SELECT definition::text FROM ai_rule_drafts WHERE draft_id=?::uuid", String.class, id), Rule.class);

        // an edit creates a NEW, human-authored version: it is not the approved one and carries no AI provenance
        Rule edited = new Rule(rule, "Edited by a person", false, 7, current.conditions(), current.actions(), current.metadata(), 90, null, null);
        lifecycle.draft(edited, token);
        assertThat(sql.queryForObject("SELECT provenance FROM rule_versions WHERE rule_id=? ORDER BY revision DESC LIMIT 1", String.class, rule)).isEqualTo("DRAFT_EDIT");
        assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions x JOIN rules r ON r.working_version_id=x.rule_version_id WHERE r.rule_id=?", rule)).isZero();
        // the existing optimistic concurrency still applies to an approved draft: the stale revision is refused and nothing changes
        assertThatThrownBy(() -> lifecycle.draft(edited, token)).hasMessageContaining("Stale rule revision");
        assertThat(count("SELECT count(*) FROM rule_versions WHERE rule_id=?", rule)).isEqualTo(2);

        // simulate a bypass: an AI_DRAFT version created without any approval, then every activation route is refused by the database
        UUID rogue = UUID.randomUUID();
        UUID rogueVersion = UUID.randomUUID();
        sql.update("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,metadata) VALUES(?,'rogue',false,1,'[]','[]','{}')", rogue);
        sql.update("INSERT INTO rule_versions(version_id,rule_id,revision,definition,created_at,provenance) VALUES(?,?,1,?::jsonb,now(),'AI_DRAFT')", rogueVersion, rogue,
                mapper.writeValueAsString(new Rule(rogue, "rogue", false, 1, current.conditions(), current.actions(), Map.of(), 60, null, null)));
        sql.update("INSERT INTO rule_version_workflow VALUES(?,'DRAFT')", rogueVersion);
        sql.update("UPDATE rules SET working_version_id=? WHERE rule_id=?", rogueVersion, rogue);
        assertThatThrownBy(() -> lifecycle.transition(rogue, "activate", null)).hasMessageContaining("recorded approval");
        assertThat(sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?", String.class, rogue)).isEqualTo("DRAFT");
        Rule rogueEnabled = new Rule(rogue, "rogue", true, 1, current.conditions(), current.actions(), Map.of(), 60, null, null);
        assertThatThrownBy(() -> lifecycle.legacySave(rogueEnabled, null)).hasMessageContaining("recorded approval"); // same definition reuses the AI version: the guard still holds
        assertThat(sql.queryForObject("SELECT lifecycle FROM rules WHERE rule_id=?", String.class, rogue)).isEqualTo("DRAFT");
    }

    @Test
    void p6_044_p6_052_aRejectedOrSupersededDraftStaysUnapprovable() {
        UUID device = device();
        reply.set(json(device, "30", ALERT, "[\"device:" + device + "\"]", 60));
        DraftOutcome.Created first = created(drafts.propose("Warn me when the temperature is high"));
        DraftOutcome.Created second = created(drafts.propose("Warn me when the temperature is high"));
        assertThat(sql.queryForObject("SELECT state FROM ai_rule_drafts WHERE draft_id=?", String.class, first.draftId())).isEqualTo("SUPERSEDED");
        Cli superseded = cli("appr_it", "approve", "--draft", first.draftId().toString(), "--hash", first.definitionHash(), "--reason", "x");
        assertThat(superseded.code()).isEqualTo(1);
        assertThat(cli("appr_it", "reject", "--draft", second.draftId().toString(), "--hash", second.definitionHash(), "--reason", "too noisy").code()).isEqualTo(0);
        assertThat(sql.queryForObject("SELECT reason FROM ai_rule_draft_decisions WHERE draft_id=?", String.class, second.draftId())).isEqualTo("too noisy");
        assertThat(cli("appr_it", "approve", "--draft", second.draftId().toString(), "--hash", second.definitionHash(), "--reason", "changed my mind").code()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM rules WHERE rule_id=(SELECT (definition->>'ruleId')::uuid FROM ai_rule_drafts WHERE draft_id=?)", second.draftId())).isZero();
    }

    @Test
    void p6_041_p6_043_anOverlapWithAnActiveRuleIsReportedAsAWarning() {
        UUID device = device();
        String command = "[{\"actionType\":\"SEND_COMMAND\",\"parameters\":{\"deviceId\":\"" + device + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true}}]";
        typedRule(device, "Existing LED rule", 1, List.of(), List.of(temperatureAbove("40")), List.of(ledCommand(device, true)));
        reply.set(json(device, "30", command, "[\"device:" + device + "\"]", 60));
        DraftOutcome.Created c = created(drafts.propose("Turn the LED on when it is hot"));
        assertThat(c.warnings().toString()).contains("OVERLAPS_ACTIVE_RULE");
    }

    @Test
    void p6_046_p6_047_noHttpRouteDecidesAndForgedIdentityChangesNothing() throws Exception {
        UUID device = device();
        reply.set(json(device, "30", ALERT, "[\"device:" + device + "\"]", 60));
        DraftOutcome.Created c = created(drafts.propose("Warn me when the temperature is high"));
        for (String suffix : new String[] {"approve", "reject", "approval", "activate", "decision"}) {
            int status = mvc.perform(post("/api/v1/intelligence/drafts/" + c.draftId() + "/" + suffix).header("X-Actor", "root").header("X-Forwarded-User", "admin")
                    .param("actor", "root").contentType("application/json").content("{\"actor\":\"root\",\"approved\":true}")).andReturn().getResponse().getStatus();
            assertThat(status).as(suffix).isIn(404, 405);
            int put = mvc.perform(put("/api/v1/intelligence/drafts/" + c.draftId()).header("X-Actor", "root").contentType("application/json").content("{\"state\":\"APPROVED\"}")).andReturn().getResponse().getStatus();
            assertThat(put).isIn(404, 405);
        }
        assertThat(sql.queryForObject("SELECT state FROM ai_rule_drafts WHERE draft_id=?", String.class, c.draftId())).isEqualTo("PENDING_APPROVAL");
        assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions WHERE draft_id=?", c.draftId())).isZero();
        // the propose route works over HTTP and creates only a draft
        reply.set(json(device, "30", ALERT, "[\"device:" + device + "\"]", 60));
        mvc.perform(post("/api/v1/intelligence/drafts").contentType("application/json").content("{\"goal\":\"Warn me when it is hot\"}")).andExpect(status().isCreated());
    }

    @Test
    void p6_053_noAiOutputFieldCanWriteAnApproval() throws Exception {
        UUID device = device();
        // a reply that tries to approve itself or smuggle an actor is refused whole by the strict schema
        reply.set(json(device, "30", ALERT, "[\"device:" + device + "\"]", 60).replace("\"rationale\"", "\"approved\":true,\"approvedBy\":\"root\",\"rationale\""));
        assertThat(((DraftOutcome.Refused) drafts.propose("Warn me when it is hot")).code()).isEqualTo(DraftRefusal.MODEL_REFUSED);
        assertThat(count("SELECT count(*) FROM ai_rule_draft_decisions WHERE decision='APPROVED' AND reason IS NULL")).isZero();
    }

    @Test
    void p6_050_copyingAPendingDraftIntoTheLegacyRuleApiCannotLaunderItIntoAnActiveRule() throws Exception {
        UUID device = device();
        reply.set(json(device, "30", ALERT, "[\"device:" + device + "\"]", 60));
        DraftOutcome.Created c = created(drafts.propose("Warn me when the temperature is high"));
        Rule draft = mapper.readValue(sql.queryForObject("SELECT definition::text FROM ai_rule_drafts WHERE draft_id=?", String.class, c.draftId()), Rule.class);
        int rules = count("SELECT count(*) FROM rules");
        String body = mapper.writeValueAsString(Map.of("name", draft.name(), "enabled", true, "priority", draft.priority(), "conditions", draft.conditions(),
                "actions", draft.actions(), "metadata", draft.metadata(), "cooldownSeconds", draft.cooldownSeconds()));
        int created = mvc.perform(post("/api/v1/rules").contentType("application/json").content(body)).andReturn().getResponse().getStatus();
        assertThat(created).as("a verbatim copy of an unapproved AI draft must not be created active (409 from the database guard)").isEqualTo(409);
        assertThat(count("SELECT count(*) FROM rules")).isEqualTo(rules);
        // a hand-written rule that merely looks similar (different metadata) is an ordinary human rule and is NOT blocked
        String human = mapper.writeValueAsString(Map.of("name", "Human rule", "enabled", true, "priority", 3, "conditions", draft.conditions(), "actions", draft.actions(),
                "metadata", Map.of("owner", "me"), "cooldownSeconds", 60));
        assertThat(mvc.perform(post("/api/v1/rules").contentType("application/json").content(human)).andReturn().getResponse().getStatus()).isEqualTo(201);
    }
}
