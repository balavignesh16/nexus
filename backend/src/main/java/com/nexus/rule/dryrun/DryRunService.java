package com.nexus.rule.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.application.RuleEvaluationSnapshotService;
import com.nexus.rule.domain.*;
import com.nexus.rule.dryrun.DryRunResponse.Diagnostic;
import com.nexus.rule.persistence.RuleVersionStore;
import com.nexus.rule.snapshot.CapturedCapability;
import com.nexus.rule.snapshot.CapturedConnectivity;
import com.nexus.rule.snapshot.RuleEvaluationSnapshot;
import com.nexus.rule.temporal.TemporalRuntimeEvidence;
import com.nexus.rule.trigger.TriggerEligibility;
import com.nexus.rule.trigger.TriggerObservation;
import com.nexus.twin.application.TwinStateQualityEvaluator;
import com.nexus.twin.domain.DigitalTwin;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Backend dry-run (C19). Validates the request BEFORE any evaluation, resolves the selected immutable versions, obtains ONE frozen snapshot
 * (a consistent backend view for CURRENT_STATE, or typed supplied state over the backend's installed contracts for SUPPLIED_STATE) and hands
 * everything to the {@link DryRunEngine}, which applies the production decision logic, candidate construction and arbitration.
 * <p>
 * Structurally side-effect-free: the only collaborators are read-only (version/contract/state readers, the evaluation snapshot reads, the
 * engine) - there is no executor, command acceptance, publisher, match persister, plan store, runtime-state writer, lifecycle service or
 * realtime publisher in this object's dependency graph (guarded by a test) - and the whole call runs in a read-only transaction, so the
 * database itself would reject a write.
 */
@Service
public class DryRunService {
    public static final int MAX_VERSIONS = 100;
    public static final int MAX_CONDITIONS = 100;
    public static final int MAX_INPUT_BYTES = 1024 * 1024;
    private static final int MAX_SUPPLIED_ENTRIES = 5_000;

    private final DryRunEngine engine;
    private final RuleVersionStore versions;
    private final RuleEvaluationSnapshotService snapshots;
    private final RuntimeStateReader runtime;
    private final TwinStateQualityEvaluator quality;
    private final Clock clock;

    public DryRunService(DryRunEngine engine, RuleVersionStore versions, RuleEvaluationSnapshotService snapshots, RuntimeStateReader runtime,
            TwinStateQualityEvaluator quality, Clock clock) {
        this.engine = engine; this.versions = versions; this.snapshots = snapshots; this.runtime = runtime; this.quality = quality; this.clock = clock;
    }

    private static DryRunRejection reject(String code, String message) { return new DryRunRejection(code, message, HttpStatus.BAD_REQUEST); }

    /**
     * ONE transaction for the whole request: REPEATABLE READ and read-only (so PostgreSQL itself refuses any write), on exactly ONE pooled connection.
     * The version lookup, the snapshot, the runtime-state and exclusion reads all join it, so they see one committed state and a burst of requests
     * can never starve the pool by holding a connection while waiting for a second. REQUIRES_NEW so an ambient read-write transaction is never joined.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public DryRunResponse run(DryRunRequest request) {
        // ---- 1. Everything below is validated BEFORE any rule is evaluated. ----
        if (request == null) throw reject("INVALID_REQUEST", "A request body is required");
        String mode = request.mode() == null ? "" : request.mode().trim();
        if (mode.isEmpty()) throw reject("MODE_REQUIRED", "mode is required: SUPPLIED_STATE or CURRENT_STATE");
        if ("HISTORICAL_REPLAY".equals(mode))
            throw new DryRunRejection("HISTORICAL_REPLAY_UNSUPPORTED", "HISTORICAL_REPLAY is not supported: faithful replay needs historical accepted-observation, receipt, "
                + "connectivity and contract evidence that is not available. No historical state was fabricated and nothing was evaluated; historical replay remains an open roadmap item.",
                HttpStatus.UNPROCESSABLE_ENTITY);
        boolean supplied = "SUPPLIED_STATE".equals(mode);
        if (!supplied && !"CURRENT_STATE".equals(mode)) throw reject("UNKNOWN_MODE", "Unsupported mode '" + mode + "'; use SUPPLIED_STATE or CURRENT_STATE");
        if (request.versions() == null || request.versions().isEmpty()) throw reject("VERSIONS_REQUIRED", "At least one rule version is required");
        if (request.versions().size() > MAX_VERSIONS)
            throw new DryRunRejection("TOO_MANY_VERSIONS", "At most " + MAX_VERSIONS + " versions per request (got " + request.versions().size() + ")", HttpStatus.PAYLOAD_TOO_LARGE);
        if (request.trigger() == null || request.trigger().type() == null || request.trigger().type().isBlank()) throw reject("TRIGGER_REQUIRED", "A trigger with a type is required");
        if (supplied) {
            if (request.simulationTime() == null) throw reject("SIMULATION_TIME_REQUIRED", "SUPPLIED_STATE needs an explicit simulationTime");
            if (request.supplied() == null) throw reject("SUPPLIED_STATE_REQUIRED", "SUPPLIED_STATE needs a supplied block (it may hold empty lists)");
        } else {
            if (request.simulationTime() != null) throw reject("SIMULATION_TIME_NOT_ALLOWED", "CURRENT_STATE uses the backend Clock; remove simulationTime");
            var s = request.supplied();
            if (s != null && (notEmpty(s.twins()) || notEmpty(s.connectivity()) || notEmpty(s.cooldowns()) || notEmpty(s.edgeLatches())
                    || notEmpty(s.hysteresisLatches()) || notEmpty(s.temporal())))
                throw reject("CURRENT_STATE_REJECTS_SUPPLIED_INPUT", "CURRENT_STATE reads backend state only; supplied state is never accepted as authoritative current state");
        }
        TriggerType triggerType;
        try { triggerType = TriggerType.valueOf(request.trigger().type().trim()); }
        catch (IllegalArgumentException unknown) { throw reject("UNKNOWN_TRIGGER", "Unknown trigger type '" + request.trigger().type() + "'"); }
        var triggerSpec = request.trigger();
        if (triggerType.legacy()) {
            var event = triggerSpec.event();
            if (event == null || event.eventType() == null) throw reject("EVENT_REQUIRED", "The LEGACY_DOMAIN_EVENT trigger needs event.eventType");
            try { EventType.valueOf(event.eventType().trim()); }
            catch (IllegalArgumentException unknown) { throw reject("UNKNOWN_EVENT_TYPE", "Unknown event type '" + event.eventType() + "'"); }
        } else if (triggerSpec.event() != null) throw reject("EVENT_NOT_ALLOWED", "Only the LEGACY_DOMAIN_EVENT trigger carries an event");

        var resolved = new ArrayList<Resolved>();
        int index = 0;
        for (var selector : request.versions()) {
            if (selector == null) throw reject("INVALID_VERSION", "versions[" + index + "] is empty");
            boolean stored = selector.ruleId() != null, inline = selector.definition() != null;
            if (stored == inline) throw reject("INVALID_VERSION", "versions[" + index + "] needs exactly one of ruleId (stored version) or definition (inline, never saved)");
            Rule pinned;
            String source;
            DryRunResponse.Ownership ownership = null;
            if (stored) {
                var ref = versions.resolve(selector.ruleId(), selector.versionId())
                    .orElseThrow(() -> new DryRunRejection("VERSION_NOT_FOUND", "No such rule version", HttpStatus.NOT_FOUND));
                var d = ref.definition();
                pinned = new Rule(ref.ruleId(), d.name(), true, d.priority(), d.conditions(), d.actions(), d.metadata(), d.cooldownSeconds(),
                    new RuleExecutionIdentity(ref.versionId(), ref.revision(), ref.executionEpoch()), d.runtime());
                source = ref.active() ? "STORED_ACTIVE" : "STORED_VERSION";
                ownership = new DryRunResponse.Ownership(ref.lifecycle(), ref.active(), ref.reconciliation(),
                    "ACTIVE".equals(ref.lifecycle()) && ref.active() && ("PASSED".equals(ref.reconciliation()) || "NOT_REQUIRED".equals(ref.reconciliation())));
            } else {
                if (selector.versionId() != null) throw reject("INVALID_VERSION", "versions[" + index + "]: an inline definition has no versionId");
                var d = selector.definition();
                pinned = new Rule(UUID.randomUUID(), d.name() == null ? "inline" : d.name(), true, d.priority() == null ? 10 : d.priority(), d.conditions(), d.actions(),
                    d.metadata(), d.cooldownSeconds(), new RuleExecutionIdentity(UUID.randomUUID(), 0, 0), d.runtime());
                source = "INLINE";
            }
            int conditions = pinned.conditions() == null ? 0 : pinned.conditions().size();
            if (conditions > MAX_CONDITIONS)
                throw new DryRunRejection("TOO_MANY_CONDITIONS", "versions[" + index + "] has " + conditions + " conditions; at most " + MAX_CONDITIONS + " per version",
                    HttpStatus.PAYLOAD_TOO_LARGE);
            resolved.add(new Resolved("sim-v" + (index + 1), source, pinned, ownership));
            index++;
        }
        UUID scopeDevice = singleScope(request, triggerSpec, resolved);

        Instant time = supplied ? request.simulationTime() : clock.instant();
        String timeSource = supplied ? "SUPPLIED" : "BACKEND_CLOCK";
        var diagnostics = new ArrayList<Diagnostic>();
        if (resolved.stream().anyMatch(v -> v.rule().conditions() != null && v.rule().conditions().stream().anyMatch(c -> c != null && c.temporal() != null)))
            diagnostics.add(new Diagnostic("TEMPORAL_PROGRESSION_NOT_SIMULATED", "INFO",
                "A temporal condition is decided from the temporal runtime evidence supplied (SUPPLIED_STATE) or copied from the backend (CURRENT_STATE) and addressed by a TEMPORAL_DUE trigger. "
                + "Arming, counting and advancing temporal state from telemetry is not simulated.", "versions"));
        UUID triggerDevice = scopeDevice != null ? scopeDevice : triggerSpec.deviceId();

        // ---- 2. The trigger observation addressed to every version, and which versions it reaches (the production eligibility test). ----
        var entries = new ArrayList<DryRunEngine.Entry>();
        var eligibleRules = new ArrayList<Rule>();
        TriggerObservation firstEligibleTrigger = null;
        UUID sourceId = UUID.randomUUID();
        DomainEvent legacyEvent = null;
        if (triggerType.legacy()) {
            var e = triggerSpec.event();
            legacyEvent = new DomainEvent(sourceId, EventType.valueOf(e.eventType().trim()), triggerDevice, time, "dry-run",
                e.payload() == null ? Map.of() : new LinkedHashMap<>(e.payload()), e.severity() == null ? "INFO" : e.severity(), triggerSpec.correlationId());
        }
        for (Resolved version : resolved) {
            var attributes = new LinkedHashMap<String, Object>(triggerSpec.attributes() == null ? Map.of() : triggerSpec.attributes());
            if (TriggerEligibility.targeted(triggerType)) {      // addressed to exactly this version, as the production router does
                attributes.put("ruleId", version.rule().ruleId().toString());
                attributes.put("ruleVersionId", version.rule().executionIdentity().ruleVersionId().toString());
            }
            var observation = new TriggerObservation(triggerType, sourceId, triggerType.legacy() ? null : UUID.randomUUID(), legacyEvent, triggerDevice, time, time,
                Map.of(), attributes, triggerSpec.correlationId(), null, 0, List.of());
            boolean eligible = TriggerEligibility.eligible(version.rule(), observation);
            entries.add(new DryRunEngine.Entry(version.simulatedId(), version.source(), version.rule(), observation, eligible, version.ownership()));
            if (eligible) { eligibleRules.add(version.rule()); if (firstEligibleTrigger == null) firstEligibleTrigger = observation; }
        }

        // ---- 3. ONE frozen snapshot and isolated runtime inputs. ----
        RuleEvaluationSnapshot snapshot = null;
        DryRunEngine.RuntimeInputs inputs;
        if (supplied) {
            var s = request.supplied();
            checkSuppliedSize(s);
            final UUID scope = scopeDevice;
            if (!eligibleRules.isEmpty())
                snapshot = snapshots.captureSupplied(eligibleRules, firstEligibleTrigger, time, caps -> suppliedState(s, scope, caps, resolved, time, diagnostics));
            inputs = new SuppliedRuntime(s);
        } else {
            if (!eligibleRules.isEmpty()) snapshot = snapshots.captureForDryRun(eligibleRules, firstEligibleTrigger, time);
            inputs = new BackendRuntime(runtime);
        }
        return engine.evaluate(mode, time, timeSource, scopeDevice, entries, snapshot, inputs, diagnostics);
    }

    private record Resolved(String simulatedId, String source, Rule rule, DryRunResponse.Ownership ownership) {}

    private static boolean notEmpty(List<?> list) { return list != null && !list.isEmpty(); }

    private static void checkSuppliedSize(DryRunRequest.Supplied s) {
        int total = size(s.twins()) + size(s.connectivity()) + size(s.cooldowns()) + size(s.edgeLatches()) + size(s.hysteresisLatches()) + size(s.temporal());
        if (total > MAX_SUPPLIED_ENTRIES) throw new DryRunRejection("SUPPLIED_STATE_TOO_LARGE", "At most " + MAX_SUPPLIED_ENTRIES + " supplied entries", HttpStatus.PAYLOAD_TOO_LARGE);
    }
    private static int size(List<?> list) { return list == null ? 0 : list.size(); }

    /** At most ONE evaluation scope: the request scope, every explicit DEVICE_ID condition and the trigger device must agree. */
    private UUID singleScope(DryRunRequest request, DryRunRequest.TriggerSpec trigger, List<Resolved> resolved) {
        var scopes = new LinkedHashSet<UUID>();
        if (request.scope() != null && request.scope().deviceId() != null) scopes.add(request.scope().deviceId());
        if (trigger.deviceId() != null) scopes.add(trigger.deviceId());
        for (Resolved version : resolved)
            if (version.rule().conditions() != null) for (RuleCondition c : version.rule().conditions())
                if (c != null && c.field() == RuleField.DEVICE_ID && c.expectedValue() != null) {
                    try { scopes.add(UUID.fromString(c.expectedValue())); } catch (IllegalArgumentException invalid) { /* reported by the production contract diagnostics */ }
                }
        if (scopes.size() > 1) throw new DryRunRejection("MULTIPLE_SCOPES", "A dry-run evaluates exactly one scope, but the request, trigger and selected versions name "
            + scopes.size() + " devices", HttpStatus.BAD_REQUEST);
        return scopes.isEmpty() ? null : scopes.iterator().next();
    }

    // ------------------------------------------------------------------ SUPPLIED_STATE

    private RuleEvaluationSnapshotService.Supplied suppliedState(DryRunRequest.Supplied s, UUID scope, Map<String, CapturedCapability> caps,
            List<Resolved> resolved, Instant time, List<Diagnostic> diagnostics) {
        var twinsByDevice = new LinkedHashMap<UUID, List<DigitalTwin>>();
        var links = new LinkedHashMap<UUID, CapturedConnectivity>();
        var linkStatus = new HashMap<UUID, String>();
        if (s.connectivity() != null) for (var link : s.connectivity()) {
            if (link == null || link.deviceId() == null || link.status() == null) throw reject("INVALID_CONNECTIVITY", "connectivity entries need deviceId and status");
            if (scope != null && !scope.equals(link.deviceId())) throw new DryRunRejection("MULTIPLE_SCOPES", "Supplied connectivity names a second device", HttpStatus.BAD_REQUEST);
            String status = link.status().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("ONLINE", "OFFLINE", "UNKNOWN").contains(status)) throw reject("INVALID_CONNECTIVITY", "connectivity status must be ONLINE, OFFLINE or UNKNOWN");
            linkStatus.put(link.deviceId(), status);
            if (!"UNKNOWN".equals(status)) links.put(link.deviceId(), new CapturedConnectivity(status, time, 0));   // absence of a row IS unknown
        }
        var qualityInputs = new LinkedHashMap<String, TwinStateQualityEvaluator.Inputs>();
        int n = 0;
        if (s.twins() != null) for (var tv : s.twins()) {
            String path = "supplied.twins[" + n++ + "]";
            if (tv == null || tv.capabilityKey() == null || tv.capabilityKey().isBlank()) throw reject("INVALID_SUPPLIED_TWIN", path + ": capabilityKey is required");
            UUID device = tv.deviceId() != null ? tv.deviceId() : scope;
            if (device == null) throw reject("INVALID_SUPPLIED_TWIN", path + ": deviceId is required when no scope device is named");
            if (scope != null && !scope.equals(device)) throw new DryRunRejection("MULTIPLE_SCOPES", path + " names a second device", HttpStatus.BAD_REQUEST);
            if (tv.value() == null) throw reject("VALUE_REQUIRED", path + ": value is required (use an explicit null for \"reported null\"; a missing key is not a value)");
            if (tv.observedAt() == null) throw reject("INVALID_SUPPLIED_TWIN", path + ": observedAt is required");
            if (tv.valueType() == null) throw reject("INVALID_SUPPLIED_TWIN", path + ": valueType is required");
            CapabilityValueType type;
            try { type = CapabilityValueType.valueOf(tv.valueType().trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException unknown) { throw reject("INVALID_SUPPLIED_TWIN", path + ": unknown valueType '" + tv.valueType() + "'"); }
            if (type == CapabilityValueType.OBJECT) throw reject("UNSUPPORTED_VALUE_TYPE", path + ": OBJECT values are not supported by rule conditions");
            if (tv.freshnessThresholdSeconds() != null && tv.freshnessThresholdSeconds() < 0) throw reject("INVALID_SUPPLIED_TWIN", path + ": freshnessThresholdSeconds must not be negative");
            var capability = caps.get(RuleEvaluationSnapshot.capabilityKey(device, tv.capabilityKey(), "READ"));
            if (capability == null) {
                diagnostics.add(new Diagnostic("SUPPLIED_TWIN_UNUSED", "WARNING", "No selected condition reads an installed READ capability '" + tv.capabilityKey()
                    + "' on this device, so the supplied value was not used", path));
                continue;
            }
            JsonNode node = tv.value();
            if (node.isNull()) {     // reported null: present as an input, but a null is no value (conditions read MISSING)
                diagnostics.add(new Diagnostic("SUPPLIED_EXPLICIT_NULL", "INFO", "An explicit null is not a value; conditions on '" + tv.capabilityKey() + "' read MISSING", path));
                continue;
            }
            Object typed = switch (type) {
                case NUMBER -> { if (!node.isNumber()) throw reject("INVALID_SUPPLIED_VALUE", path + ".value must be a JSON number for NUMBER"); yield node.decimalValue(); }
                case BOOLEAN -> { if (!node.isBoolean()) throw reject("INVALID_SUPPLIED_VALUE", path + ".value must be a JSON boolean for BOOLEAN"); yield node.booleanValue(); }
                default -> { if (!node.isTextual()) throw reject("INVALID_SUPPLIED_VALUE", path + ".value must be a JSON string for " + type); yield node.textValue(); }
            };
            var twin = new DigitalTwin(device, tv.observedAt(), tv.capabilityKey(), tv.capabilityKey(), typed, tv.unit(), tv.observedAt(), tv.receivedAt(), Map.of(),
                capability.definitionId(), capability.bindingId(), capability.definitionKey(), type, null);
            twinsByDevice.computeIfAbsent(device, d -> new ArrayList<>()).add(twin);
            qualityInputs.put(RuleEvaluationSnapshot.twinKey(twin),
                quality.suppliedInputs(twin, linkStatus.getOrDefault(device, "UNKNOWN"), tv.freshnessThresholdSeconds()));
        }
        var latches = new LinkedHashMap<String, Boolean>();
        if (s.hysteresisLatches() != null) for (var latch : s.hysteresisLatches()) {
            if (latch == null || latch.ruleId() == null || latch.conditionId() == null) throw reject("INVALID_HYSTERESIS_LATCH", "hysteresis latches need ruleId and conditionId");
            if (scope == null) throw reject("SCOPE_REQUIRED", "A hysteresis latch needs a scope device");
            latches.put(RuleEvaluationSnapshot.hysteresisKey(latch.ruleId(), latch.conditionId(), scope), latch.latched());
        }
        var temporal = new LinkedHashMap<String, TemporalRuntimeEvidence>();
        if (s.temporal() != null) for (var t : s.temporal()) {
            if (t == null || t.ruleId() == null || t.conditionId() == null || t.phase() == null || t.operator() == null) throw reject("INVALID_TEMPORAL_STATE", "temporal entries need ruleId, conditionId, operator and phase");
            if (scope == null) throw reject("SCOPE_REQUIRED", "Temporal state needs a scope device");
            var match = resolved.stream().filter(r -> r.rule().ruleId().equals(t.ruleId()) && (t.versionId() == null || r.rule().executionIdentity().ruleVersionId().equals(t.versionId()))).findFirst()
                .orElseThrow(() -> reject("INVALID_TEMPORAL_STATE", "temporal state names a rule version that is not selected"));
            var identity = match.rule().executionIdentity();
            temporal.put(RuleEvaluationSnapshot.temporalKey(identity.ruleVersionId(), t.conditionId(), scope),
                new TemporalRuntimeEvidence(t.ruleId(), identity.ruleVersionId(), identity.ruleRevision(), t.conditionId(), scope, t.generation(), t.operator(), t.phase(),
                    t.armedAt(), t.dueAt(), null, List.of(), Map.of(), t.sequence(), null, time));
        }
        return new RuleEvaluationSnapshotService.Supplied(twinsByDevice, links, qualityInputs, temporal, latches);
    }

    /** Seeds from the request only; a rule without a seed has never matched and holds no latch. */
    private static final class SuppliedRuntime implements DryRunEngine.RuntimeInputs {
        private final Map<UUID, Instant> cooldowns = new HashMap<>();
        private final Map<UUID, Boolean> edges = new HashMap<>();
        SuppliedRuntime(DryRunRequest.Supplied s) {
            if (s.cooldowns() != null) for (var c : s.cooldowns()) {
                if (c == null || c.ruleId() == null) throw reject("INVALID_COOLDOWN_SEED", "cooldown seeds need ruleId");
                if (c.lastMatchedAt() != null) cooldowns.put(c.ruleId(), c.lastMatchedAt());
            }
            if (s.edgeLatches() != null) for (var e : s.edgeLatches()) {
                if (e == null || e.ruleId() == null) throw reject("INVALID_EDGE_SEED", "edge latch seeds need ruleId");
                edges.put(e.ruleId(), e.latched());
            }
        }
        public Optional<Instant> lastMatched(Rule rule, String scopeKey) { return Optional.ofNullable(cooldowns.get(rule.ruleId())); }
        public boolean edgeLatched(Rule rule, UUID scopeDevice) { return edges.getOrDefault(rule.ruleId(), false); }
    }

    /** Request-local READ of the production gates. Inline definitions have no production state. */
    private static final class BackendRuntime implements DryRunEngine.RuntimeInputs {
        private final RuntimeStateReader reader;
        BackendRuntime(RuntimeStateReader reader) { this.reader = reader; }
        public Optional<Instant> lastMatched(Rule rule, String scopeKey) { return reader.cooldownLast(rule.ruleId(), scopeKey); }
        public boolean edgeLatched(Rule rule, UUID scopeDevice) { return reader.edgeLatched(rule.ruleId(), scopeDevice, rule.executionIdentity().executionEpoch()); }
    }
}
