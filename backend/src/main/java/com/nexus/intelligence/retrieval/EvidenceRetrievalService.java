package com.nexus.intelligence.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.intelligence.anomaly.AnomalyReport;
import com.nexus.intelligence.anomaly.AnomalyService;
import com.nexus.intelligence.anomaly.Finding;
import com.nexus.intelligence.evidence.EvidenceItem;
import com.nexus.intelligence.explain.Explanation;
import com.nexus.intelligence.explain.RuleNonMatchExplainer;
import com.nexus.intelligence.recommend.Recommendation;
import com.nexus.intelligence.recommend.RecommendationService;
import com.nexus.intelligence.retrieval.EvidenceResult.Entity;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.persistence.RuleVersionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * P6.7: the read-only evidence retrieval layer of the NEXUS Assistant. It answers twelve FIXED, typed lookups from the authoritative tables and existing read
 * services. There is no generic query, no SQL built from model text, no write, no command, no approval and no activation anywhere in this class. Every lookup runs
 * in its own read-only transaction with a statement timeout, selects an explicit allowlist of columns (no credentials, serial numbers, payload text or object
 * values), is bounded in rows, lookback window, evidence size, concurrency and total time, and reports in plain words what it cannot establish.
 *
 * <p>Time authority: telemetry is stamped with the DEVICE event time, twin receipt and command history with the backend; freshness is always judged on the backend
 * clock, and a device timestamp later than the allowed skew is FUTURE_DATED, never "fresh". Free text stored by devices or callers (rule match, command and ACK messages)
 * is never selected: no AI SQL reads message or payload text, so it cannot reach a prompt or an explanation. The service exists only when {@code nexus.assistant.enabled=true}; NEXUS has no user authentication, so enabling it
 * exposes these facts to everyone who can reach the endpoint (a local or private environment only).
 */
@Service
@ConditionalOnProperty(prefix = "nexus.assistant", name = "enabled", havingValue = "true")
public class EvidenceRetrievalService {
    private static final Pattern CAPABILITY = Pattern.compile("[a-z][a-z0-9_]{0,99}");
    private static final Pattern CURSOR = Pattern.compile("[0-9T:.Z-]{20,40}[|][0-9a-fA-F-]{36}");
    private static final int DEFAULT_ROWS = 50;
    private static final int DEFAULT_LOOKBACK_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;
    private final AnomalyService anomalies;
    private final RecommendationService recommendations;
    private final RuleVersionStore rules;
    private final RuleNonMatchExplainer nonMatches;
    private final RetrievalSettings settings;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Semaphore concurrency;

    public EvidenceRetrievalService(JdbcTemplate jdbc, PlatformTransactionManager transactions, AnomalyService anomalies, RecommendationService recommendations,
                                    RuleVersionStore rules, RuleNonMatchExplainer nonMatches, RetrievalSettings settings, Clock clock, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setTimeout(Math.max(1, (int) Math.ceil(settings.totalTimeMillis() / 1000.0)));
        this.anomalies = anomalies;
        this.recommendations = recommendations;
        this.rules = rules;
        this.nonMatches = nonMatches;
        this.settings = settings;
        this.clock = clock;
        this.mapper = mapper;
        this.concurrency = new Semaphore(settings.maxConcurrent());
    }

    public RetrievalSettings settings() {
        return settings;
    }

