package com.nexus.intelligence.draft.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.intelligence.model.ProviderIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY writer of ai_rule_drafts. It can store a proposal, supersede older ones, expire and purge by the database's own functions. It cannot decide a
 * draft (the database refuses anyone who is not a local approver) and it never touches rules or rule_versions.
 */
@Repository
public class DraftStore {
    public record Catalogue(UUID id, String type, List<String> capabilities) {}

    public record View(UUID draftId, String state, boolean valid, String definitionHash, Instant createdAt, Instant expiresAt, String providerId, String modelId,
                       String configHash, String rationale, JsonNode evidenceRefs, JsonNode validation, JsonNode definition, UUID ruleId) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final int retentionDays;

    public DraftStore(JdbcTemplate jdbc, ObjectMapper mapper, @Value("${nexus.ai.draft.retention-days:30}") int retentionDays) {
        if (retentionDays < 1 || retentionDays > 365) throw new IllegalStateException("nexus.ai.draft.retention-days must be 1 to 365");
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.retentionDays = retentionDays;
    }

    public int retentionDays() {
        return retentionDays;
    }

    /** Installed devices, reduced to what a rule needs: id, type and the capabilities (type, direction, unit). No names, serials or descriptions. */
    public List<Catalogue> catalogue(int limit) {
        return jdbc.query("SELECT d.id, d.device_type, COALESCE(string_agg(c.capability_type || ':' || c.direction || ':' || COALESCE(c.unit,''), ',' ORDER BY c.capability_type, c.direction), '') AS caps"
                        + " FROM devices d LEFT JOIN device_capabilities c ON c.device_id = d.id GROUP BY d.id, d.device_type ORDER BY d.id LIMIT ?",
                (rs, n) -> new Catalogue(rs.getObject("id", UUID.class), rs.getString("device_type"), rs.getString("caps").isEmpty() ? List.of() : List.of(rs.getString("caps").split(","))), limit);
    }

    /** Stores the proposal. {@code valid} decides PENDING_APPROVAL or INVALID; the database computes the hash, fingerprint, creation time and expiry window. */
    public String insert(UUID draftId, boolean valid, String definitionJson, ProviderIdentity identity, String requestDigest, String rationale, List<String> evidenceRefs, String validationJson) {
        try {
            return jdbc.queryForObject("INSERT INTO ai_rule_drafts(draft_id,state,definition,definition_hash,content_fingerprint,provider_id,model_id,config_hash,request_digest,rationale,evidence_refs,validation,valid,expires_at)"
                            + " VALUES(?,?,?::jsonb,'-','-',?,?,?,?,?,?::jsonb,?::jsonb,?, now() + make_interval(days => ?)) RETURNING definition_hash",
                    String.class, draftId, valid ? "PENDING_APPROVAL" : "INVALID", definitionJson, identity.providerId(), identity.modelId(), identity.configHash(), requestDigest,
                    rationale, mapper.writeValueAsString(evidenceRefs), validationJson, valid, retentionDays);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("evidence references are not serialisable", e);
        }
    }

    public int supersede(UUID draftId) {
        Integer n = jdbc.queryForObject("SELECT ai_draft_supersede(?)", Integer.class, draftId);
        return n == null ? 0 : n;
    }

    public int expireDue() {
        Integer n = jdbc.queryForObject("SELECT ai_draft_expire_due()", Integer.class);
        return n == null ? 0 : n;
    }

    /** Removes the bodies of unapproved drafts older than the retention window. Decisions and approved drafts are never removed. */
    public int purge() {
        Integer n = jdbc.queryForObject("SELECT ai_draft_purge(make_interval(days => ?))", Integer.class, retentionDays);
        return n == null ? 0 : n;
    }

    public Optional<View> find(UUID id) {
        return jdbc.query(SELECT + " WHERE draft_id = ?", (rs, n) -> view(rs), id).stream().findFirst();
    }

    public List<View> recent(int limit) {
        return jdbc.query(SELECT + " ORDER BY created_at DESC LIMIT ?", (rs, n) -> view(rs), limit);
    }

    private static final String SELECT = "SELECT draft_id,state,valid,definition_hash,created_at,expires_at,provider_id,model_id,config_hash,rationale,evidence_refs::text AS evidence_refs,"
            + "validation::text AS validation,definition::text AS definition,rule_id FROM ai_rule_drafts";

    private View view(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new View(rs.getObject("draft_id", UUID.class), rs.getString("state"), rs.getBoolean("valid"), rs.getString("definition_hash"),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), rs.getString("provider_id"), rs.getString("model_id"),
                    rs.getString("config_hash"), rs.getString("rationale"), mapper.readTree(rs.getString("evidence_refs")), mapper.readTree(rs.getString("validation")),
                    mapper.readTree(rs.getString("definition")), rs.getObject("rule_id", UUID.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("unreadable stored draft", e);
        }
    }
}
