-- V43 (P6.6): the mutual-exclusion analysis stored with a draft (P6-043) must not be silently relied on after the rules or exclusion groups it compared have changed.
-- The analyzer (DraftConflictAnalyzer) records, inside its read-only snapshot, the draft's command target keys and a fingerprint of the persisted state it compared:
-- the rules that command those targets (active versions of active rules, working versions of the others), their lifecycle, and every member of the exclusion groups
-- they belong to. Approval and the first activation of the approved version recompute that fingerprint and refuse with SQLSTATE P0A01 when it differs.
-- Owner: this file. Writers: none (read-only functions). Readers: the decision trigger, the activation guard and the approval CLI.
-- Atomicity (closes the check-to-commit window): one transaction-scoped advisory lock (key 7066001, see ConflictStateLock). Every writer of the compared state holds it
-- SHARED (the services take it first, before any row lock; the statement triggers below take it for any other writer). The checks that rely on the analysis (approval,
-- first activation and resume of an AI version) hold it EXCLUSIVE from before the fingerprint is recomputed until their commit, so they wait for in-flight writers and
-- no relevant change can commit in between. The freshness functions are VOLATILE so that each recomputation takes a fresh snapshot AFTER the lock is held.
-- Target keys follow ActionArbiter.targetKey (device | capability | relay channel, lower-cased); DraftConflictAnalysisIntegrationTest compares the two.

CREATE FUNCTION ai_command_targets(def jsonb) RETURNS text[] LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(array_agg(DISTINCT k ORDER BY k), '{}'::text[]) FROM (
        SELECT lower(q.p ->> 'deviceId') || '|' || lower(btrim(COALESCE(q.p ->> 'capabilityKey', q.p ->> 'capability'))) || '|' ||
               CASE WHEN jsonb_typeof(q.p -> 'value') = 'object' AND jsonb_typeof(q.p -> 'value' -> 'channel') = 'number'
                    THEN 'channel:' || trunc((q.p -> 'value' ->> 'channel')::numeric)::int ELSE '' END AS k
        FROM jsonb_array_elements(CASE WHEN jsonb_typeof(def -> 'actions') = 'array' THEN def -> 'actions' ELSE '[]'::jsonb END) a
        CROSS JOIN LATERAL (SELECT a -> 'parameters' AS p) q
        WHERE a ->> 'actionType' = 'SEND_COMMAND' AND jsonb_typeof(q.p) = 'object' AND q.p ->> 'deviceId' IS NOT NULL
          AND COALESCE(q.p ->> 'capabilityKey', q.p ->> 'capability') IS NOT NULL
    ) t
$$;

CREATE FUNCTION ai_conflict_fingerprint(p_targets text[], p_exclude uuid) RETURNS text LANGUAGE sql VOLATILE AS $$
    WITH relevant AS (
        SELECT r.rule_id, r.lifecycle, (r.lifecycle = 'ACTIVE' AND r.reconciliation IN ('PASSED', 'NOT_REQUIRED')) AS is_active,
               CASE WHEN r.lifecycle = 'ACTIVE' AND r.reconciliation IN ('PASSED', 'NOT_REQUIRED') THEN r.active_version_id ELSE r.working_version_id END AS vid
        FROM rules r WHERE r.lifecycle <> 'ARCHIVED' AND r.rule_id IS DISTINCT FROM p_exclude
    ), shared AS (
        SELECT x.rule_id, x.lifecycle, x.is_active, x.vid FROM relevant x JOIN rule_versions v ON v.version_id = x.vid
        WHERE ai_command_targets(v.definition) && p_targets
    ), lines AS (
        SELECT 'R|' || rule_id || '|' || vid || '|' || lifecycle || '|' || is_active AS l FROM shared
        UNION ALL
        SELECT 'M|' || m.group_id || '|' || m.rule_id || '|' || m.rank || '|' || COALESCE(r2.lifecycle, '?')
        FROM rule_exclusion_members m JOIN rules r2 ON r2.rule_id = m.rule_id
        WHERE m.group_id IN (SELECT group_id FROM rule_exclusion_members WHERE rule_id IN (SELECT rule_id FROM shared))
    )
    SELECT nexus_sha256_hex(COALESCE(string_agg(l, E'\n' ORDER BY l), '')) FROM lines
