package com.nexus.intelligence.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.intelligence.assistant.AssistantAnswer.Kind;
import com.nexus.intelligence.assistant.AssistantAnswer.Statement;
import com.nexus.intelligence.draft.DraftOutcome;
import com.nexus.intelligence.draft.DraftService;
import com.nexus.intelligence.draft.store.DraftStore;
import com.nexus.intelligence.evidence.EvidenceItem;
import com.nexus.intelligence.model.RefusalCode;
import com.nexus.intelligence.retrieval.EvidenceResult;
import com.nexus.intelligence.retrieval.EvidenceRetrievalService;
import com.nexus.intelligence.retrieval.EvidenceStatus;
import com.nexus.intelligence.retrieval.LookupRequest;
import com.nexus.intelligence.retrieval.LookupType;
import com.nexus.intelligence.trend.HardLimitDrafts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The ONLY place the assistant touches the draft workflow, and only to CREATE A DRAFT through the existing validation: the model-free hard-limit template
 * ({@link HardLimitDrafts}) or the model-based draft ({@link DraftService#propose}), which apply the same policy, the Phase 3 validator and the mutual-exclusion analysis
 * and store the result for a person to review. A draft is inert: it is not a rule, it is never active, and this class has no way to approve, reject, activate, resume or
 * send anything (the approval is a local command with its own database role and the activation guard is a database trigger; neither is reachable from here, and a
 * boundary test pins the imports of this file). Everything shown about a draft is written by the platform from the STORED draft, never from model text.
 */
@Service
@ConditionalOnProperty(prefix = "nexus.assistant", name = "enabled", havingValue = "true")
public class SuggestionService {
    public record Outcome(AssistantAnswer.Status status, List<Statement> statements, List<EvidenceItem> sources, List<String> notices, UUID draftId) {}

    private static final String NEXT_STEP = "Nothing is active and nothing was sent. A local approver must approve this exact draft with the approval command, and activating the approved version is a separate step; "
            + "this assistant can do neither.";

    private final DraftService drafts;
    private final HardLimitDrafts hardLimits;
    private final DraftStore store;
    private final EvidenceRetrievalService retrieval;

    public SuggestionService(DraftService drafts, HardLimitDrafts hardLimits, DraftStore store, EvidenceRetrievalService retrieval) {
        this.drafts = drafts;
        this.hardLimits = hardLimits;
        this.store = store;
        this.retrieval = retrieval;
    }

    public Outcome draft(DeterministicRouter.DraftRequest request) {
        List<String> notices = new ArrayList<>();
        if (request.mentionsActivation()) notices.add("The question also asked for approval or activation. This assistant cannot do either; only a draft was prepared.");
        DraftOutcome outcome;
        if (request.hardLimit() != null) {
            DeterministicRouter.HardLimitSpec spec = request.hardLimit();
            EvidenceResult device = retrieval.lookup(LookupRequest.of(LookupType.DEVICE_STATE).withDeviceName(spec.deviceName()));
            if (device.status() != EvidenceStatus.OBSERVED || device.entity() == null || device.entity().id() == null)
                return notStored(device.status() == EvidenceStatus.INSUFFICIENT_EVIDENCE ? "More than one device is named \"" + spec.deviceName() + "\", so no draft was prepared: use a device id with the draft route."
                        : "No device named \"" + spec.deviceName() + "\" was found, so no draft was prepared.", notices);
            try {
                outcome = hardLimits.proposeAlertLimit(UUID.fromString(device.entity().id()), spec.capabilityKey(), spec.value(), spec.unit());
            } catch (IllegalArgumentException invalid) {
                return notStored("No draft was prepared: " + invalid.getMessage() + ".", notices);
            }
        } else {
            outcome = drafts.propose(request.goal());
        }
        if (outcome instanceof DraftOutcome.Refused refused) return notStored(refusal(refused), notices);
        DraftOutcome.Created created = (DraftOutcome.Created) outcome;
        return stored(created, notices);
    }

    private static Outcome notStored(String why, List<String> notices) {
        List<Statement> statements = List.of(new Statement("s1", Kind.NOTICE, why, List.of()));
        return new Outcome(AssistantAnswer.Status.DRAFT_NOT_STORED, statements, List.of(), notices, null);
    }

    private static String refusal(DraftOutcome.Refused r) {
        return switch (r.code()) {
            case MODEL_REFUSED -> r.modelRefusal() == RefusalCode.DISABLED
                    ? "No model is configured, so a free-form rule cannot be drafted. A hard-limit rule needs no model: say draft a hard limit rule for <device> <capability> at <value> <unit>."
                    : r.modelRefusal() == RefusalCode.NO_CONSENT ? "The model may not receive device information without the separate consent, so a free-form rule cannot be drafted. A hard-limit rule needs no model."
                    : "The model could not produce a usable draft (" + (r.modelRefusal() == null ? "refused" : r.modelRefusal().name()) + "); nothing was stored.";
            case GOAL_INVALID -> "The request is not a usable goal for a rule (3 to 500 characters); nothing was stored.";
            case NO_DEVICES -> "No device is registered, so there is nothing a rule could refer to; nothing was stored.";
            case MALFORMED_CANDIDATE -> "The model's proposal was not in the required form; nothing was stored.";
            case MISSING_EVIDENCE, UNRESOLVED_EVIDENCE -> "The model's proposal cited devices that could not be confirmed; nothing was stored.";
            case VALIDATOR_UNAVAILABLE -> "The rule validator was not available, so no draft was stored.";
        };
    }

    private Outcome stored(DraftOutcome.Created created, List<String> notices) {
        UUID id = created.draftId();
        String ref = "draft:" + id;
        List<Statement> out = new ArrayList<>();
        List<EvidenceItem> sources = new ArrayList<>();
        int[] n = {1};
        JsonNode validation = store.find(id).map(DraftStore.View::validation).orElse(null);
        String name = store.find(id).map(v -> v.definition().path("name").asText("")).orElse("");
        sources.add(EvidenceItem.present(ref + "/state", "draft state", created.state()));
        out.add(new Statement("s" + n[0]++, Kind.FACT, "A draft rule was stored for review" + (name.isBlank() ? "" : ": \"" + name + "\"") + " (draft " + id + "), state " + created.state() + ".", List.of(ref + "/state")));
        List<String> problems = new ArrayList<>();
        if (validation != null) {
            for (JsonNode v : validation.path("policyViolations")) problems.add("policy " + v.path("code").asText("?"));
            for (JsonNode v : validation.path("validatorDiagnostics")) problems.add("validator " + v.path("code").asText("?") + (v.hasNonNull("message") ? " (" + clip(v.get("message").asText()) + ")" : ""));
        }
        sources.add(EvidenceItem.present(ref + "/valid", "draft validity", String.valueOf(created.valid())));
        out.add(new Statement("s" + n[0]++, Kind.FACT, created.valid() ? "The draft passed the policy and the rule validator." : "The draft is INVALID and can never be approved: " + (problems.isEmpty() ? "see its validation record." : String.join("; ", problems.subList(0, Math.min(6, problems.size()))) + "."),
                List.of(ref + "/valid")));
        if (validation != null && validation.has("conflictAnalysis")) {
            JsonNode ca = validation.get("conflictAnalysis");
            String status = ca.path("status").asText("NOT_ANALYSED");
            StringBuilder text = new StringBuilder("Conflict analysis against the active rules and exclusion groups: " + status + ".");
            int shown = 0;
            for (JsonNode f : ca.path("findings")) {
                if (shown++ >= 5) break;
                text.append(' ').append(f.path("reason").asText(f.path("code").asText("")));
            }
            if ("UNRESOLVED".equals(status)) text.append(' ').append(ca.path("reason").asText(""));
            sources.add(EvidenceItem.present(ref + "/conflicts", "conflict analysis status", status));
            out.add(new Statement("s" + n[0]++, Kind.FACT, text.toString().strip(), List.of(ref + "/conflicts")));
        }
        if (validation != null && validation.path("warnings").size() > 0) {
            List<String> codes = new ArrayList<>();
            for (JsonNode w : validation.path("warnings")) codes.add(w.path("code").asText("?") + (w.hasNonNull("ruleId") ? " (rule " + w.get("ruleId").asText() + ")" : ""));
            sources.add(EvidenceItem.present(ref + "/warnings", "draft warnings", String.join(", ", codes)));
            out.add(new Statement("s" + n[0]++, Kind.FACT, "Warnings recorded with the draft: " + String.join(", ", codes.subList(0, Math.min(5, codes.size()))) + ".", List.of(ref + "/warnings")));
        }
        out.add(new Statement("s" + n[0]++, Kind.NOTICE, NEXT_STEP, List.of()));
        return new Outcome(AssistantAnswer.Status.DRAFT_STORED, out, sources, notices, id);
    }

    private static String clip(String s) {
        return s.length() > 160 ? s.substring(0, 160) : s;
    }
}
