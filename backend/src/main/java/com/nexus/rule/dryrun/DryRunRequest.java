package com.nexus.rule.dryrun;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.rule.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Backend dry-run request (contract C19). Every field is typed; unknown fields are rejected. {@code mode} is a string so that an unsupported
 * mode (HISTORICAL_REPLAY, anything else) gets a stable typed rejection instead of a framework binding error.
 * <ul>
 * <li>SUPPLIED_STATE: {@code simulationTime} and {@code supplied} state are required inputs; the browser clock is never used.</li>
 * <li>CURRENT_STATE: the backend Clock and a consistent backend snapshot are used; {@code simulationTime} and {@code supplied.twins} are rejected.</li>
 * </ul>
 * Nothing in a request or response is ever a production authority: simulated identifiers are not UUIDs and cannot be replayed as command ids.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DryRunRequest(String mode, Scope scope, List<VersionSelector> versions, TriggerSpec trigger, Instant simulationTime, Supplied supplied) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Scope(UUID deviceId) {}

    /** A stored version (ruleId, optional versionId: default active then working) or an inline unsaved definition (never persisted). */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record VersionSelector(UUID ruleId, UUID versionId, InlineDefinition definition, String label) {}

    /**
     * An unsaved definition. Its OWN strict model (not the lenient create-request DTO): every field is named here and anything else - including the
     * policy keys the engine does not support - is rejected at binding instead of being silently ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InlineDefinition(String name, Boolean enabled, Integer priority, List<RuleCondition> conditions, List<RuleAction> actions, Map<String, String> metadata,
            Integer cooldownSeconds, List<TriggerSubscription> triggers, RepeatPolicy repeatPolicy, ScheduleSpec schedule, CooldownScope cooldownScope) {
        /** Null when nothing beyond the legacy adapter is configured, exactly like a create request. */
        public RuleRuntime runtime() {
            return triggers == null && repeatPolicy == null && schedule == null && cooldownScope == null ? null : new RuleRuntime(triggers, repeatPolicy, schedule, cooldownScope);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TriggerSpec(String type, UUID deviceId, Map<String, Object> attributes, EventSpec event, String correlationId) {}

    /** The legacy DomainEvent adapter input: the payload predicates read exactly these typed values. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EventSpec(String eventType, String severity, Map<String, Object> payload) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Supplied(List<TwinValue> twins, List<Connectivity> connectivity, List<Cooldown> cooldowns, List<EdgeLatch> edgeLatches,
            List<HysteresisLatch> hysteresisLatches, List<Temporal> temporal) {}

    /**
     * One reported capability value. {@code value} stays a JSON node so false, 0, an empty string and an explicit null stay distinct from a
     * missing key. {@code receivedAt} absent/null means the backend receipt time is unavailable (quality UNKNOWN), never "now".
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TwinValue(UUID deviceId, String capabilityKey, String valueType, JsonNode value, String unit, Instant observedAt, Instant receivedAt,
            Integer freshnessThresholdSeconds) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Connectivity(UUID deviceId, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Cooldown(UUID ruleId, Instant lastMatchedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EdgeLatch(UUID ruleId, boolean latched) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HysteresisLatch(UUID ruleId, String conditionId, boolean latched) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Temporal(UUID ruleId, UUID versionId, String conditionId, String operator, String phase, long sequence, long generation,
            Instant armedAt, Instant dueAt) {}
}