$$;

-- true when the draft was analysed and the state it was compared with has changed since. A draft stored without an analysis (before this version) is not judged here.
CREATE FUNCTION ai_conflict_stale(p_draft uuid) RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$
DECLARE d ai_rule_drafts%ROWTYPE; a jsonb;
BEGIN
    SELECT * INTO d FROM ai_rule_drafts WHERE draft_id = p_draft;
    IF NOT FOUND THEN RETURN false; END IF;
    a := d.validation -> 'conflictAnalysis';
    IF a IS NULL OR a ->> 'stateFingerprint' IS NULL THEN RETURN false; END IF;
    RETURN ai_conflict_fingerprint(ARRAY(SELECT jsonb_array_elements_text(a -> 'targetKeys')), (d.definition ->> 'ruleId')::uuid) IS DISTINCT FROM (a ->> 'stateFingerprint');
END
$$;

CREATE OR REPLACE FUNCTION ai_rule_draft_decisions_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d ai_rule_drafts%ROWTYPE;
BEGIN
    -- The actor is read from the connection, never from the row being inserted.
    NEW.decided_at := now();
    NEW.db_session_user := session_user;
    NEW.db_current_user := current_user;
    NEW.client_addr := inet_client_addr();
    NEW.backend_pid := pg_backend_pid();
    NEW.actor_kind := CASE WHEN NEW.decision IN ('EXPIRED', 'SUPERSEDED') THEN 'SYSTEM' ELSE 'LOCAL_APPROVER' END;
    IF NEW.decision IN ('APPROVED', 'REJECTED') AND NOT ai_is_local_approver() THEN
        RAISE EXCEPTION 'session user % is not a local approver', session_user USING ERRCODE = '42501';
    END IF;
    SELECT * INTO d FROM ai_rule_drafts WHERE draft_id = NEW.draft_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'draft not found' USING ERRCODE = 'P0002'; END IF;
    IF d.state <> 'PENDING_APPROVAL' THEN RAISE EXCEPTION 'draft is %, not pending', d.state USING ERRCODE = '55000'; END IF;
    IF NEW.draft_hash <> d.definition_hash THEN RAISE EXCEPTION 'decision names a different draft content' USING ERRCODE = '40001'; END IF;
    NEW.content_fingerprint := d.content_fingerprint;
    NEW.evidence_refs := d.evidence_refs;
    NEW.provider_id := d.provider_id;
    NEW.model_id := d.model_id;
    NEW.config_hash := d.config_hash;
    IF NEW.decision = 'SUPERSEDED' THEN
        IF NOT EXISTS (SELECT 1 FROM ai_rule_drafts n WHERE n.request_digest = d.request_digest AND n.created_at > d.created_at AND n.state IN ('PENDING_APPROVAL', 'APPROVED')) THEN
            RAISE EXCEPTION 'no newer draft supersedes this one' USING ERRCODE = '55000';
        END IF;
    ELSIF NEW.decision = 'EXPIRED' THEN
        IF d.expires_at > now() THEN RAISE EXCEPTION 'draft has not expired' USING ERRCODE = '55000'; END IF;
    ELSIF NEW.decision = 'APPROVED' THEN
        IF NOT d.valid THEN RAISE EXCEPTION 'an invalid draft cannot be approved' USING ERRCODE = '23514'; END IF;
        IF d.expires_at <= now() THEN RAISE EXCEPTION 'draft has expired' USING ERRCODE = '55000'; END IF;
        PERFORM pg_advisory_xact_lock(7066001);
        IF ai_conflict_stale(NEW.draft_id) THEN
            RAISE EXCEPTION 'stale conflict analysis: the rules or exclusion groups this draft was checked against have changed since it was created' USING ERRCODE = 'P0A01';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM rule_versions v WHERE v.version_id = NEW.rule_version_id AND v.rule_id = NEW.rule_id AND v.provenance = 'AI_DRAFT'
                          AND nexus_sha256_hex(v.definition::text) = NEW.rule_version_hash AND v.definition = d.definition) THEN
            RAISE EXCEPTION 'approval must bind the exact AI_DRAFT version created from this draft' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION rules_ai_activation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v rule_versions%ROWTYPE; fp text; approved boolean; unapproved_copy boolean; approved_draft uuid; becoming_active boolean; resuming boolean;
