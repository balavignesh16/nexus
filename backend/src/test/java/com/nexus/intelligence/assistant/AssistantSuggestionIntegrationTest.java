package com.nexus.intelligence.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.intelligence.assistant.AssistantAnswer.Kind;
import com.nexus.intelligence.assistant.AssistantAnswer.Statement;
import com.nexus.intelligence.draft.DraftConflictAnalyzer;
import com.nexus.intelligence.draft.DraftService;
import com.nexus.intelligence.draft.store.DraftStore;
import com.nexus.intelligence.model.AiCallGuard;
import com.nexus.intelligence.model.GuardSettings;
import com.nexus.intelligence.model.ModelGateway;
import com.nexus.intelligence.model.ModelProvider;
import com.nexus.intelligence.model.ModelProviderException;
import com.nexus.intelligence.model.ProviderIdentity;
import com.nexus.intelligence.retrieval.EvidenceRetrievalService;
import com.nexus.intelligence.trend.HardLimitDrafts;
import com.nexus.intelligence.trend.TrendService;
import com.nexus.rule.application.RuleCapabilityValidator;
import com.nexus.rule.application.RuleExclusionGroupService;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.TriggerSubscription;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.persistence.RuleVersionStore;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6.11 on real PostgreSQL: a chat request for a rule becomes at most a DRAFT through the existing draft workflow; nothing is approved, activated, resumed or sent, the
 * validation and conflict results are shown unchanged, and no model text is shown. The model (scripted here) only ever proposes a draft that the platform validates.
 */
class AssistantSuggestionIntegrationTest extends TypedTriggerSupport {
    @DynamicPropertySource
    static void assistantOn(DynamicPropertyRegistry r) {
        r.add("nexus.assistant.enabled", () -> "true");
        r.add("nexus.ai.rate-limit.requests-per-minute", () -> "1000");
        r.add("nexus.ai.rate-limit.burst", () -> "1000");
    }

    @Autowired AssistantService assistant;
    @Autowired EvidenceRetrievalService retrieval;
    @Autowired TrendService trends;
    @Autowired HardLimitDrafts hardLimits;
    @Autowired DraftStore store;
    @Autowired RuleCapabilityValidator validator;
    @Autowired RuleVersionStore ruleStore;
    @Autowired DraftConflictAnalyzer conflicts;
    @Autowired RuleExclusionGroupService groups;
    @Autowired ModelGateway defaultGateway;
    @Autowired ObjectMapper mapper;

    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private UUID named(String name) {
        UUID d = device();
        sql.update("UPDATE devices SET name=? WHERE id=?", name, d);
        return d;
    }

    private static String text(AssistantAnswer a) {
        List<Statement> all = new ArrayList<>(a.statements());
        all.addAll(a.additional());
        return all.stream().map(Statement::text).collect(Collectors.joining(" | "));
    }

    private String sideEffects() {
        return sql.queryForObject("""
                SELECT (SELECT count(*) FROM rules)||'/'||(SELECT count(*) FROM rule_versions)||'/'||(SELECT count(*) FROM rules WHERE lifecycle='ACTIVE')||'/'||(SELECT coalesce(sum(row_revision),0) FROM rules)||'/'||
                       (SELECT count(*) FROM ai_rule_draft_decisions)||'/'||(SELECT count(*) FROM action_history)||'/'||(SELECT count(*) FROM command_outbox)||'/'||(SELECT count(*) FROM current_desired_twin_state)||'/'||
                       (SELECT count(*) FROM rule_exclusion_members)""", String.class);
    }

    private static void sourcesResolve(AssistantAnswer a) {
        Set<String> refs = a.sources().stream().map(e -> e.ref()).collect(Collectors.toSet());
        for (Statement s : a.statements()) if (s.kind() == Kind.FACT) {
            assertThat(s.refs()).as(s.text()).isNotEmpty();
            assertThat(refs).containsAll(s.refs());
        }
    }

    private static final class ScriptedProvider implements ModelProvider {
        final AtomicReference<String> reply = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        @Override public ProviderIdentity identity() { return new ProviderIdentity("fake", "scripted", "x"); }
        @Override public boolean available() { return true; }
        @Override public String complete(String prompt, int maxOutputChars) throws ModelProviderException {
            calls.incrementAndGet();
            return reply.get();
        }
    }

