package com.nexus.intelligence.draft;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V42 on a real PostgreSQL: the approver privilege is the database's own, the activation guard holds on every direct path, decisions are append-only and
 * bound to one exact definition, and concurrent decisions leave exactly one outcome. Ledger P6-046..P6-056.
 */
@Testcontainers
class AiDraftDatabaseTest {
    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void migrate() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE appr LOGIN PASSWORD 'appr'");
            s.execute("GRANT nexus_approver TO appr");
            s.execute("CREATE ROLE plain LOGIN PASSWORD 'plain'");
            s.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO appr, plain");
            s.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO appr, plain");
        }
    }

    static Connection admin() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    static Connection as(String role) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), role, role);
    }

    static String definition(UUID ruleId, String name) {
        return "{\"ruleId\":\"" + ruleId + "\",\"name\":\"" + name + "\",\"enabled\":false,\"priority\":5,\"conditions\":[],\"actions\":[],\"metadata\":{},\"cooldownSeconds\":60}";
    }

    /** Inserts a PENDING_APPROVAL draft that expires in {@code seconds}; returns its id. */
    static UUID draft(Connection c, String digest, int seconds) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO ai_rule_drafts(draft_id,state,definition,definition_hash,content_fingerprint,provider_id,model_id,config_hash,request_digest,evidence_refs,validation,valid,expires_at)"
                        + " VALUES(?,'PENDING_APPROVAL',?::jsonb,'x','x','fake','m','h',?,'[\"device:1\"]'::jsonb,'{}'::jsonb,true,now()+make_interval(secs=>?))")) {
            p.setObject(1, id);
            p.setString(2, definition(UUID.randomUUID(), "AI rule " + id));
            p.setString(3, digest);
            p.setInt(4, seconds);
            p.executeUpdate();
        }
        return id;
    }

    static UUID draft(Connection c) throws SQLException {
        return draft(c, "d".repeat(64), 3600);
    }

    static String text(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    static long count(Connection c, String sql, Object... args) throws SQLException {
        return Long.parseLong(text(c, sql, args));
    }

    static String hash(Connection c, UUID draft) throws SQLException {
        return text(c, "SELECT definition_hash FROM ai_rule_drafts WHERE draft_id=?", draft);
    }

    static String decide(Connection c, UUID draft, String hash, String decision, String osUser) throws SQLException {
        return text(c, "SELECT ai_draft_decide(?,?,?,?,?)", draft, hash, decision, "reviewed", osUser);
    }

    static String sqlState(Runnable0 r) {
        return assertThrows(SQLException.class, r::run).getSQLState();
    }

    interface Runnable0 { void run() throws SQLException; }

    static void activate(Connection c, UUID rule, String version) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE rules SET active_version_id=?::uuid, lifecycle='ACTIVE', enabled=true, reconciliation='NOT_REQUIRED' WHERE rule_id=?")) {
            p.setString(1, version);
            p.setObject(2, rule);
            p.executeUpdate();
        }
    }

    /** A rule root plus one version with the given provenance and definition, created like the lifecycle service does, not active. */
    static String ruleWithVersion(Connection c, UUID ruleId, String provenance, String definition) throws SQLException {
        UUID version = UUID.randomUUID();
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,metadata) VALUES('" + ruleId + "','n',false,1,'[]','[]','{}')");
            s.execute("INSERT INTO rule_versions(version_id,rule_id,revision,definition,created_at,provenance) VALUES('" + version + "','" + ruleId + "',1,'" + definition + "'::jsonb,now(),'" + provenance + "')");
            s.execute("INSERT INTO rule_version_workflow VALUES('" + version + "','DRAFT')");
            s.execute("UPDATE rules SET working_version_id='" + version + "' WHERE rule_id='" + ruleId + "'");
        }
        return version.toString();
    }

    @Test
    void p6_048_v42IsInstalledAfterV41WithoutGaps() throws Exception {
        try (Connection c = admin()) {
            assertThat(text(c, "SELECT version FROM flyway_schema_history WHERE success AND version='42'")).isEqualTo("42");
            assertThat(text(c, "SELECT version FROM flyway_schema_history WHERE success AND version='41'")).isEqualTo("41");
            assertThat(count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$' AND version::int BETWEEN 41 AND 42")).isEqualTo(2);
        }
    }

    @Test
    void p6_039_aDraftIsStoredOutsideRulesWithServerComputedHashAndRefusesBadStates() throws Exception {
        try (Connection c = as("plain")) {
            long rulesBefore = count(c, "SELECT COUNT(*) FROM rules");
            UUID id = draft(c);
            assertThat(hash(c, id)).hasSize(64).isNotEqualTo("x"); // the caller's value is replaced
            assertThat(count(c, "SELECT COUNT(*) FROM rules")).isEqualTo(rulesBefore);
            for (String bad : new String[] {
                    "state='APPROVED'", "state='INVALID'", "definition=definition||'{\"enabled\":true}'", "valid=false"}) {
                UUID other = UUID.randomUUID();
                String sql = "INSERT INTO ai_rule_drafts(draft_id,state,definition,definition_hash,content_fingerprint,provider_id,model_id,config_hash,request_digest,evidence_refs,validation,valid,expires_at)"
                        + " SELECT ?, 'PENDING_APPROVAL', definition, 'x','x',provider_id,model_id,config_hash,request_digest,evidence_refs,validation,valid, now()+interval '1 hour' FROM ai_rule_drafts WHERE draft_id=?";
                String patched = sql.replace("'PENDING_APPROVAL'", bad.startsWith("state=") ? bad.substring(6) : "'PENDING_APPROVAL'")
                        .replace(" definition,", bad.startsWith("definition=") ? " (definition||'{\"enabled\":true}'::jsonb)," : " definition,")
                        .replace("validation,valid, now()", bad.equals("valid=false") ? "validation,false, now()" : "validation,valid, now()");
                assertThat(sqlState(() -> { try (PreparedStatement p = c.prepareStatement(patched)) { p.setObject(1, other); p.setObject(2, id); p.executeUpdate(); } })).as(bad).isIn("23514");
            }
        }
    }

    @Test
    void p6_046_p6_047_onlyADirectMemberOfTheApproverRoleCanDecideAndSuperuserAloneDoesNot() throws Exception {
        UUID id;
        String h;
        try (Connection c = as("plain")) {
            id = draft(c);
            h = hash(c, id);
            UUID d = id;
            String hh = h;
            assertThat(sqlState(() -> decide(c, d, hh, "APPROVED", "root"))).isEqualTo("42501");
            assertThat(sqlState(() -> decide(c, d, hh, "REJECTED", "root"))).isEqualTo("42501");
            assertThat(sqlState(() -> text(c, "INSERT INTO ai_rule_draft_decisions(draft_id,decision,draft_hash,content_fingerprint,evidence_refs,provider_id,model_id,config_hash,actor_kind,db_session_user,db_current_user,backend_pid)"
                    + " VALUES('" + d + "','REJECTED','" + hh + "','x','[]','p','m','h','LOCAL_APPROVER','appr','appr',1) RETURNING 1"))).isEqualTo("42501");
        }
        try (Connection c = admin()) { // the database owner / superuser is not an approver unless explicitly granted
            UUID d = id;
            String hh = h;
            assertThat(sqlState(() -> decide(c, d, hh, "APPROVED", "root"))).isEqualTo("42501");
            assertThat(text(c, "SELECT state FROM ai_rule_drafts WHERE draft_id=?", d)).isEqualTo("PENDING_APPROVAL");
            assertThat(count(c, "SELECT COUNT(*) FROM rules WHERE rule_id=(SELECT (definition->>'ruleId')::uuid FROM ai_rule_drafts WHERE draft_id=?)", d)).isZero();
        }
    }

    @Test
    void p6_048_p6_049_anApprovalIsRecordedWithDatabaseVerifiedActorAndBoundToTheExactVersion() throws Exception {
        try (Connection c = as("appr")) {
            UUID id = draft(c);
            String h = hash(c, id);
            String decision = decide(c, id, h, "APPROVED", "forged-os-user-root");
            assertThat(text(c, "SELECT db_session_user FROM ai_rule_draft_decisions WHERE decision_id=?::uuid", decision)).isEqualTo("appr");
            assertThat(text(c, "SELECT actor_kind FROM ai_rule_draft_decisions WHERE decision_id=?::uuid", decision)).isEqualTo("LOCAL_APPROVER");
            assertThat(text(c, "SELECT os_user_claimed FROM ai_rule_draft_decisions WHERE decision_id=?::uuid", decision)).isEqualTo("forged-os-user-root"); // recorded as a CLAIM only
            assertThat(text(c, "SELECT draft_hash FROM ai_rule_draft_decisions WHERE decision_id=?::uuid", decision)).isEqualTo(h);
            assertThat(text(c, "SELECT v.provenance FROM rule_versions v JOIN ai_rule_draft_decisions x ON x.rule_version_id=v.version_id WHERE x.decision_id=?::uuid", decision)).isEqualTo("AI_DRAFT");
            assertThat(text(c, "SELECT x.rule_version_hash = nexus_sha256_hex(v.definition::text) FROM rule_versions v JOIN ai_rule_draft_decisions x ON x.rule_version_id=v.version_id WHERE x.decision_id=?::uuid", decision)).isEqualTo("t");
            assertThat(text(c, "SELECT r.lifecycle || '/' || r.enabled FROM rules r JOIN ai_rule_draft_decisions x ON x.rule_id=r.rule_id WHERE x.decision_id=?::uuid", decision)).isEqualTo("DRAFT/false"); // approval never activates
            assertThat(text(c, "SELECT state FROM ai_rule_drafts WHERE draft_id=?", id)).isEqualTo("APPROVED");
            // a second decision for the same draft is refused
            assertThat(sqlState(() -> decide(c, id, h, "REJECTED", "x"))).isEqualTo("55000");
        }
    }

    @Test
    void p6_049_p6_055_aStaleHashIsRefusedAndLeavesNothingBehind() throws Exception {
        try (Connection c = as("appr")) {
            UUID id = draft(c);
            long rules = count(c, "SELECT COUNT(*) FROM rules"), versions = count(c, "SELECT COUNT(*) FROM rule_versions"), decisions = count(c, "SELECT COUNT(*) FROM ai_rule_draft_decisions");
            assertThat(sqlState(() -> decide(c, id, "0".repeat(64), "APPROVED", "x"))).isEqualTo("40001");
            assertThat(count(c, "SELECT COUNT(*) FROM rules")).isEqualTo(rules);
            assertThat(count(c, "SELECT COUNT(*) FROM rule_versions")).isEqualTo(versions);
            assertThat(count(c, "SELECT COUNT(*) FROM ai_rule_draft_decisions")).isEqualTo(decisions);
            assertThat(text(c, "SELECT state FROM ai_rule_drafts WHERE draft_id=?", id)).isEqualTo("PENDING_APPROVAL");
        }
    }

    @Test
    void p6_055_aFailureInsideTheDecisionRollsBackTheWholeApproval() throws Exception {
        try (Connection c = as("appr")) {
            UUID id = draft(c);
            String h = hash(c, id);
            // make the rule id collide so the materialisation fails halfway through the function
            String ruleId = text(c, "SELECT definition->>'ruleId' FROM ai_rule_drafts WHERE draft_id=?", id);
            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,metadata) VALUES('" + ruleId + "','squatter',false,1,'[]','[]','{}')");
            }
            long versions = count(c, "SELECT COUNT(*) FROM rule_versions");
            assertThat(sqlState(() -> decide(c, id, h, "APPROVED", "x"))).isEqualTo("23505");
            assertThat(text(c, "SELECT state FROM ai_rule_drafts WHERE draft_id=?", id)).isEqualTo("PENDING_APPROVAL");
            assertThat(count(c, "SELECT COUNT(*) FROM rule_versions")).isEqualTo(versions);
            assertThat(count(c, "SELECT COUNT(*) FROM ai_rule_draft_decisions WHERE draft_id=?", id)).isZero();
        }
    }

    @Test
    void p6_048_decisionsAndDraftContentAreImmutableEvenForTheDatabaseOwner() throws Exception {
        try (Connection c = as("appr")) {
            UUID id = draft(c);
            String h = hash(c, id);
            decide(c, id, h, "APPROVED", "x");
            try (Connection owner = admin()) {
                assertThat(sqlState(() -> text(owner, "UPDATE ai_rule_draft_decisions SET reason='changed' WHERE draft_id='" + id + "' RETURNING 1"))).isEqualTo("P0001");
                assertThat(sqlState(() -> text(owner, "DELETE FROM ai_rule_draft_decisions WHERE draft_id='" + id + "' RETURNING 1"))).isEqualTo("P0001");
                assertThat(sqlState(() -> text(owner, "UPDATE ai_rule_drafts SET definition=definition||'{\"priority\":9}' WHERE draft_id='" + id + "' RETURNING 1"))).isEqualTo("55000");
                assertThat(sqlState(() -> text(owner, "UPDATE ai_rule_drafts SET state='PENDING_APPROVAL' WHERE draft_id='" + id + "' RETURNING 1"))).isEqualTo("55000");
                assertThat(sqlState(() -> text(owner, "DELETE FROM ai_rule_drafts WHERE draft_id='" + id + "' RETURNING 1"))).isEqualTo("55000");
            }
        }
    }

    @Test
    void p6_050_p6_051_theActivationGuardRefusesUnapprovedAiVersionsOnEveryDirectPathAndAllowsTheApprovedOne() throws Exception {
        try (Connection c = as("appr")) {
            // 1. an AI_DRAFT version with no approval row cannot become active
            UUID forged = UUID.randomUUID();
            String forgedVersion = ruleWithVersion(c, forged, "AI_DRAFT", definition(forged, "forged"));
            assertThat(sqlState(() -> activate(c, forged, forgedVersion))).isEqualTo("23514");
            // 2. nor by inserting the root already active
            UUID direct = UUID.randomUUID();
            assertThat(sqlState(() -> { try (Statement s = c.createStatement()) { s.execute("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions,metadata,active_version_id,lifecycle) VALUES('" + direct + "','n',true,1,'[]','[]','{}','" + UUID.randomUUID() + "','ACTIVE')"); } })).isEqualTo("23503");
            // 3. an approved version can (approval is necessary and, with validation elsewhere, enough for the database)
            UUID id = draft(c);
            decide(c, id, hash(c, id), "APPROVED", "x");
            UUID rule = UUID.fromString(text(c, "SELECT rule_id FROM ai_rule_drafts WHERE draft_id=?", id));
            String version = text(c, "SELECT working_version_id FROM rules WHERE rule_id=?", rule);
            activate(c, rule, version);
            assertThat(text(c, "SELECT lifecycle FROM rules WHERE rule_id=?", rule)).isEqualTo("ACTIVE");
        }
    }

    @Test
    void p6_050_aVerbatimCopyOfAnUnapprovedOrRejectedDraftIsRefusedButAnEditAndAnOrdinaryRuleAreNot() throws Exception {
        try (Connection c = as("appr")) {
            UUID id = draft(c);
            String def = text(c, "SELECT definition::text FROM ai_rule_drafts WHERE draft_id=?", id);
            UUID copyRule = UUID.randomUUID();
            String copyDef = def.replace(text(c, "SELECT definition->>'ruleId' FROM ai_rule_drafts WHERE draft_id=?", id), copyRule.toString());
            String copyVersion = ruleWithVersion(c, copyRule, "API_CREATE", copyDef);
            assertThat(sqlState(() -> activate(c, copyRule, copyVersion))).as("pending draft copied by hand").isEqualTo("23514");
            decide(c, id, hash(c, id), "REJECTED", "x");
            assertThat(sqlState(() -> activate(c, copyRule, copyVersion))).as("rejected draft copied by hand").isEqualTo("23514");
            // an edited copy is a different, human-authored definition
            UUID editedRule = UUID.randomUUID();
            String editedVersion = ruleWithVersion(c, editedRule, "API_CREATE", copyDef.replace("\"priority\": 5", "\"priority\": 6").replace("\"priority\":5", "\"priority\":6").replace("AI rule", "Edited rule"));
            activate(c, editedRule, editedVersion);
            // and a rule with nothing to do with any draft is untouched by the guard
            UUID plainRule = UUID.randomUUID();
            activate(c, plainRule = plainRule, ruleWithVersion(c, plainRule, "API_CREATE", definition(plainRule, "Manual rule")));
            assertThat(text(c, "SELECT lifecycle FROM rules WHERE rule_id=?", plainRule)).isEqualTo("ACTIVE");
        }
    }

    @Test
    void p6_052_aRejectedDraftStaysRejectedWithItsReasonAndCannotBeApprovedLater() throws Exception {
        try (Connection c = as("appr")) {
            UUID id = draft(c);
            String h = hash(c, id);
            decide(c, id, h, "REJECTED", "x");
            assertThat(text(c, "SELECT reason FROM ai_rule_draft_decisions WHERE draft_id=?", id)).isEqualTo("reviewed");
            assertThat(sqlState(() -> decide(c, id, h, "APPROVED", "x"))).isEqualTo("55000");
            assertThat(count(c, "SELECT COUNT(*) FROM rules WHERE rule_id=(SELECT (definition->>'ruleId')::uuid FROM ai_rule_drafts WHERE draft_id=?)", id)).isZero();
        }
    }

    @Test
    void p6_044_p6_054_anExpiredDraftCannotBeApprovedAndExpiryIsRecordedOnceBySystem() throws Exception {
        try (Connection c = as("appr")) {
            UUID id = draft(c, "e".repeat(64), 1);
            String h = hash(c, id);
            Thread.sleep(1_300);
            assertThat(sqlState(() -> decide(c, id, h, "APPROVED", "x"))).isEqualTo("55000");
            assertThat(text(c, "SELECT ai_draft_expire_due()")).isNotEqualTo("0");
            assertThat(text(c, "SELECT state FROM ai_rule_drafts WHERE draft_id=?", id)).isEqualTo("EXPIRED");
            assertThat(text(c, "SELECT actor_kind || '/' || decision FROM ai_rule_draft_decisions WHERE draft_id=?", id)).isEqualTo("SYSTEM/EXPIRED");
            assertThat(text(c, "SELECT ai_draft_expire_due()")).isEqualTo("0");
            // a draft that has NOT expired cannot be expired by anyone
            UUID fresh = draft(c);
            assertThat(sqlState(() -> text(c, "INSERT INTO ai_rule_draft_decisions(draft_id,decision,draft_hash,content_fingerprint,evidence_refs,provider_id,model_id,config_hash,actor_kind,db_session_user,db_current_user,backend_pid)"
                    + " VALUES('" + fresh + "','EXPIRED','" + hash(c, fresh) + "','x','[]','p','m','h','SYSTEM','x','x',1) RETURNING 1"))).isEqualTo("55000");
        }
    }

    @Test
    void p6_044_aNewerDraftForTheSameRequestSupersedesTheOlderOne() throws Exception {
        try (Connection c = as("appr")) {
            String digest = "5".repeat(64);
            UUID older = draft(c, digest, 3600);
            Thread.sleep(20);
            UUID newer = draft(c, digest, 3600);
            assertThat(text(c, "SELECT ai_draft_supersede(?)", newer)).isEqualTo("1");
            assertThat(text(c, "SELECT state FROM ai_rule_drafts WHERE draft_id=?", older)).isEqualTo("SUPERSEDED");
            assertThat(sqlState(() -> decide(c, older, hash(c, older), "APPROVED", "x"))).isEqualTo("55000");
            decide(c, newer, hash(c, newer), "APPROVED", "x"); // the newer one is still approvable
            // nothing supersedes without a newer draft
            UUID lone = draft(c, "6".repeat(64), 3600);
            assertThat(sqlState(() -> text(c, "INSERT INTO ai_rule_draft_decisions(draft_id,decision,draft_hash,content_fingerprint,evidence_refs,provider_id,model_id,config_hash,actor_kind,db_session_user,db_current_user,backend_pid)"
                    + " VALUES('" + lone + "','SUPERSEDED','" + hash(c, lone) + "','x','[]','p','m','h','SYSTEM','x','x',1) RETURNING 1"))).isEqualTo("55000");
        }
    }

    @Test
    void p6_056_concurrentDecisionsOnOneDraftLeaveExactlyOneOutcome() throws Exception {
        UUID id;
        String h;
        try (Connection c = as("appr")) {
            id = draft(c);
            h = hash(c, id);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String decision = i % 4 == 3 ? "REJECTED" : "APPROVED";
            UUID d = id;
            String hh = h;
            results.add(pool.submit(() -> {
                try (Connection c = as("appr")) {
                    go.await();
                    decide(c, d, hh, decision, "t");
                    return true;
                } catch (SQLException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int won = 0;
        for (Future<Boolean> f : results) if (f.get()) won++;
        pool.shutdown();
        assertThat(won).isEqualTo(1);
        try (Connection c = admin()) {
            assertThat(count(c, "SELECT COUNT(*) FROM ai_rule_draft_decisions WHERE draft_id=?", id)).isEqualTo(1);
            String state = text(c, "SELECT state FROM ai_rule_drafts WHERE draft_id=?", id);
            assertThat(state).isIn("APPROVED", "REJECTED");
            long rules = count(c, "SELECT COUNT(*) FROM rules WHERE rule_id=(SELECT (definition->>'ruleId')::uuid FROM ai_rule_drafts WHERE draft_id=?)", id);
            assertThat(rules).isEqualTo(state.equals("APPROVED") ? 1 : 0);
        }
    }

    @Test
    void p6_056_concurrentActivationOfAnApprovedVersionActivatesItOnce() throws Exception {
        UUID rule;
        String version;
        try (Connection c = as("appr")) {
            UUID id = draft(c);
            decide(c, id, hash(c, id), "APPROVED", "t");
            rule = UUID.fromString(text(c, "SELECT rule_id FROM ai_rule_drafts WHERE draft_id=?", id));
            version = text(c, "SELECT working_version_id FROM rules WHERE rule_id=?", rule);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID r = rule;
            String v = version;
            results.add(pool.submit(() -> {
                try (Connection c = as("plain")) {
                    go.await();
                    try (PreparedStatement p = c.prepareStatement("UPDATE rules SET active_version_id=?::uuid, lifecycle='ACTIVE', enabled=true, reconciliation='NOT_REQUIRED', execution_epoch=execution_epoch+1 WHERE rule_id=? AND lifecycle<>'ACTIVE'")) {
                        p.setString(1, v);
                        p.setObject(2, r);
                        return p.executeUpdate();
                    }
                }
            }));
        }
        go.countDown();
        int changed = 0;
        for (Future<Integer> f : results) changed += f.get();
        pool.shutdown();
        assertThat(changed).isEqualTo(1);
        try (Connection c = admin()) {
            assertThat(text(c, "SELECT execution_epoch FROM rules WHERE rule_id=?", rule)).isEqualTo("1");
        }
    }

    @Test
    void p6_054_retentionRemovesOnlyOldUnapprovedDraftBodiesAndKeepsEveryDecisionAndApprovedDraft() throws Exception {
        try (Connection c = as("appr"); Connection owner = admin()) {
            UUID rejected = draft(c, "a1".repeat(32), 3600), approved = draft(c, "a2".repeat(32), 3600), pending = draft(c, "a3".repeat(32), 3600);
            decide(c, rejected, hash(c, rejected), "REJECTED", "x");
            decide(c, approved, hash(c, approved), "APPROVED", "x");
            // age everything by 40 days (test-only: the creation time is otherwise server-assigned)
            try (Statement s = owner.createStatement()) {
                s.execute("ALTER TABLE ai_rule_drafts DISABLE TRIGGER ai_rule_drafts_guard");
                s.execute("UPDATE ai_rule_drafts SET created_at = created_at - interval '40 days', expires_at = expires_at - interval '40 days' WHERE draft_id IN ('" + rejected + "','" + approved + "','" + pending + "')");
                s.execute("ALTER TABLE ai_rule_drafts ENABLE TRIGGER ai_rule_drafts_guard");
            }
            assertThat(sqlState(() -> text(c, "SELECT ai_draft_purge(interval '1 hour')"))).isEqualTo("22023");
            long decisions = count(owner, "SELECT COUNT(*) FROM ai_rule_draft_decisions");
            assertThat(text(c, "SELECT ai_draft_expire_due()")).isNotNull();
            text(c, "SELECT ai_draft_purge(interval '30 days')");
            assertThat(count(owner, "SELECT COUNT(*) FROM ai_rule_drafts WHERE draft_id=?", rejected)).isZero();
            assertThat(count(owner, "SELECT COUNT(*) FROM ai_rule_drafts WHERE draft_id=?", pending)).isZero(); // expired first, then purged
            assertThat(count(owner, "SELECT COUNT(*) FROM ai_rule_drafts WHERE draft_id=?", approved)).isEqualTo(1);
            assertThat(count(owner, "SELECT COUNT(*) FROM ai_rule_draft_decisions")).isGreaterThanOrEqualTo(decisions + 1); // the expiry decision was added, none removed
            assertThat(count(owner, "SELECT COUNT(*) FROM ai_rule_draft_decisions WHERE draft_id IN (?,?,?)", rejected, approved, pending)).isEqualTo(3);
            assertThat(count(owner, "SELECT COUNT(*) FROM rule_versions WHERE provenance='AI_DRAFT'")).isGreaterThanOrEqualTo(1); // authoritative versions untouched
        }
    }
}