BEGIN
    IF NEW.active_version_id IS NULL THEN RETURN NEW; END IF;
    becoming_active := TG_OP = 'INSERT' OR NEW.active_version_id IS DISTINCT FROM OLD.active_version_id;
    resuming := TG_OP = 'UPDATE' AND NEW.lifecycle = 'ACTIVE' AND OLD.lifecycle IS DISTINCT FROM 'ACTIVE';
    IF NOT becoming_active AND NOT resuming THEN RETURN NEW; END IF;
    SELECT * INTO v FROM rule_versions WHERE version_id = NEW.active_version_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'the active version must already exist' USING ERRCODE = '23503'; END IF;
    fp := ai_definition_fingerprint(v.definition);
    approved := EXISTS (SELECT 1 FROM ai_rule_draft_decisions x
                         WHERE x.decision = 'APPROVED' AND x.rule_version_id = v.version_id AND x.rule_version_hash = nexus_sha256_hex(v.definition::text));
    unapproved_copy := (EXISTS (SELECT 1 FROM ai_rule_drafts d WHERE d.content_fingerprint = fp AND d.state <> 'APPROVED')
                        OR EXISTS (SELECT 1 FROM ai_rule_draft_decisions x WHERE x.content_fingerprint = fp AND x.decision <> 'APPROVED'))
                       AND NOT EXISTS (SELECT 1 FROM ai_rule_draft_decisions x WHERE x.content_fingerprint = fp AND x.decision = 'APPROVED');
    IF (v.provenance = 'AI_DRAFT' OR unapproved_copy) AND NOT approved THEN
        RAISE EXCEPTION 'an AI-proposed rule version cannot become active without a recorded approval of this exact version' USING ERRCODE = '23514';
    END IF;
    -- An approved AI version is rechecked whenever it becomes active: the first activation AND every resume (the rule may have been paused for a long time).
    IF v.provenance = 'AI_DRAFT' AND approved THEN
        PERFORM pg_advisory_xact_lock(7066001);
        SELECT x.draft_id INTO approved_draft FROM ai_rule_draft_decisions x WHERE x.decision = 'APPROVED' AND x.rule_version_id = v.version_id LIMIT 1;
        IF approved_draft IS NOT NULL AND ai_conflict_stale(approved_draft) THEN
            RAISE EXCEPTION 'stale conflict analysis: the rules or exclusion groups this draft was checked against have changed since it was created' USING ERRCODE = 'P0A01';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- The activation guard also fires when a rule is resumed (lifecycle becomes ACTIVE) and when its enabled flag or lifecycle is set on insert.
DROP TRIGGER rules_ai_activation_guard ON rules;
CREATE TRIGGER rules_ai_activation_guard BEFORE INSERT OR UPDATE OF active_version_id, lifecycle, enabled ON rules FOR EACH ROW EXECUTE FUNCTION rules_ai_activation_guard();

-- ai_draft_decide re-issued with the exclusive lock taken FIRST (before the draft row lock and before the rule row is inserted, which would otherwise take the shared
-- lock and make an upgrade necessary). Otherwise identical to V42.
CREATE OR REPLACE FUNCTION ai_draft_decide(p_draft uuid, p_expected_hash text, p_decision text, p_reason text, p_os_user text) RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE
    d ai_rule_drafts%ROWTYPE;
    v uuid := gen_random_uuid();
    did uuid := gen_random_uuid();
    def jsonb;
