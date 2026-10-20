package com.nexus.intelligence.assistant;

import com.nexus.intelligence.assistant.AssistantAnswer.Kind;
import com.nexus.intelligence.assistant.AssistantAnswer.Statement;
import com.nexus.intelligence.energy.EnergyService;
import com.nexus.intelligence.evidence.EvidenceItem;
import com.nexus.intelligence.model.DataBlock;
import com.nexus.intelligence.model.ModelGateway;
import com.nexus.intelligence.model.ModelOutcome;
import com.nexus.intelligence.model.ModelPurpose;
import com.nexus.intelligence.model.ModelRequest;
import com.nexus.intelligence.retrieval.EvidenceResult;
import com.nexus.intelligence.retrieval.EvidenceRetrievalService;
import com.nexus.intelligence.retrieval.EvidenceStatus;
import com.nexus.intelligence.retrieval.LookupRequest;
import com.nexus.intelligence.retrieval.LookupType;
import com.nexus.intelligence.retrieval.QuestionEvidence;
import com.nexus.intelligence.trend.HardLimit;
import com.nexus.intelligence.trend.TrendResult;
import com.nexus.intelligence.trend.TrendService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The NEXUS Assistant: one place to ask about the stored state of devices, rules, commands, readings, anomalies and suggestions.
 *
 * <p>Flow of a turn: the question is routed to FIXED read-only tools by {@link DeterministicRouter} (no model decides what runs); the tools run through
 * {@link EvidenceRetrievalService} (and the trend analysis), each in a bounded read-only transaction; {@link AnswerComposer} writes the answer sentences from the
 * returned evidence with fixed templates, every fact citing references that resolve; and, only when a model is configured, consented to and reachable, the model may
 * REORDER which of those sentences come first by selecting evidence references from the set that was presented (its choice is validated and any text it writes is
 * discarded). Offline, or when anything about the model is wrong, the same sentences appear in the deterministic order.
 *
 * <p>There is no tool that changes anything: no command, rule edit, approval, activation or draft creation is reachable from here (an architecture test enforces the
 * imports), and a request to change something is refused. Answers state what the evidence cannot show. NEXUS has no user authentication: a conversation id is a handle
 * for follow-up questions, not an identity, and the assistant is on only when {@code nexus.assistant.enabled=true} for a local or private environment.
 */
@Service
@ConditionalOnProperty(prefix = "nexus.assistant", name = "enabled", havingValue = "true")
public class AssistantService {
    public static final int MAX_QUESTION_CHARS = 500;
    static final int MAX_STATEMENTS = 60;
    static final int MAX_SOURCES = 200;

    private final EvidenceRetrievalService retrieval;
    private final TrendService trends;
    private final ModelGateway gateway;
    private final ConversationStore conversations;
    private final Clock clock;

    private final SuggestionService suggestions;
    private final EnergyService energy;

    @org.springframework.beans.factory.annotation.Autowired
    public AssistantService(EvidenceRetrievalService retrieval, TrendService trends, ModelGateway gateway, SuggestionService suggestions, EnergyService energy, Clock clock) {
        this.retrieval = retrieval;
        this.trends = trends;
        this.gateway = gateway;
        this.suggestions = suggestions;
        this.energy = energy;
        this.clock = clock;
        this.conversations = new ConversationStore(clock);
    }

    /** Without the energy analysis: an energy question is answered with a notice. */
    public AssistantService(EvidenceRetrievalService retrieval, TrendService trends, ModelGateway gateway, SuggestionService suggestions, Clock clock) {
        this(retrieval, trends, gateway, suggestions, null, clock);
    }

    /** Without the drafting capability: a request for a draft is answered with a notice and nothing is stored. */
    public AssistantService(EvidenceRetrievalService retrieval, TrendService trends, ModelGateway gateway, Clock clock) {
        this(retrieval, trends, gateway, null, clock);
    }

