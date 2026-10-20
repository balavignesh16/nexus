package com.nexus.rule.application;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * The transaction-scoped lock that makes an AI draft's conflict check and the transition it protects atomic (P6-043, V43).
 *
 * <p>Writers of the state a conflict analysis compares (rule lifecycle, active and working versions, exclusion-group membership) hold it SHARED; the
 * checks that rely on the analysis (approving a draft, making an approved AI version active, resuming it) hold it EXCLUSIVE from the check until their
 * commit. Shared holders never block each other; an exclusive holder waits for in-flight writers to commit and blocks new ones until it commits, so no
 * relevant change can commit between the check and the transition. It is always the FIRST lock a service acquires (before device, rule and group row
 * locks), so the order is the same everywhere. The database triggers of V43 take the same lock (shared) as a backstop for any other writer and the
 * decision and activation guards take it exclusively; an already-held lock is not requested again by the same transaction. The key matches V43.
 */
public final class ConflictStateLock {
    public static final long KEY = 7066001L;

    private ConflictStateLock() {}

    public static void shared(JdbcTemplate jdbc) { jdbc.queryForObject("SELECT pg_advisory_xact_lock_shared(?)::text", String.class, KEY); }

    public static void exclusive(JdbcTemplate jdbc) { jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class, KEY); }

    /** Exclusive when the rule has an AI-drafted version (its activation or resume is checked against the stored analysis), otherwise shared. */
    public static void forRule(JdbcTemplate jdbc, UUID ruleId, boolean mayActivate) {
        if (mayActivate && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM rule_versions WHERE rule_id = ? AND provenance = 'AI_DRAFT')", Boolean.class, ruleId))) exclusive(jdbc);
        else shared(jdbc);
    }
}