BEGIN
    IF p_decision NOT IN ('APPROVED', 'REJECTED') THEN RAISE EXCEPTION 'decision must be APPROVED or REJECTED' USING ERRCODE = '22023'; END IF;
    IF p_decision = 'APPROVED' THEN PERFORM pg_advisory_xact_lock(7066001); END IF;
    SELECT * INTO d FROM ai_rule_drafts WHERE draft_id = p_draft FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'draft not found' USING ERRCODE = 'P0002'; END IF;
    IF d.state <> 'PENDING_APPROVAL' THEN RAISE EXCEPTION 'draft is %, not pending', d.state USING ERRCODE = '55000'; END IF;
    IF d.definition_hash <> p_expected_hash THEN RAISE EXCEPTION 'the draft changed since it was reviewed' USING ERRCODE = '40001'; END IF;
    IF p_decision = 'REJECTED' THEN
        INSERT INTO ai_rule_draft_decisions (decision_id, draft_id, decision, draft_hash, content_fingerprint, reason, evidence_refs, provider_id, model_id, config_hash,
                                              actor_kind, db_session_user, db_current_user, backend_pid, os_user_claimed)
        VALUES (did, p_draft, 'REJECTED', d.definition_hash, d.content_fingerprint, left(p_reason, 500), d.evidence_refs, d.provider_id, d.model_id, d.config_hash,
                'LOCAL_APPROVER', session_user, current_user, pg_backend_pid(), left(p_os_user, 128));
        UPDATE ai_rule_drafts SET state = 'REJECTED' WHERE draft_id = p_draft;
        RETURN did;
    END IF;
    def := d.definition;
    INSERT INTO rules (rule_id, name, enabled, priority, conditions, actions, metadata, cooldown_seconds)
    VALUES ((def ->> 'ruleId')::uuid, def ->> 'name', false, (def ->> 'priority')::integer, def -> 'conditions', def -> 'actions',
            COALESCE(def -> 'metadata', '{}'::jsonb), (def ->> 'cooldownSeconds')::integer);
    INSERT INTO rule_versions (version_id, rule_id, revision, definition, created_at, provenance)
    VALUES (v, (def ->> 'ruleId')::uuid, 1, def, now(), 'AI_DRAFT');
    INSERT INTO rule_version_workflow VALUES (v, 'DRAFT');
    UPDATE rules SET working_version_id = v WHERE rule_id = (def ->> 'ruleId')::uuid;
    INSERT INTO ai_rule_draft_decisions (decision_id, draft_id, decision, draft_hash, content_fingerprint, rule_id, rule_version_id, rule_version_hash, reason, evidence_refs,
                                          provider_id, model_id, config_hash, actor_kind, db_session_user, db_current_user, backend_pid, os_user_claimed)
    VALUES (did, p_draft, 'APPROVED', d.definition_hash, d.content_fingerprint, (def ->> 'ruleId')::uuid, v, nexus_sha256_hex(def::text), left(p_reason, 500), d.evidence_refs,
            d.provider_id, d.model_id, d.config_hash, 'LOCAL_APPROVER', session_user, current_user, pg_backend_pid(), left(p_os_user, 128));
    UPDATE ai_rule_drafts SET state = 'APPROVED', rule_id = (def ->> 'ruleId')::uuid WHERE draft_id = p_draft;
    RETURN did;
END
$$;

-- Backstop for every writer of the compared state that does not go through the services: a BEFORE ROW trigger takes the shared lock. It is a ROW trigger named to
-- sort AFTER rules_ai_activation_guard, so a transaction that must hold the lock EXCLUSIVELY (an AI version becoming active) has taken it before this trigger asks for
-- the shared mode (a shared lock is never upgraded: two transactions that each held it shared and wanted it exclusive would deadlock). A transaction that already
-- holds the lock in either mode is not blocked by itself.
CREATE FUNCTION ai_conflict_state_writer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock_shared(7066001);
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER rules_conflict_state_writer BEFORE INSERT OR DELETE OR UPDATE OF lifecycle, enabled, active_version_id, working_version_id, reconciliation ON rules
    FOR EACH ROW EXECUTE FUNCTION ai_conflict_state_writer();
CREATE TRIGGER rule_exclusion_members_conflict_state_writer BEFORE INSERT OR UPDATE OR DELETE ON rule_exclusion_members
    FOR EACH ROW EXECUTE FUNCTION ai_conflict_state_writer();