    public AssistantAnswer ask(String conversationId, String question) {
        String q = validate(question);
        ConversationStore.Conversation conversation = conversations.open(conversationId);
        DeterministicRouter.Context context = conversation.context();
        List<String> names = retrieval.deviceNames(500);
        DeterministicRouter.Plan plan = DeterministicRouter.route(q, names, context);
        var now = clock.instant();
        List<String> notices = new ArrayList<>();
        if (plan.mentionsChange() && plan.kind() != DeterministicRouter.Kind.WRITE)
            notices.add("Part of the question asks to change, send, approve, activate or delete something. This assistant cannot do that; it only read the stored facts below.");

        switch (plan.kind()) {
            case WRITE -> {
                notices.add("This assistant only reads stored facts. It cannot change, control, send, approve, activate or delete anything. Use the normal rule and approval workflow for that.");
                String asked = q.toLowerCase(java.util.Locale.ROOT);
                if ((asked.contains("rule") || asked.contains("automation")) && (asked.contains("create") || asked.contains("make") || asked.contains("add") || asked.contains("set up")))
                    notices.add("I can draft a rule for review instead: say draft a rule that ..., or draft a hard limit rule for <device> <capability> at <value> <unit>.");
                int turn = conversation.record(q, context, now);
                return finish(conversation, turn, AssistantAnswer.Status.REFUSED_WRITE, "DETERMINISTIC", "DETERMINISTIC", List.of(), List.of(), List.of(), List.of(), notices, null);
            }
            case SCOPE -> {
                notices.add("I can only answer questions about the stored state of devices, rules, commands, readings, anomalies and suggestions in this platform.");
                int turn = conversation.record(q, context, now);
                return finish(conversation, turn, AssistantAnswer.Status.OUT_OF_SCOPE, "DETERMINISTIC", "DETERMINISTIC", List.of(), List.of(), List.of(), List.of(), notices, null);
            }
            case DRAFT -> {
                if (suggestions == null) {
                    notices.add("Drafting rules is not available in this configuration. Nothing was stored.");
                    int turn = conversation.record(q, context, now);
                    return finish(conversation, turn, AssistantAnswer.Status.DRAFT_NOT_STORED, "DETERMINISTIC", "DETERMINISTIC", List.of("DRAFT"), List.of(), List.of(), List.of(), notices, null);
                }
                SuggestionService.Outcome drafted = suggestions.draft(plan.draft());
                notices.addAll(drafted.notices());
                int turn = conversation.record(q, context, now);
                return finish(conversation, turn, drafted.status(), "DETERMINISTIC", "DETERMINISTIC", List.of(plan.draft().hardLimit() != null ? "DRAFT_HARD_LIMIT" : "DRAFT_FROM_GOAL"), drafted.statements(), List.of(), drafted.sources(), notices, null);
            }
            case CLARIFY -> {
                String clarification = plan.clarification() + (names.isEmpty() ? "" : " Known devices include: " + String.join(", ", names.subList(0, Math.min(10, names.size()))) + ".");
                int turn = conversation.record(q, context, now);
                return finish(conversation, turn, AssistantAnswer.Status.NEEDS_CLARIFICATION, "DETERMINISTIC", "DETERMINISTIC", List.of(), List.of(), List.of(), List.of(), notices, clarification);
            }
            default -> { /* tools below */ }
        }

        // run the fixed tools
        List<LookupRequest> requests = new ArrayList<>();
        List<DeterministicRouter.Call> lookupCalls = new ArrayList<>();
        for (DeterministicRouter.Call c : plan.calls()) {
            if (c.request() == null) continue;      // a fleet-wide energy question needs no lookup
            requests.add(c.request());
            lookupCalls.add(c);
        }
        QuestionEvidence evidence = retrieval.retrieve(requests);
        notices.addAll(evidence.notices());
        List<EvidenceResult> results = evidence.results();
        TrendResult trend = null;
        String trendLabel = null;
        for (int i = 0; i < results.size() && i < lookupCalls.size(); i++) {
            DeterministicRouter.Call call = lookupCalls.get(i);
            EvidenceResult r = results.get(i);
            if (call.trend() && r.entity() != null && r.entity().id() != null && r.status() != EvidenceStatus.NOT_FOUND && call.request().capabilityKey() != null) {
                trend = trends.analyse(UUID.fromString(r.entity().id()), call.request().capabilityKey(), Optional.ofNullable(call.windowHours()), Optional.<HardLimit>empty(), Optional.empty()).trend();
                trendLabel = r.entity().id() + "/" + call.request().capabilityKey();
                break;
            }
        }
        List<AnswerComposer.EnergyView> energyViews = new ArrayList<>();
        Set<Integer> hidden = new LinkedHashSet<>();
        for (DeterministicRouter.Call call : plan.calls()) {
            if (!call.energy()) continue;
            if (energy == null) {
                notices.add("The energy analysis is not available in this configuration.");
                continue;
            }
            Optional<UUID> energyDevice = Optional.empty();
            String scopeName = null;
            boolean resolved = true;
            if (call.request() != null) {
                int index = lookupCalls.indexOf(call);
                EvidenceResult r = index >= 0 && index < results.size() ? results.get(index) : null;
                if (r != null && r.status() == EvidenceStatus.OBSERVED && r.entity() != null && r.entity().id() != null) {
                    energyDevice = Optional.of(UUID.fromString(r.entity().id()));
                    scopeName = r.entity().name();
                    hidden.add(index);
                } else {
                    resolved = false;      // the device could not be resolved: the lookup's own statements explain why
                }
            }
            if (resolved) energyViews.add(new AnswerComposer.EnergyView(energy.analyse(energyDevice, Optional.ofNullable(call.windowHours())), call.advice(), scopeName));
        }
        AnswerComposer.Composed composed = AnswerComposer.compose(visibleResults(lookupCalls, results, trend, hidden), trend, trendLabel, energyViews);
        if (composed.dropped() > 0) notices.add(composed.dropped() + " statement(s) were left out because their sources could not be resolved.");
        List<Statement> statements = new ArrayList<>(composed.statements());
        if (statements.size() > MAX_STATEMENTS) {
            statements = new ArrayList<>(statements.subList(0, MAX_STATEMENTS));
            notices.add("The answer was cut at " + MAX_STATEMENTS + " statements.");
        }
        List<EvidenceItem> sources = composed.sources().size() > MAX_SOURCES ? composed.sources().subList(0, MAX_SOURCES) : composed.sources();

        // identities for the next turn (never facts)
        UUID deviceId = context.deviceId();
        String deviceName = context.deviceName();
        UUID ruleId = context.ruleId(), commandId = context.commandId();
        String capability = context.capabilityKey();
        for (int i = 0; i < results.size() && i < lookupCalls.size(); i++) {
            EvidenceResult r = results.get(i);
            LookupRequest req = lookupCalls.get(i).request();
            if (r.entity() != null && "device".equals(r.entity().type()) && r.entity().id() != null && r.status() != EvidenceStatus.NOT_FOUND) {
                deviceId = UUID.fromString(r.entity().id());
                deviceName = r.entity().name() != null ? r.entity().name() : deviceName;
            }
            if (req.ruleId() != null) ruleId = req.ruleId();
            if (req.commandId() != null) commandId = req.commandId();
            if (req.capabilityKey() != null) capability = req.capabilityKey();
        }
        int turn = conversation.record(q, new DeterministicRouter.Context(deviceId, deviceName, ruleId, commandId, capability), now);

        boolean anyFact = statements.stream().anyMatch(s -> s.kind() == Kind.FACT);
        boolean allUnavailable = !results.isEmpty() && results.stream().allMatch(r -> r.status() == EvidenceStatus.NOT_AVAILABLE);
        AssistantAnswer.Status status = !anyFact ? (allUnavailable ? AssistantAnswer.Status.UNAVAILABLE : AssistantAnswer.Status.NO_EVIDENCE) : AssistantAnswer.Status.ANSWERED;

        // optional: a model may only choose the ORDER in which already-written sentences appear
        List<Statement> primary = statements, additional = List.of();
        String orderedBy = "DETERMINISTIC";
        if (anyFact) {
            Optional<List<String>> selected = modelSelection(q, statements, sources);
            if (selected.isPresent() && !selected.get().isEmpty()) {
                List<Statement> facts = statements.stream().filter(s -> s.kind() == Kind.FACT).toList();
                List<Statement> chosen = new ArrayList<>();
                for (String ref : selected.get()) for (Statement s : facts) if (s.refs().contains(ref) && !chosen.contains(s)) chosen.add(s);
                if (!chosen.isEmpty()) {
                    List<Statement> rest = new ArrayList<>(facts);
                    rest.removeAll(chosen);
                    List<Statement> keep = new ArrayList<>(chosen);
                    for (Statement s : statements) if (s.kind() != Kind.FACT) keep.add(s);
                    primary = keep;
                    additional = rest;
                    orderedBy = "MODEL_SELECTED";
                }
            }
        }
        List<String> planLabels = new ArrayList<>();
        for (DeterministicRouter.Call c : plan.calls()) planLabels.add(c.energy() ? "ENERGY" : c.trend() ? "TREND" : c.request().type().name());
        return finish(conversation, turn, status, "DETERMINISTIC", orderedBy, planLabels, primary, additional, sources, notices, null);
    }