    private AssistantService withDraftModel(ScriptedProvider provider, boolean consent) {
        GuardSettings settings = new GuardSettings(2, Duration.ofSeconds(5), 0, 5, Duration.ofSeconds(30), 20_000, 20_000);
        ModelGateway gateway = new ModelGateway(provider, new AiCallGuard(settings, Clock.systemUTC()), settings, consent);
        DraftService drafts = new DraftService(gateway, store, validator, ruleStore, conflicts, mapper, clock);
        return new AssistantService(retrieval, trends, defaultGateway, new SuggestionService(drafts, hardLimits, store, retrieval), clock);
    }

    private static String modelDraft(UUID device, String actions, String rationale) {
        return "{\"name\":\"AI high temperature\",\"priority\":5,\"cooldownSeconds\":60,\"rationale\":\"" + rationale + "\","
                + "\"conditions\":[{\"field\":\"DEVICE_ID\",\"operator\":\"EQ\",\"expectedValue\":\"" + device + "\"},{\"field\":\"VALUE\",\"operator\":\"GT\",\"expectedValue\":\"30\",\"capabilityType\":\"TEMPERATURE\"}],"
                + "\"actions\":" + actions + ",\"evidenceRefs\":[\"device:" + device + "\"]}";
    }

    // ------------------------------------------------------------------ the hard-limit draft through chat (no model)