    /** All the lookups of ONE question, bounded in number and in total time. Lookups beyond the limits are not run and the result says so. */
    public QuestionEvidence retrieve(List<LookupRequest> requests) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.totalTimeMillis());
        List<EvidenceResult> results = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        boolean limited = false;
        int run = 0;
        for (LookupRequest request : requests == null ? List.<LookupRequest>of() : requests) {
            if (run >= settings.maxLookupsPerQuestion()) {
                limited = true;
                notices.add("At most " + settings.maxLookupsPerQuestion() + " lookups are run per question; the remaining lookups were not run.");
                break;
            }
            if (System.nanoTime() >= deadline) {
                limited = true;
                notices.add("The total time for one question (" + settings.totalTimeMillis() + " ms) was used up; the remaining lookups were not run.");
                break;
            }
            results.add(lookup(request));
            run++;
        }
        return new QuestionEvidence(results, notices, limited);
    }

    /**
     * The distinct device names the platform knows, at most {@code limit} (and at most 500), for recognising a device named in a question. A fixed, read-only,
     * bounded statement like the lookups; it returns names only, clipped, and nothing else about a device. An unreadable database gives an empty list.
     */
    public List<String> deviceNames(int limit) {
        int bound = Math.max(1, Math.min(limit, 500));
        try {
            List<String>[] holder = new List[1];
            readOnly.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class, Integer.toString(settings.statementTimeoutMillis()));
                holder[0] = jdbc.query("SELECT DISTINCT name FROM devices WHERE name IS NOT NULL AND length(name) >= 3 ORDER BY name LIMIT ?", (rs, n) -> clip(rs.getString(1)), bound);
            });
            return holder[0] == null ? List.of() : List.copyOf(holder[0]);
        } catch (DataAccessException | IllegalStateException | org.springframework.transaction.TransactionException unreadable) {
            return List.of();
        }
    }

    /** One lookup. Never throws for a missing entity, a limit or a read failure: those are statuses. */
    public EvidenceResult lookup(LookupRequest request) {
        Instant now = clock.instant();
        if (request == null || request.type() == null) {
            return EvidenceResult.unavailable(LookupType.DEVICE_STATE, EvidenceStatus.NOT_AVAILABLE, null, now, "UNKNOWN_LOOKUP: only the twelve fixed lookup types exist", general());
        }
        LookupType type = request.type();
        boolean acquired = false;
        try {
            acquired = concurrency.tryAcquire(settings.totalTimeMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) return EvidenceResult.unavailable(type, EvidenceStatus.NOT_AVAILABLE, null, now, "BUSY: too many lookups are running at once", general());
            EvidenceResult[] holder = new EvidenceResult[1];
            readOnly.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class, Integer.toString(settings.statementTimeoutMillis()));
                holder[0] = dispatch(request, now);
            });
            return fit(holder[0]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return EvidenceResult.unavailable(type, EvidenceStatus.NOT_AVAILABLE, null, now, "INTERRUPTED", general());
        } catch (DataAccessException | IllegalStateException | org.springframework.transaction.TransactionException unreadable) {
            // never echo the database message: it can carry identifiers and statements
            return EvidenceResult.unavailable(type, EvidenceStatus.NOT_AVAILABLE, null, now, "READ_FAILED: the evidence could not be read within the limits (" + unreadable.getClass().getSimpleName() + ")", general());
        } finally {
            if (acquired) concurrency.release();
        }
    }

    private EvidenceResult dispatch(LookupRequest r, Instant now) {
        return switch (r.type()) {
            case DEVICE_STATE -> deviceState(r, now);
            case TWIN_STATE -> twinState(r, now);
            case LATEST_TELEMETRY -> latestTelemetry(r, now);
            case TELEMETRY_HISTORY -> telemetryHistory(r, now);
            case CONNECTIVITY -> connectivity(r, now);
            case ACTIVE_RULES -> activeRules(r, now);
            case RULE_MATCHES -> ruleMatches(r, now);
            case COMMAND_HISTORY -> commandHistory(r, now);
            case DELIVERY_EVIDENCE -> deliveryEvidence(r, now);
            case VERIFICATION -> verification(r, now);
            case ANOMALIES -> anomalyLookup(r, now);
            case RECOMMENDATIONS -> recommendationLookup(r, now);
        };
    }

    // ------------------------------------------------------------------ identity

    private record Resolved(UUID id, String name, EvidenceResult early) {}

    private Resolved resolveDevice(LookupRequest r, Instant now) {
        if (r.deviceId() != null) {
            List<String> names = jdbc.query("SELECT name FROM devices WHERE id = ?", (rs, n) -> rs.getString(1), r.deviceId());
            if (names.isEmpty())
                return new Resolved(null, null, EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_FOUND, new Entity("device", r.deviceId().toString(), null), now, "NOT_FOUND: no device has this id", general()));
            return new Resolved(r.deviceId(), names.get(0), null);
        }
        if (r.deviceName() != null && !r.deviceName().isBlank()) {
            if (r.deviceName().length() > 200)
                return new Resolved(null, null, EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, null, now, "INVALID_IDENTIFIER: the device name is too long", general()));
            List<Map<String, Object>> found = jdbc.query("SELECT id, name FROM devices WHERE name = ? ORDER BY id LIMIT 6", (rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("deviceId", rs.getObject("id", UUID.class).toString());
                m.put("name", clip(rs.getString("name")));
                return m;
            }, r.deviceName());
            if (found.isEmpty())
                return new Resolved(null, null, EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_FOUND, new Entity("device", null, clip(r.deviceName())), now, "NOT_FOUND: no device has exactly this name", general()));
            if (found.size() > 1) {
                boolean more = found.size() > 5;
                List<Map<String, Object>> candidates = more ? found.subList(0, 5) : found;
                return new Resolved(null, null, new EvidenceResult(r.type(), EvidenceStatus.INSUFFICIENT_EVIDENCE, new Entity("device", null, clip(r.deviceName())), now, Freshness.UNKNOWN, null,
                        List.of(), candidates, false, more, null, List.of("Use a device id to choose one of the candidates."), general(),
                        "AMBIGUOUS_IDENTIFIER: more than one device has this name; no guess was made"));
            }
            return new Resolved(UUID.fromString((String) found.get(0).get("deviceId")), (String) found.get(0).get("name"), null);
        }
        return new Resolved(null, null, EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, null, now, "MISSING_IDENTIFIER: this lookup needs a device id or an exact device name", general()));
    }

    private Entity deviceEntity(Resolved d) {
        return new Entity("device", d.id().toString(), d.name());
    }

    // ------------------------------------------------------------------ the lookups

    private EvidenceResult deviceState(LookupRequest r, Instant now) {
        Resolved d = resolveDevice(r, now);
        if (d.early() != null) return d.early();
        Map<String, Object> row = new LinkedHashMap<>();
        List<EvidenceItem> evidence = new ArrayList<>();
        jdbc.query("SELECT d.name, d.device_type, d.status, d.mode, c.status AS connectivity_status, c.last_changed_at FROM devices d LEFT JOIN device_connectivity c ON c.device_id = d.id WHERE d.id = ?", rs -> {
            row.put("deviceId", d.id().toString());
            row.put("name", clip(rs.getString("name")));
            row.put("deviceType", rs.getString("device_type"));
            row.put("registrationStatus", rs.getString("status"));
            row.put("mode", rs.getString("mode"));
            row.put("connectivityStatus", rs.getString("connectivity_status"));
            Timestamp changed = rs.getTimestamp("last_changed_at");
            row.put("connectivityChangedAt", changed == null ? null : changed.toInstant().toString());
        }, d.id());
        String ref = "device:" + d.id();
        evidence.add(EvidenceItem.presentOrAbsent(ref + "/registrationStatus", "device registration status", row.get("registrationStatus")));
        evidence.add(row.get("connectivityStatus") == null ? EvidenceItem.notRecorded(ref + "/connectivityStatus", "connectivity status")
                : EvidenceItem.present(ref + "/connectivityStatus", "connectivity status", row.get("connectivityStatus")));
        List<Map<String, Object>> capabilities = jdbc.query("SELECT capability_type, direction, unit FROM device_capabilities WHERE device_id = ? ORDER BY capability_type, direction LIMIT ?", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("capabilityType", rs.getString("capability_type"));
            m.put("direction", rs.getString("direction"));
            m.put("unit", rs.getString("unit"));
            return m;
        }, d.id(), settings.maxRows());
        row.put("capabilities", capabilities);
        Instant newest = parseInstant((String) row.get("connectivityChangedAt"));
        return result(r.type(), EvidenceStatus.OBSERVED, deviceEntity(d), now, newest, evidence, List.of(row), true, false, null, List.of(),
                lim("Registration status describes the platform record, not the physical device.", "A connectivity status is the last observation recorded, not a live check."), null);
    }

    private EvidenceResult twinState(LookupRequest r, Instant now) {
        Resolved d = resolveDevice(r, now);
        if (d.early() != null) return d.early();
        int limit = rows(r);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<EvidenceItem> evidence = new ArrayList<>();
        Instant[] newest = new Instant[1];
        jdbc.query("SELECT state_key, capability_key, legacy_capability_type, numeric_value, boolean_value, string_value, latest_unit, latest_timestamp, received_at FROM digital_twins WHERE device_id = ? ORDER BY state_key NULLS LAST, capability_key LIMIT ?", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String key = firstNonNull(rs.getString("state_key"), rs.getString("capability_key"), rs.getString("legacy_capability_type"));
            Object value = scalar(rs);
            Instant event = instant(rs, "latest_timestamp"), received = instant(rs, "received_at");
            m.put("section", "REPORTED");
            m.put("stateKey", key);
            m.put("value", value);
            m.put("unit", rs.getString("latest_unit"));
            m.put("deviceEventTime", event == null ? null : event.toString());
            m.put("backendReceivedAt", received == null ? null : received.toString());
            rows.add(m);
            evidence.add(EvidenceItem.presentOrAbsent("twin:" + d.id() + "/reported/" + key, "reported value of " + key, value));
            newest[0] = later(newest[0], received != null ? received : event);
        }, d.id(), limit);
        jdbc.query("SELECT target_key, capability_key, numeric_value, boolean_value, string_value, unit, source, command_id, requested_at, accepted_for_dispatch_at FROM current_desired_twin_state WHERE device_id = ? ORDER BY target_key LIMIT ?", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            Object value = scalar(rs);
            m.put("section", "DESIRED");
            m.put("stateKey", rs.getString("target_key"));
            m.put("value", value);
            m.put("unit", rs.getString("unit"));
            m.put("source", rs.getString("source"));
            m.put("commandId", String.valueOf(rs.getObject("command_id")));
            m.put("requestedAt", String.valueOf(instant(rs, "requested_at")));
            m.put("acceptedForDispatchAt", String.valueOf(instant(rs, "accepted_for_dispatch_at")));
            rows.add(m);
            evidence.add(EvidenceItem.presentOrAbsent("twin:" + d.id() + "/desired/" + rs.getString("target_key"), "desired value of " + rs.getString("target_key"), value));
        }, d.id(), limit);
        if (rows.isEmpty()) return noData(r.type(), deviceEntity(d), now, "The platform holds no reported or desired twin state for this device.");
        return result(r.type(), EvidenceStatus.OBSERVED, deviceEntity(d), now, newest[0], evidence, rows, true, false, null, List.of(),
                lim("A reported value is what the device last said; a desired value is what a command asked for.", "A difference between desired and reported is a difference, not proof that a command failed or that the device did or did not act."), null);
    }

    private EvidenceResult latestTelemetry(LookupRequest r, Instant now) {
        Resolved d = resolveDevice(r, now);
        if (d.early() != null) return d.early();
        List<String> notices = new ArrayList<>();
        int hours = lookback(r, notices);
        String capability = capability(r);
        if (r.capabilityKey() != null && capability == null)
            return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, deviceEntity(d), now, "INVALID_IDENTIFIER: the capability key is not a capability key", general());
        int limit = rows(r);
        Instant from = now.minus(Duration.ofHours(hours));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<EvidenceItem> evidence = new ArrayList<>();
        Instant[] newest = new Instant[1];
        jdbc.query("SELECT DISTINCT ON (capability_key) id, capability_key, timestamp, numeric_value, boolean_value, string_value, unit FROM telemetry_history"
                + " WHERE device_id = ? AND capability_key IS NOT NULL AND timestamp >= ? AND (?::text IS NULL OR capability_key = ?) ORDER BY capability_key, timestamp DESC, id DESC LIMIT ?", rs -> {
            Map<String, Object> m = telemetryRow(rs, now);
            rows.add(m);
            evidence.add(EvidenceItem.presentOrAbsent("telemetry:" + m.get("readingId"), "reading of " + m.get("capabilityKey"), m.get("value")));
            newest[0] = later(newest[0], parseInstant((String) m.get("deviceEventTime")));
        }, d.id(), Timestamp.from(from), capability, capability, limit);
        if (rows.isEmpty())
            return withNotices(noData(r.type(), deviceEntity(d), now, "No reading was stored in the last " + hours + " hours" + (capability == null ? "" : " for " + capability) + "."), notices);
        return result(r.type(), EvidenceStatus.OBSERVED, deviceEntity(d), now, newest[0], evidence, rows, true, false, null, notices,
                lim("The time on each reading is the DEVICE event time; the backend receipt time of a reading is not recorded here.", "No newer reading is not proof that the device is off or the quantity unchanged."), null);
    }

    private EvidenceResult telemetryHistory(LookupRequest r, Instant now) {
        Resolved d = resolveDevice(r, now);
        if (d.early() != null) return d.early();
        String capability = capability(r);
        if (capability == null)
            return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, deviceEntity(d), now, "MISSING_IDENTIFIER: a valid capability key is required for telemetry history", general());
        List<String> notices = new ArrayList<>();
        int hours = lookback(r, notices);
        int limit = rows(r);
        Instant from = now.minus(Duration.ofHours(hours));
        Instant cursorTime = null;
        UUID cursorId = null;
        if (r.cursor() != null) {
            if (!CURSOR.matcher(r.cursor()).matches())
                return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, deviceEntity(d), now, "INVALID_CURSOR: the continuation cursor is not valid", general());
            String[] parts = r.cursor().split("[|]");
            try {
                cursorTime = Instant.parse(parts[0]);
                cursorId = UUID.fromString(parts[1]);
            } catch (RuntimeException invalid) {
                return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, deviceEntity(d), now, "INVALID_CURSOR: the continuation cursor is not valid", general());
            }
        }
        Timestamp ct = cursorTime == null ? null : Timestamp.from(cursorTime);
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.query("SELECT id, capability_key, timestamp, numeric_value, boolean_value, string_value, unit FROM telemetry_history"
                        + " WHERE device_id = ? AND capability_key = ? AND timestamp >= ? AND (?::timestamptz IS NULL OR timestamp < ? OR (timestamp = ? AND id < ?::uuid))"
                        + " ORDER BY timestamp DESC, id DESC LIMIT ?", (rs, n) -> telemetryRow(rs, now),
                d.id(), capability, Timestamp.from(from), ct, ct, ct, cursorId == null ? null : cursorId.toString(), limit + 1));
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        if (rows.isEmpty()) return withNotices(noData(r.type(), deviceEntity(d), now, "No reading of " + capability + " was stored in the window."), notices);
        List<EvidenceItem> evidence = new ArrayList<>();
        Instant newest = null;
        for (Map<String, Object> m : rows) {
            evidence.add(EvidenceItem.presentOrAbsent("telemetry:" + m.get("readingId"), "reading of " + capability, m.get("value")));
            newest = later(newest, parseInstant((String) m.get("deviceEventTime")));
        }
        String next = null;
        if (more) {
            Map<String, Object> last = rows.get(rows.size() - 1);
            next = last.get("deviceEventTime") + "|" + last.get("readingId");
            notices.add("More readings exist in the window; pass nextCursor to continue.");
        }
        return result(r.type(), EvidenceStatus.OBSERVED, deviceEntity(d), now, newest, evidence, rows, !more, more, next, notices,
                lim("Readings are ordered newest first by device event time.", "Gaps between readings are not interpolated and are not proof of a fault."), null);
    }

    private EvidenceResult connectivity(LookupRequest r, Instant now) {
        Resolved d = resolveDevice(r, now);
        if (d.early() != null) return d.early();
        List<Map<String, Object>> rows = jdbc.query("SELECT status, last_changed_at, generation FROM device_connectivity WHERE device_id = ?", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", rs.getString("status"));
            m.put("lastChangedAt", String.valueOf(instant(rs, "last_changed_at")));
            m.put("generation", rs.getLong("generation"));
            return m;
        }, d.id());
        if (rows.isEmpty()) return noData(r.type(), deviceEntity(d), now, "No connectivity observation was recorded for this device. That is not evidence of the device being offline.");
        List<EvidenceItem> evidence = List.of(EvidenceItem.present("connectivity:" + d.id() + "/status", "connectivity status", rows.get(0).get("status")));
        return result(r.type(), EvidenceStatus.OBSERVED, deviceEntity(d), now, parseInstant((String) rows.get(0).get("lastChangedAt")), evidence, rows, true, false, null, List.of(),
                lim("This is the last observation the platform recorded, not a live check.", "A connected device can stop reporting a capability, and a silent device is not necessarily disconnected."), null);
    }

    private EvidenceResult activeRules(LookupRequest r, Instant now) {
        UUID device = null;
        Entity entity = new Entity("rules", null, null);
        if (r.deviceId() != null || (r.deviceName() != null && !r.deviceName().isBlank())) {
            Resolved d = resolveDevice(r, now);
            if (d.early() != null) return d.early();
            device = d.id();
            entity = deviceEntity(d);
        }
        int limit = rows(r);
        String deviceText = device == null ? null : device.toString();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<EvidenceItem> evidence = new ArrayList<>();
        boolean truncated = false;
        for (RuleVersionStore.View view : rules.list(true)) {
            if (deviceText != null && !mentions(view.definition(), deviceText)) continue;
            if (rows.size() >= limit) {
                truncated = true;
                break;
            }
            Rule rule = view.definition();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", "ACTIVE");
            m.put("ruleId", view.ruleId().toString());
            m.put("name", clip(rule.name()));
            m.put("priority", rule.priority());
            m.put("lifecycle", view.lifecycle());
            m.put("activeVersionId", String.valueOf(view.activeVersionId()));
            m.put("rowRevision", view.rowRevision());
            m.put("executionEpoch", view.executionEpoch());
            m.put("conditionCount", rule.conditions() == null ? 0 : rule.conditions().size());
            List<String> actionTypes = new ArrayList<>();
            if (rule.actions() != null) for (RuleAction a : rule.actions()) if (a != null && a.actionType() != null) actionTypes.add(a.actionType());
            m.put("actionTypes", actionTypes);
            rows.add(m);
            evidence.add(EvidenceItem.present("rule:" + view.ruleId() + "/activeVersion", "active version of rule", String.valueOf(view.activeVersionId())));
        }
        int inactive = 0;
        if (!truncated) {
            for (RuleVersionStore.View view : rules.list(false)) {
                if ("ACTIVE".equals(view.lifecycle())) continue;
                if (deviceText != null && !mentions(view.definition(), deviceText)) continue;
                if (rows.size() >= limit) {
                    truncated = true;
                    break;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("section", "NOT_ACTIVE");
                m.put("ruleId", view.ruleId().toString());
                m.put("name", clip(view.definition().name()));
                m.put("lifecycle", view.lifecycle());
                rows.add(m);
                evidence.add(EvidenceItem.present("rule:" + view.ruleId() + "/lifecycle", "lifecycle of rule", view.lifecycle()));
                inactive++;
            }
        }
        if (rows.isEmpty()) return noData(r.type(), entity, now, "No rule" + (deviceText == null ? "" : " mentioning this device") + " exists.");
        return result(r.type(), EvidenceStatus.OBSERVED, entity, now, null, evidence, rows, !truncated, truncated, null,
                truncated ? List.of("The list was cut at " + limit + " rows.") : List.of(),
                lim("Only an ACTIVE rule can fire; a rule listed as NOT_ACTIVE does not run.", "A rule being active does not mean it has ever fired or that its action succeeded.", "Rule definitions are not returned, only identity, priority and version ids."), null);
    }

    private EvidenceResult ruleMatches(LookupRequest r, Instant now) {
        UUID device = null;
        Entity entity = new Entity("rule", r.ruleId() == null ? null : r.ruleId().toString(), null);
        if (r.deviceId() != null || (r.deviceName() != null && !r.deviceName().isBlank())) {
            Resolved d = resolveDevice(r, now);
            if (d.early() != null) return d.early();
            device = d.id();
            entity = deviceEntity(d);
        }
        if (r.ruleId() == null && device == null)
            return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, null, now, "MISSING_IDENTIFIER: a rule id or a device is required", general());
        if (r.ruleId() != null && rules.get(r.ruleId()).isEmpty())
            return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_FOUND, new Entity("rule", r.ruleId().toString(), null), now, "NOT_FOUND: no rule has this id", general());
        List<String> notices = new ArrayList<>();
        int hours = lookback(r, notices);
        int limit = rows(r);
        Instant from = now.minus(Duration.ofHours(hours));
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.query("SELECT match_id, rule_id, rule_version_id, rule_revision, matched_at, trigger_type FROM rule_matches"
                        + " WHERE matched_at >= ? AND (?::uuid IS NULL OR rule_id = ?::uuid) AND (?::uuid IS NULL OR match_id IN (SELECT match_id FROM action_history WHERE device_id = ?::uuid))"
                        + " ORDER BY matched_at DESC, match_id DESC LIMIT ?", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("matchId", String.valueOf(rs.getObject("match_id")));
            m.put("ruleId", String.valueOf(rs.getObject("rule_id")));
            m.put("ruleVersionId", String.valueOf(rs.getObject("rule_version_id")));
            m.put("ruleRevision", rs.getObject("rule_revision"));
            m.put("matchedAt", String.valueOf(instant(rs, "matched_at")));
            m.put("triggerType", rs.getString("trigger_type"));
            return m;
        }, Timestamp.from(from), r.ruleId(), r.ruleId(), device, device, limit + 1));
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        List<EvidenceItem> evidence = new ArrayList<>();
        Instant newest = null;
        for (Map<String, Object> m : rows) {
            evidence.add(EvidenceItem.present("match:" + m.get("matchId") + "/matchedAt", "time the rule matched", m.get("matchedAt")));
            newest = later(newest, parseInstant((String) m.get("matchedAt")));
        }
        List<String> limits = lim("A match is a persisted decision that a rule condition held; it is not proof that a command was sent or succeeded.",
                device == null ? "" : "For a device, only matches that produced an action for that device are found.");
        final List<Map<String, Object>> finalRows = rows;
        if (r.ruleId() != null && device != null) {
            Optional<RuleNonMatchExplainer.Result> nonMatch = nonMatches.explain(r.ruleId(), device, TriggerType.ACCEPTED_TELEMETRY);
            nonMatch.ifPresent(result -> {
                Explanation e = result.explanation();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("section", "NON_MATCH_DRY_RUN");
                m.put("hypothetical", e.hypothetical());
                m.put("abstained", e.abstained());
                m.put("summary", clip(e.summary()));
                m.put("platformStatements", e.platformStatements().stream().limit(10).map(this::clip).toList());
                finalRows.add(m);
                evidence.addAll(e.evidence());
            });
        }
        if (rows.isEmpty()) return withNotices(noData(r.type(), entity, now, "No rule match was stored in the last " + hours + " hours."), notices);
        if (more) notices.add("More matches exist in the window.");
        return result(r.type(), EvidenceStatus.OBSERVED, entity, now, newest, evidence, rows, !more, more, null, notices, limits, null);
    }

    private EvidenceResult commandHistory(LookupRequest r, Instant now) {
        UUID device = null;
        Entity entity = new Entity("command", r.commandId() == null ? null : r.commandId().toString(), null);
        if (r.commandId() == null) {
            Resolved d = resolveDevice(r, now);
            if (d.early() != null) return d.early();
            device = d.id();
            entity = deviceEntity(d);
        }
        List<String> notices = new ArrayList<>();
        int hours = lookback(r, notices);
        int limit = rows(r);
        Instant from = now.minus(Duration.ofHours(hours));
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.query("SELECT a.request_id, a.device_id, a.action_type, a.status, a.parameters::text AS parameters, a.timestamp, o.state AS outbox_state, o.attempt_count"
                        + " FROM action_history a LEFT JOIN command_outbox o ON o.command_id = a.request_id"
                        + " WHERE a.action_type = 'SEND_COMMAND' AND (?::uuid IS NULL OR a.request_id = ?::uuid) AND (?::uuid IS NULL OR a.device_id = ?::uuid) AND (?::uuid IS NOT NULL OR a.timestamp >= ?)"
                        + " ORDER BY a.timestamp DESC, a.request_id DESC LIMIT ?", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("commandId", String.valueOf(rs.getObject("request_id")));
            m.put("deviceId", String.valueOf(rs.getObject("device_id")));
            m.put("status", rs.getString("status"));
            m.put("acceptedAt", String.valueOf(instant(rs, "timestamp")));
            m.put("command", commandSummary(rs.getString("parameters")));
            m.put("outboxState", rs.getString("outbox_state"));
            m.put("attempts", rs.getObject("attempt_count"));
            return m;
        }, r.commandId(), r.commandId(), device, device, r.commandId(), Timestamp.from(from), limit + 1));
        boolean more = rows.size() > limit;
        if (more) rows = new ArrayList<>(rows.subList(0, limit));
        if (rows.isEmpty()) {
            if (r.commandId() != null) return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_FOUND, entity, now, "NOT_FOUND: no command has this id", general());
            return withNotices(noData(r.type(), entity, now, "No command was accepted for this device in the last " + hours + " hours."), notices);
        }
        List<EvidenceItem> evidence = new ArrayList<>();
        Instant newest = null;
        for (Map<String, Object> m : rows) {
            evidence.add(EvidenceItem.present("command:" + m.get("commandId") + "/status", "command status", m.get("status")));
            newest = later(newest, parseInstant((String) m.get("acceptedAt")));
        }
        if (more) notices.add("More commands exist in the window.");
        return result(r.type(), EvidenceStatus.OBSERVED, entity, now, newest, evidence, rows, !more, more, null, notices,
                lim("The time is the backend database clock at acceptance.", "An accepted command is not a published command, a published command is not an acknowledged one, and none of them proves the device acted."), null);
    }

    private EvidenceResult deliveryEvidence(LookupRequest r, Instant now) {
        if (r.commandId() == null) return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, null, now, "MISSING_IDENTIFIER: a command id is required", general());
        String id = r.commandId().toString();
        Entity entity = new Entity("command", id, null);
        Map<String, Object> action = new LinkedHashMap<>();
        boolean[] found = new boolean[1];
        jdbc.query("SELECT status, action_type, timestamp, published_at, ack_status, ack_received_at, ack_backend_received_at, ack_device_timestamp, ack_deadline_at FROM action_history WHERE request_id = ?", rs -> {
            found[0] = true;
            action.put("status", rs.getString("status"));
            action.put("acceptedAt", String.valueOf(instant(rs, "timestamp")));
            action.put("publishedAt", instant(rs, "published_at") == null ? null : instant(rs, "published_at").toString());
            action.put("ackStatus", rs.getString("ack_status"));
            action.put("ackReceivedAt", instant(rs, "ack_received_at") == null ? null : instant(rs, "ack_received_at").toString());
            action.put("ackBackendReceivedAt", instant(rs, "ack_backend_received_at") == null ? null : instant(rs, "ack_backend_received_at").toString());
            action.put("ackDeviceTimestamp", instant(rs, "ack_device_timestamp") == null ? null : instant(rs, "ack_device_timestamp").toString());
            action.put("ackDeadlineAt", instant(rs, "ack_deadline_at") == null ? null : instant(rs, "ack_deadline_at").toString());
        }, r.commandId());
        Map<String, Object> outbox = new LinkedHashMap<>();
        jdbc.query("SELECT state, attempt_count, max_attempts, first_publish_confirmed_at, terminal_at, terminal_reason, last_error_code, expires_at FROM command_outbox WHERE command_id = ?", rs -> {
            found[0] = true;
            outbox.put("state", rs.getString("state"));
            outbox.put("attempts", rs.getInt("attempt_count"));
            outbox.put("maxAttempts", rs.getInt("max_attempts"));
            outbox.put("firstPublishConfirmedAt", instant(rs, "first_publish_confirmed_at") == null ? null : instant(rs, "first_publish_confirmed_at").toString());
            outbox.put("terminalAt", instant(rs, "terminal_at") == null ? null : instant(rs, "terminal_at").toString());
            outbox.put("terminalReason", rs.getString("terminal_reason"));
            outbox.put("lastErrorCode", rs.getString("last_error_code"));
            outbox.put("expiresAt", instant(rs, "expires_at") == null ? null : instant(rs, "expires_at").toString());
        }, r.commandId());
        if (!found[0]) return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_FOUND, entity, now, "NOT_FOUND: no command has this id", general());
        int limit = Math.min(rows(r), 100);
        List<Map<String, Object>> facts = jdbc.query("SELECT sequence, fact_type, from_state, to_state, attempt, reason_code, source, occurred_at FROM command_state_history WHERE command_id = ? ORDER BY sequence LIMIT ?", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", "STATE_FACT");
            m.put("sequence", rs.getLong("sequence"));
            m.put("factType", rs.getString("fact_type"));
            m.put("fromState", rs.getString("from_state"));
            m.put("toState", rs.getString("to_state"));
            m.put("attempt", rs.getObject("attempt"));
            m.put("reasonCode", rs.getString("reason_code"));
            m.put("source", rs.getString("source"));
            m.put("occurredAt", String.valueOf(instant(rs, "occurred_at")));
            return m;
        }, r.commandId(), limit);
        Map<String, Object> convergence = new LinkedHashMap<>();
        jdbc.query("SELECT target_key, verification_status, comparison_result, verification_updated_at, verification_deadline_at, discrepancy_reason FROM current_twin_verification_state WHERE desired_command_id = ? ORDER BY target_key LIMIT 1", rs -> {
            convergence.put("targetKey", rs.getString("target_key"));
            convergence.put("verificationStatus", rs.getString("verification_status"));
            convergence.put("comparisonResult", rs.getString("comparison_result"));
            convergence.put("updatedAt", instant(rs, "verification_updated_at") == null ? null : instant(rs, "verification_updated_at").toString());
            convergence.put("deadlineAt", instant(rs, "verification_deadline_at") == null ? null : instant(rs, "verification_deadline_at").toString());
            convergence.put("discrepancyReason", rs.getString("discrepancy_reason"));
        }, r.commandId());

        List<Map<String, Object>> rows = new ArrayList<>();
        List<EvidenceItem> evidence = new ArrayList<>();
        rows.add(section("ACCEPTANCE", "status", action.get("status"), "acceptedAt", action.get("acceptedAt")));
        evidence.add(EvidenceItem.presentOrAbsent("command:" + id + "/status", "command status", action.get("status")));
        Object published = action.get("publishedAt") != null ? action.get("publishedAt") : outbox.get("firstPublishConfirmedAt");
        rows.add(section("PUBLICATION", "publishedAt", published, "outboxState", outbox.get("state")));
        evidence.add(published == null ? EvidenceItem.absent("command:" + id + "/publishedAt", "publication time") : EvidenceItem.present("command:" + id + "/publishedAt", "publication time", published));
        if (!outbox.isEmpty()) {
            Map<String, Object> o = new LinkedHashMap<>(outbox);
            o.put("section", "OUTBOX");
            rows.add(o);
            evidence.add(EvidenceItem.presentOrAbsent("command:" + id + "/outbox/state", "outbox state", outbox.get("state")));
        }
        Map<String, Object> ack = section("ACK", "ackStatus", action.get("ackStatus"), "ackReceivedAt", action.get("ackReceivedAt"));
        ack.put("ackBackendReceivedAt", action.get("ackBackendReceivedAt"));
        ack.put("ackDeviceTimestamp", action.get("ackDeviceTimestamp"));
        rows.add(ack);
        evidence.add(EvidenceItem.presentOrAbsent("command:" + id + "/ack/status", "acknowledgement status", action.get("ackStatus")));
        Map<String, Object> timeout = section("TIMEOUT", "ackDeadlineAt", action.get("ackDeadlineAt"), "timedOut", String.valueOf(facts.stream().anyMatch(f -> "ACK_TIMED_OUT".equals(f.get("factType")))));
        rows.add(timeout);
        rows.addAll(facts);
        for (Map<String, Object> f : facts) evidence.add(EvidenceItem.present("command:" + id + "/history/" + f.get("sequence"), "state-history fact", String.valueOf(f.get("factType"))));
        if (!convergence.isEmpty()) {
            Map<String, Object> c = new LinkedHashMap<>(convergence);
            c.put("section", "CONVERGENCE");
            rows.add(c);
            evidence.add(EvidenceItem.presentOrAbsent("command:" + id + "/verification/status", "verification status", convergence.get("verificationStatus")));
        }
        Instant newest = null;
        for (Map<String, Object> m : rows) for (Object v : m.values()) if (v instanceof String s && s.length() >= 20 && s.length() <= 40 && s.endsWith("Z")) newest = later(newest, parseInstant(s));
        return result(r.type(), EvidenceStatus.OBSERVED, entity, now, newest, evidence, rows, true, false, null, List.of(),
                lim("Acceptance, publication, acknowledgement, timeout and convergence are separate facts.", "A published command is not acknowledged; an acknowledgement is not execution; none of them proves the physical device acted.",
                        "A missing acknowledgement before its deadline is a timeout record, not proof the command was lost.", "The time of an acknowledgement is stamped by the backend on receipt and, where present, by the device."), null);
    }

    private EvidenceResult verification(LookupRequest r, Instant now) {
        Resolved d = resolveDevice(r, now);
        if (d.early() != null) return d.early();
        int limit = rows(r);
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.query("SELECT target_key, subtarget_key, desired_command_id, verification_status, verification_updated_at, verification_deadline_at, comparator_key, comparison_result, numeric_tolerance, readback_value::text AS readback_value, discrepancy_reason, last_readback_event_time, last_readback_received_at"
                + " FROM current_twin_verification_state WHERE device_id = ? ORDER BY target_key, subtarget_key LIMIT ?", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetKey", rs.getString("target_key"));
            m.put("subtargetKey", rs.getString("subtarget_key"));
            m.put("desiredCommandId", String.valueOf(rs.getObject("desired_command_id")));
            m.put("verificationStatus", rs.getString("verification_status"));
            m.put("updatedAt", String.valueOf(instant(rs, "verification_updated_at")));
            m.put("deadlineAt", String.valueOf(instant(rs, "verification_deadline_at")));
            m.put("comparator", rs.getString("comparator_key"));
            m.put("comparisonResult", rs.getString("comparison_result"));
            m.put("numericTolerance", rs.getBigDecimal("numeric_tolerance"));
            m.put("readbackValue", clip(rs.getString("readback_value")));
            m.put("discrepancyReason", rs.getString("discrepancy_reason"));
            m.put("readbackEventTime", String.valueOf(instant(rs, "last_readback_event_time")));
            m.put("readbackReceivedAt", String.valueOf(instant(rs, "last_readback_received_at")));
            return m;
        }, d.id(), limit));
        if (rows.isEmpty()) return noData(r.type(), deviceEntity(d), now, "No verification state is recorded for this device.");
        List<EvidenceItem> evidence = new ArrayList<>();
        Instant newest = null;
        for (Map<String, Object> m : rows) {
            evidence.add(EvidenceItem.presentOrAbsent("verification:" + d.id() + "/" + m.get("targetKey") + "/status", "verification status", m.get("verificationStatus")));
            newest = later(newest, parseInstant((String) m.get("updatedAt")));
        }
        return result(r.type(), EvidenceStatus.OBSERVED, deviceEntity(d), now, newest, evidence, rows, true, false, null, List.of(),
                lim("Verification compares a command with a LATER reading from the device; it is evidence of convergence, not of physical actuation.", "No verification row means the platform made no comparison, not that the device failed."), null);
    }

    private EvidenceResult anomalyLookup(LookupRequest r, Instant now) {
        Resolved d = resolveDevice(r, now);
        if (d.early() != null) return d.early();
        String capability = capability(r);
        if (r.capabilityKey() != null && capability == null)
            return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, deviceEntity(d), now, "INVALID_IDENTIFIER: the capability key is not a capability key", general());
        List<String> notices = new ArrayList<>();
        int hours = Math.min(lookback(r, notices), anomalies.settings().windowHours());
        AnomalyReport report = anomalies.analyse(d.id(), Optional.ofNullable(capability), Optional.of(hours), Optional.empty());
        if (!report.deviceFound()) return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_FOUND, deviceEntity(d), now, "NOT_FOUND: no device has this id", general());
        List<Map<String, Object>> rows = new ArrayList<>();
        List<EvidenceItem> evidence = new ArrayList<>();
        int limit = rows(r);
        boolean truncated = false;
        for (Finding f : report.findings()) {
            if (rows.size() >= limit) {
                truncated = true;
                break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("findingId", f.findingId());
            m.put("capabilityKey", f.capabilityKey());
            m.put("category", f.category().name());
            m.put("status", f.status().name());
            m.put("reason", clip(f.reason()));
            m.put("known", f.known().stream().limit(10).map(this::clip).toList());
            m.put("uncertain", f.uncertain().stream().limit(10).map(this::clip).toList());
            m.put("confirmedFault", f.confirmedFault());
            rows.add(m);
            evidence.add(EvidenceItem.present("finding:" + f.findingId(), "anomaly finding status", f.status().name()));
        }
        if (rows.isEmpty()) return withNotices(noData(r.type(), deviceEntity(d), now, "The analysis produced no findings for this window."), notices);
        EvidenceResult base = result(r.type(), EvidenceStatus.DERIVED, deviceEntity(d), now, report.evaluatedAt(), evidence, rows, !truncated && !report.capabilityListTruncated(), truncated || report.capabilityListTruncated(), null, notices,
                lim("Findings are statistical observations on stored readings, never a confirmed fault.", "INSUFFICIENT_EVIDENCE means the platform looked and cannot say; it is not a healthy result.", "Connectivity is a separate fact and is not merged into the findings."), null);
        return base;
    }

    private EvidenceResult recommendationLookup(LookupRequest r, Instant now) {
        Optional<UUID> device = Optional.empty();
        Entity entity = new Entity("recommendations", null, null);
        if (r.deviceId() != null || (r.deviceName() != null && !r.deviceName().isBlank())) {
            Resolved d = resolveDevice(r, now);
            if (d.early() != null) return d.early();
            device = Optional.of(d.id());
            entity = deviceEntity(d);
        }
        if (device.isEmpty() && r.ruleId() == null)
            return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_AVAILABLE, null, now, "MISSING_IDENTIFIER: a device or a rule is required", general());
        RecommendationService.Report report = recommendations.recommend(device, Optional.ofNullable(r.ruleId()), Optional.empty(), Optional.empty());
        if (!report.scopeFound()) return EvidenceResult.unavailable(r.type(), EvidenceStatus.NOT_FOUND, entity, now, "NOT_FOUND: the device or rule does not exist", general());
        List<Map<String, Object>> rows = new ArrayList<>();
        List<EvidenceItem> evidence = new ArrayList<>();
        int limit = rows(r);
        boolean truncated = report.truncated();
        for (Recommendation rec : report.recommendations()) {
            if (rows.size() >= limit) {
                truncated = true;
                break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recommendationId", rec.id());
            m.put("category", rec.category().name());
            m.put("status", rec.status().name());
            m.put("title", clip(rec.title()));
            m.put("statement", clip(rec.statement()));
            m.put("effect", rec.effect());
            m.put("evidenceRefs", rec.evidenceRefs().stream().limit(20).toList());
            rows.add(m);
            evidence.add(EvidenceItem.present("recommendation:" + rec.id(), "recommendation status", rec.status().name()));
        }
        if (rows.isEmpty()) return noData(r.type(), entity, now, "No recommendation was produced from the stored facts.");
        return result(r.type(), EvidenceStatus.DERIVED, entity, now, report.evaluatedAt(), evidence, rows, !truncated, truncated, null, report.notices(),
                lim("A recommendation is a suggestion for a person to review; nothing was changed, sent or scheduled.", "It is not a diagnosis."), null);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> telemetryRow(ResultSet rs, Instant now) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        Instant event = instant(rs, "timestamp");
        m.put("readingId", String.valueOf(rs.getObject("id")));
        m.put("capabilityKey", rs.getString("capability_key"));
        m.put("value", scalar(rs));
        m.put("unit", rs.getString("unit"));
        m.put("deviceEventTime", event == null ? null : event.toString());
        Freshness f = freshness(event, now);
        m.put("freshness", f.name());
        m.put("ageSeconds", f == Freshness.FUTURE_DATED || event == null ? null : Duration.between(event, now).getSeconds());
        return m;
    }

    private static Object scalar(ResultSet rs) throws SQLException {
        BigDecimal number = rs.getBigDecimal("numeric_value");
        if (number != null) return number.doubleValue();
        Object bool = rs.getObject("boolean_value");
        if (bool != null) return rs.getBoolean("boolean_value");
        String text = rs.getString("string_value");
        return text == null ? null : text.length() > 200 ? text.substring(0, 200) : text;
    }

    private Freshness freshness(Instant evidenceTime, Instant now) {
        if (evidenceTime == null) return Freshness.UNKNOWN;
        if (evidenceTime.isAfter(now.plusSeconds(settings.futureSkewSeconds()))) return Freshness.FUTURE_DATED;
        return Duration.between(evidenceTime, now).getSeconds() > settings.freshnessSeconds() ? Freshness.STALE : Freshness.FRESH;
    }

    private EvidenceResult result(LookupType type, EvidenceStatus status, Entity entity, Instant now, Instant newest, List<EvidenceItem> evidence, List<Map<String, Object>> rows, boolean complete,
                                  boolean truncated, String next, List<String> notices, List<String> limits, String reason) {
        return new EvidenceResult(type, status, entity, now, newest == null ? Freshness.UNKNOWN : freshness(newest, now), newest, evidence, rows, complete, truncated, next, notices, limits, reason);
    }

    private EvidenceResult noData(LookupType type, Entity entity, Instant now, String what) {
        return new EvidenceResult(type, EvidenceStatus.INSUFFICIENT_EVIDENCE, entity, now, Freshness.UNKNOWN, null, List.of(), List.of(), true, false, null, List.of(),
                lim("Missing data is not proof of any physical condition: it does not show that the device is off, broken, idle or healthy."), what);
    }

    private EvidenceResult withNotices(EvidenceResult base, List<String> notices) {
        if (notices.isEmpty()) return base;
        List<String> all = new ArrayList<>(base.notices());
        all.addAll(notices);
        return new EvidenceResult(base.lookup(), base.status(), base.entity(), base.evaluatedAt(), base.freshness(), base.newestEvidenceAt(), base.evidence(), base.rows(), base.complete(), base.truncated(),
                base.nextCursor(), all, base.limits(), base.reason());
    }

    /** Enforces the evidence size bound: rows are dropped from the end until the serialised result fits, and the cut is reported. */
    private EvidenceResult fit(EvidenceResult result) {
        try {
            List<Map<String, Object>> rows = new ArrayList<>(result.rows());
            List<EvidenceItem> evidence = new ArrayList<>(result.evidence());
            boolean cut = false;
            while (size(rows, evidence) > settings.maxEvidenceBytes() && !rows.isEmpty()) {
                rows.remove(rows.size() - 1);
                if (evidence.size() > rows.size()) evidence = new ArrayList<>(evidence.subList(0, Math.max(rows.size(), 0)));
                cut = true;
            }
            if (!cut) return result;
            List<String> notices = new ArrayList<>(result.notices());
            notices.add("The result was cut to fit the evidence size limit of " + settings.maxEvidenceBytes() + " bytes.");
            // a history page keeps a valid continuation point at the last row that was kept, so the cut rows are fetched by the next page and never lost
            String next = null;
            if (result.lookup() == LookupType.TELEMETRY_HISTORY && !rows.isEmpty()) {
                Map<String, Object> last = rows.get(rows.size() - 1);
                next = last.get("deviceEventTime") + "|" + last.get("readingId");
                notices.add("Pass nextCursor to read the rows that were cut.");
            }
            return new EvidenceResult(result.lookup(), result.status(), result.entity(), result.evaluatedAt(), result.freshness(), result.newestEvidenceAt(), evidence, rows, false, true, next, notices, result.limits(), result.reason());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return EvidenceResult.unavailable(result.lookup(), EvidenceStatus.NOT_AVAILABLE, result.entity(), result.evaluatedAt(), "READ_FAILED: the result could not be sized", general());
        }
    }

    private int size(List<Map<String, Object>> rows, List<EvidenceItem> evidence) throws com.fasterxml.jackson.core.JsonProcessingException {
        return mapper.writeValueAsBytes(rows).length + mapper.writeValueAsBytes(evidence).length;
    }

    private int rows(LookupRequest r) {
        int wanted = r.limit() == null ? DEFAULT_ROWS : r.limit();
        return Math.max(1, Math.min(wanted, settings.maxRows()));
    }

    private int lookback(LookupRequest r, List<String> notices) {
        int wanted = r.lookbackHours() == null ? DEFAULT_LOOKBACK_HOURS : r.lookbackHours();
        if (wanted < 1) wanted = 1;
        if (wanted > settings.maxLookbackHours()) {
            notices.add("The requested lookback of " + wanted + " hours was reduced to the maximum of " + settings.maxLookbackHours() + " hours.");
            return settings.maxLookbackHours();
        }
        return wanted;
    }

    private String capability(LookupRequest r) {
        return r.capabilityKey() != null && CAPABILITY.matcher(r.capabilityKey()).matches() ? r.capabilityKey() : null;
    }

    private static boolean mentions(Rule rule, String deviceText) {
        if (rule.conditions() != null)
            for (RuleCondition c : rule.conditions()) if (c != null && deviceText.equalsIgnoreCase(c.expectedValue())) return true;
        if (rule.actions() != null)
            for (RuleAction a : rule.actions()) if (a != null && a.parameters() != null && a.parameters().get("deviceId") != null && deviceText.equalsIgnoreCase(a.parameters().get("deviceId").toString())) return true;
        return false;
    }

    /** Only the allowlisted keys of a command's parameters, as plain text: the capability, the command name, a primitive value and a unit. */
    private String commandSummary(String parameters) {
        if (parameters == null) return null;
        try {
            JsonNode node = mapper.readTree(parameters);
            Map<String, Object> m = new LinkedHashMap<>();
            for (String key : List.of("capability", "capabilityKey", "command", "unit")) if (node.hasNonNull(key) && node.get(key).isTextual()) m.put(key, clip(node.get(key).asText()));
            JsonNode value = node.get("value");
            if (value != null && (value.isNumber() || value.isBoolean() || value.isTextual())) m.put("value", clip(value.asText()));
            else if (value != null && value.isObject() && value.hasNonNull("channel")) m.put("channel", value.get("channel").asText());
            return m.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private String clip(String text) {
        if (text == null) return null;
        StringBuilder out = new StringBuilder(Math.min(text.length(), settings.maxTextChars()));
        for (int i = 0; i < text.length() && out.length() < settings.maxTextChars(); i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        return out.toString();
    }

    private static Map<String, Object> section(String name, String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("section", name);
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Instant parseInstant(String text) {
        if (text == null || "null".equals(text)) return null;
        try {
            return Instant.parse(text);
        } catch (RuntimeException notAnInstant) {
            return null;
        }
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null) return v;
        return null;
    }

    private static List<String> lim(String... limits) {
        List<String> out = new ArrayList<>();
        for (String l : limits) if (l != null && !l.isBlank()) out.add(l);
        out.add("This is a read of stored platform facts at one moment; it does not control or test anything.");
        return out;
    }

    private static List<String> general() {
        return List.of("Nothing could be concluded from this lookup.");
    }
}