    /** The lookup results; the trend call's history lookup stays in the list because its readings are sources of the trend statement. */
    private static List<EvidenceResult> visibleResults(List<DeterministicRouter.Call> calls, List<EvidenceResult> results, TrendResult trend, Set<Integer> hidden) {
        List<EvidenceResult> out = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            boolean isTrend = trend != null && i < calls.size() && calls.get(i).trend();
            if (!isTrend && !hidden.contains(i)) out.add(results.get(i));
        }
        return out;
    }

    private Optional<List<String>> modelSelection(String question, List<Statement> statements, List<EvidenceItem> sources) {
        Set<String> factRefs = new LinkedHashSet<>();
        for (Statement s : statements) if (s.kind() == Kind.FACT) factRefs.addAll(s.refs());
        if (factRefs.size() < 2) return Optional.empty();
        StringBuilder block = new StringBuilder();
        Set<String> presented = new LinkedHashSet<>();
        for (EvidenceItem e : sources) {
            if (!factRefs.contains(e.ref()) || presented.size() >= 40) continue;
            presented.add(e.ref());
            block.append(e.ref()).append(" | ").append(e.label()).append(" | ").append(e.value() == null ? "" : e.value()).append('\n');
        }
        if (presented.size() < 2) return Optional.empty();
        ModelOutcome outcome = gateway.ask(new ModelRequest(ModelPurpose.ASSISTANT_SELECTION, AssistantContracts.selectionInstruction(),
                List.of(DataBlock.userText("question", question), DataBlock.evidence("evidence", block.toString())), AssistantContracts.SELECTION_SCHEMA));
        if (!(outcome instanceof ModelOutcome.Completed done)) return Optional.empty();
        EvidenceSelection.Result result = EvidenceSelection.validate(done.values(), presented);
        if (result instanceof EvidenceSelection.Accepted accepted && !accepted.selection().abstained()) return Optional.of(accepted.selection().refs());
        return Optional.empty();
    }

    private AssistantAnswer finish(ConversationStore.Conversation c, int turn, AssistantAnswer.Status status, String routedBy, String orderedBy, List<String> plan, List<Statement> statements, List<Statement> additional,
                                   List<EvidenceItem> sources, List<String> notices, String clarification) {
        return new AssistantAnswer(c.id(), turn, status, routedBy, orderedBy, plan, statements, additional, sources, notices, clarification, clock.instant());
    }

    static String validate(String question) {
        if (question == null) throw new IllegalArgumentException("a question is required");
        String q = question.strip();
        if (q.length() < 3 || q.length() > MAX_QUESTION_CHARS) throw new IllegalArgumentException("a question is 3 to " + MAX_QUESTION_CHARS + " characters");
        for (int i = 0; i < q.length(); i++) {
            char ch = q.charAt(i);
            if (Character.isISOControl(ch) && ch != '\n' && ch != '\t') throw new IllegalArgumentException("a question may not contain control characters");
        }
        return q;
    }

    /** Test seam: the same plan the service would run, without running it. */
    public static DeterministicRouter.Plan planFor(String question, List<String> names, DeterministicRouter.Context context) {
        return DeterministicRouter.route(validate(question), names, context);
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> unused() {
        return new LinkedHashMap<>();
    }
}