    @Test
    void p6_202_p6_201_aHardLimitRequestInChatStoresOnlyAnInactiveDraftAndSaysWhatHappensNext() {
        named("Motor Papa1");
        String before = sideEffects();
        AssistantAnswer a = assistant.ask(null, "Draft a hard limit rule for Motor Papa1 temperature at 100 celsius");
        assertThat(a.status()).isEqualTo(AssistantAnswer.Status.DRAFT_STORED);
        assertThat(a.plan()).containsExactly("DRAFT_HARD_LIMIT");
        String t = text(a);
        assertThat(t).contains("A draft rule was stored for review").contains("state PENDING_APPROVAL").contains("passed the policy and the rule validator").contains("Conflict analysis")
                .contains("Nothing is active and nothing was sent").contains("this assistant can do neither");
        sourcesResolve(a);
        UUID draft = UUID.fromString(a.sources().get(0).ref().substring("draft:".length(), "draft:".length() + 36));
        var row = sql.queryForMap("SELECT state, valid, provider_id, definition->>'enabled' AS enabled, definition->'metadata'->>'origin' AS origin FROM ai_rule_drafts WHERE draft_id=?", draft);
        assertThat(row).containsEntry("state", "PENDING_APPROVAL").containsEntry("provider_id", "none").containsEntry("enabled", "false").containsEntry("origin", "TEMPLATE_DRAFT");
        String after = sideEffects();
        assertThat(after).as("no rule, version, approval, command or Twin row was created: the draft lives only in ai_rule_drafts").isEqualTo(before);
        // the stored draft cannot become an active rule without an approval
        Rule copy;
        try {
            copy = mapper.readValue(sql.queryForObject("SELECT definition::text FROM ai_rule_drafts WHERE draft_id=?", String.class, draft), Rule.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        Rule enabled = new Rule(copy.ruleId(), copy.name(), true, copy.priority(), copy.conditions(), copy.actions(), copy.metadata(), copy.cooldownSeconds(), null, copy.runtime());
        assertThatThrownBy(() -> lifecycle.legacySave(enabled, null)).hasMessageContaining("recorded approval");
        assertThat(sideEffects()).isEqualTo(before);
    }

    @Test
    void p6_203_noChatRequestCanApproveActivateOrSendAndAnActivationAddOnIsNamedAndIgnored() {
        named("Motor Quebec2");
        AssistantAnswer drafted = assistant.ask(null, "Draft a hard limit rule for Motor Quebec2 temperature at 100 celsius and activate it");
        assertThat(drafted.status()).isEqualTo(AssistantAnswer.Status.DRAFT_STORED);
        assertThat(String.join(" ", drafted.notices())).contains("cannot do either");
        UUID draft = UUID.fromString(drafted.sources().get(0).ref().substring("draft:".length(), "draft:".length() + 36));
        String before = sideEffects();
        for (String q : List.of("Approve draft " + draft, "Activate the draft rule " + draft, "Please approve the pending draft rule and activate it.", "Reject draft " + draft, "Enable rule " + draft,
                "Create a rule that turns off Motor Quebec2 at 90 degrees")) {
            AssistantAnswer a = assistant.ask(null, q);
            assertThat(a.status()).as(q).isEqualTo(AssistantAnswer.Status.REFUSED_WRITE);
            assertThat(a.statements()).as(q).isEmpty();
        }
        AssistantAnswer hint = assistant.ask(null, "Create a rule that turns off Motor Quebec2 at 90 degrees");
        assertThat(String.join(" ", hint.notices())).contains("I can draft a rule for review instead");
        assertThat(sideEffects()).isEqualTo(before);
        assertThat(sql.queryForObject("SELECT state FROM ai_rule_drafts WHERE draft_id=?", String.class, draft)).isEqualTo("PENDING_APPROVAL");
        assertThat(sql.queryForObject("SELECT count(*) FROM ai_rule_draft_decisions WHERE draft_id=?", Integer.class, draft)).isZero();
    }

    @Test
    void p6_204_aLimitInTheWrongUnitAnUnknownDeviceAnAmbiguousNameAndAMissingUnitAreShownAsWhatTheyAre() {
        named("Motor Romeo3");
        AssistantAnswer wrong = assistant.ask(null, "Draft a hard limit rule for Motor Romeo3 temperature at 100 fahrenheit");
        assertThat(wrong.status()).isEqualTo(AssistantAnswer.Status.DRAFT_STORED);
        String t = text(wrong);
        assertThat(t).contains("state INVALID").contains("INVALID and can never be approved").contains("validator");
        UUID draft = UUID.fromString(wrong.sources().get(0).ref().substring("draft:".length(), "draft:".length() + 36));
        assertThat(sql.queryForObject("SELECT state FROM ai_rule_drafts WHERE draft_id=?", String.class, draft)).isEqualTo("INVALID");
        // a stored INVALID draft cannot be approved by anyone: the database refuses (the approval function is not reachable from chat)
        named("Twin Sierra4");
        named("Twin Sierra4");
        AssistantAnswer ambiguous = assistant.ask(null, "Draft a hard limit rule for Twin Sierra4 temperature at 100 celsius");
        assertThat(ambiguous.status()).isEqualTo(AssistantAnswer.Status.DRAFT_NOT_STORED);
        assertThat(text(ambiguous)).contains("More than one device is named");
        AssistantAnswer unknown = assistant.ask(null, "Draft a rule for Nobody Tango5 temperature at 100 celsius with a hard limit");
        assertThat(unknown.status()).isIn(AssistantAnswer.Status.NEEDS_CLARIFICATION, AssistantAnswer.Status.DRAFT_NOT_STORED);
        AssistantAnswer noUnit = assistant.ask(null, "Draft a hard limit rule for Motor Romeo3 temperature at 100");
        assertThat(noUnit.status()).isEqualTo(AssistantAnswer.Status.NEEDS_CLARIFICATION);
        assertThat(noUnit.clarification()).contains("a unit is never assumed");
        AssistantAnswer badCapability = assistant.ask(null, "Draft a hard limit rule for Motor Romeo3 vibration at 5 mm");
        assertThat(badCapability.status()).isEqualTo(AssistantAnswer.Status.DRAFT_NOT_STORED);
        assertThat(text(badCapability)).contains("not a known capability type");
    }

    // ------------------------------------------------------------------ model-based drafts

    @Test
    void p6_202_withoutAModelAFreeFormRuleIsNotDraftedAndSaysWhy() {
        named("Fan Uniform6");
        String before = sideEffects();
        AssistantAnswer a = assistant.ask(null, "Draft a rule that turns the Fan Uniform6 on when it is hot");
        assertThat(a.status()).isEqualTo(AssistantAnswer.Status.DRAFT_NOT_STORED);
        assertThat(text(a)).contains("No model is configured").contains("A hard-limit rule needs no model");
        assertThat(sideEffects()).isEqualTo(before);
        assertThat(sql.queryForObject("SELECT count(*) FROM ai_rule_drafts WHERE request_digest IS NOT NULL AND definition::text LIKE '%Uniform6%'", Integer.class)).isZero();
    }

    @Test
    void p6_202_p6_204_aModelDraftIsValidatedByThePlatformAndEverythingShownIsPlatformWrittenFromTheStoredDraft() throws Exception {
        UUID device = named("Heater Victor7");
        UUID existing = typedRule(device(), "Existing LED owner", 1, TELEMETRY, List.of(temperatureAbove("40")), List.of(ledCommand(device, true)));
        String groupName = "chat-conflict-" + UUID.randomUUID();
        var group = groups.create(groupName);
        groups.setMember(group.groupId(), existing, 1);
        String before = sideEffects();

        ScriptedProvider provider = new ScriptedProvider();
        String command = "[{\"actionType\":\"SEND_COMMAND\",\"parameters\":{\"deviceId\":\"" + device + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":false}}]";
        provider.reply.set(modelDraft(device, command, "MODEL RATIONALE MARKER"));
        AssistantAnswer conflicted = withDraftModel(provider, true).ask(null, "Draft a rule that turns the LED of Heater Victor7 off when it is hot");
        assertThat(conflicted.status()).isEqualTo(AssistantAnswer.Status.DRAFT_STORED);
        assertThat(provider.calls.get()).isEqualTo(1);
        String t = text(conflicted);
        assertThat(t).contains("state PENDING_APPROVAL").contains("Conflict analysis against the active rules and exclusion groups: CONFLICTS.").contains("Existing LED owner").contains("exclusion group '" + groupName + "'");
        assertThat(mapper.writeValueAsString(conflicted)).as("no model text is shown").doesNotContain("MODEL RATIONALE MARKER").doesNotContain("scripted");
        sourcesResolve(conflicted);
        assertThat(sideEffects()).as("a conflicting draft is still only a draft").isEqualTo(before);

        // a policy violation (external egress) is shown as INVALID with its fixed code
        provider.reply.set(modelDraft(device, "[{\"actionType\":\"WEBHOOK\",\"parameters\":{\"url\":\"https://evil.example/x\"}}]", "x"));
        AssistantAnswer invalid = withDraftModel(provider, true).ask(null, "Draft a rule that posts to a webhook when Heater Victor7 is hot");
        assertThat(invalid.status()).isEqualTo(AssistantAnswer.Status.DRAFT_STORED);
        assertThat(text(invalid)).contains("state INVALID").contains("policy EXTERNAL_EGRESS_FORBIDDEN").doesNotContain("evil.example");
        assertThat(sideEffects()).isEqualTo(before);

        // bad output and a missing consent store nothing
        provider.reply.set("this is not json");
        AssistantAnswer malformed = withDraftModel(provider, true).ask(null, "Draft a rule for Heater Victor7 when it is hot");
        assertThat(malformed.status()).isEqualTo(AssistantAnswer.Status.DRAFT_NOT_STORED);
        int callsBefore = provider.calls.get();
        AssistantAnswer noConsent = withDraftModel(provider, false).ask(null, "Draft a rule for Heater Victor7 when it is hot");
        assertThat(noConsent.status()).isEqualTo(AssistantAnswer.Status.DRAFT_NOT_STORED);
        assertThat(text(noConsent)).contains("separate consent");
        assertThat(provider.calls.get()).as("no device information was sent without the consent").isEqualTo(callsBefore);
        assertThat(sideEffects()).isEqualTo(before);
        assertThat(sql.queryForObject("SELECT count(*) FROM ai_rule_draft_decisions", Integer.class)).isZero();
    }

    @Test
    void p6_201_adviceQuestionsReturnSuggestionsForAPersonAndExecuteNothing() {
        UUID d = named("Compressor Whiskey8");
        for (int i = 0; i < 260; i++)
            sql.update("INSERT INTO telemetry_history(id, device_id, timestamp, sensor_type, value, unit, capability_key, value_type, numeric_value) VALUES (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), d, Timestamp.from(now.minusSeconds(60L * (260 - i))), "TEMPERATURE", 21.0, "CELSIUS", "temperature", "NUMBER", 21.0);
        String before = sideEffects();
        AssistantAnswer a = assistant.ask(null, "What should I do about Compressor Whiskey8?");
        assertThat(a.plan()).containsExactlyInAnyOrder("RECOMMENDATIONS", "ANOMALIES");
        assertThat(a.status()).isEqualTo(AssistantAnswer.Status.ANSWERED);
        String t = text(a);
        assertThat(t).contains("Observation (").contains("not a confirmed fault".isEmpty() ? "" : "never a confirmed fault");
        if (t.contains("Suggestion for a person to review")) assertThat(t).contains("None. This is a suggestion for a person to review");
        assertThat(t.toLowerCase()).doesNotContain("i have turned").doesNotContain("i sent").doesNotContain("has been fixed");
        assertThat(sideEffects()).isEqualTo(before);
    }
}
