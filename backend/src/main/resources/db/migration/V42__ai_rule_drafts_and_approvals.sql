-- Phase 6.3: AI-proposed rule drafts, append-only approval decisions and a database guard on activation.
--
-- A draft is a PROPOSAL held outside `rules`. It becomes a (still inactive) rule version only when a local approver decides APPROVED, and that version
-- can become the active version only while an approval bound to its exact content hash exists. The guard is a trigger on `rules`, so it holds for every
-- path that sets rules.active_version_id (service, legacy REST save, reconciliation, direct SQL by an ordinary role).

CREATE FUNCTION nexus_sha256_hex(t text) RETURNS text LANGUAGE sql IMMUTABLE AS
$$ SELECT encode(sha256(convert_to(t, 'UTF8')), 'hex') $$;

-- Content identity of a rule definition, independent of the rule id and the enabled flag, so a verbatim copy of a draft under another id is recognised.
CREATE FUNCTION ai_definition_fingerprint(d jsonb) RETURNS text LANGUAGE sql IMMUTABLE AS
$$ SELECT nexus_sha256_hex((d - 'ruleId' - 'enabled')::text) $$;

-- The group role whose DIRECT members are local approvers. Created NOLOGIN when the migrating account may; otherwise the operator creates it, and until then
-- every approval is refused (fail closed).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexus_approver') THEN
        CREATE ROLE nexus_approver NOLOGIN;
    END IF;
EXCEPTION WHEN insufficient_privilege THEN
    RAISE NOTICE 'role nexus_approver was not created (insufficient privilege); create it before approving drafts';
END
$$;

-- Verified by the database, not claimed by a caller: the login of THIS connection must be a direct member of nexus_approver.
-- Superuser status alone does not count.
CREATE FUNCTION ai_is_local_approver() RETURNS boolean LANGUAGE sql STABLE AS
$$ SELECT EXISTS (
       SELECT 1 FROM pg_auth_members m
         JOIN pg_roles g ON g.oid = m.roleid
         JOIN pg_roles u ON u.oid = m.member
        WHERE g.rolname = 'nexus_approver' AND u.rolname = session_user) $$;

CREATE TABLE ai_rule_drafts (
    draft_id uuid PRIMARY KEY,
    state varchar(20) NOT NULL CHECK (state IN ('PENDING_APPROVAL', 'INVALID', 'APPROVED', 'REJECTED', 'EXPIRED', 'SUPERSEDED')),
    definition jsonb NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    definition_hash char(64) NOT NULL,
    content_fingerprint char(64) NOT NULL,
    provider_id varchar(64) NOT NULL,
    model_id varchar(128) NOT NULL,
    config_hash varchar(64) NOT NULL,
    request_digest char(64) NOT NULL,
    rationale varchar(1000),
    evidence_refs jsonb NOT NULL CHECK (jsonb_typeof(evidence_refs) = 'array' AND jsonb_array_length(evidence_refs) BETWEEN 1 AND 20),
    validation jsonb NOT NULL CHECK (jsonb_typeof(validation) = 'object'),
    valid boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    rule_id uuid,
    CHECK (expires_at > created_at),
    CHECK ((state = 'INVALID') <= (NOT valid)),
    CHECK ((state = 'APPROVED') = (rule_id IS NOT NULL))
);
CREATE INDEX ai_rule_drafts_pending ON ai_rule_drafts (expires_at) WHERE state = 'PENDING_APPROVAL';
CREATE INDEX ai_rule_drafts_by_state ON ai_rule_drafts (state, created_at);
CREATE INDEX ai_rule_drafts_fingerprint ON ai_rule_drafts (content_fingerprint);

CREATE FUNCTION ai_rule_drafts_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state NOT IN ('PENDING_APPROVAL', 'INVALID') THEN RAISE EXCEPTION 'a draft is created PENDING_APPROVAL or INVALID' USING ERRCODE = '23514'; END IF;
        IF NEW.rule_id IS NOT NULL THEN RAISE EXCEPTION 'a new draft has no rule' USING ERRCODE = '23514'; END IF;
        IF NEW.valid <> (NEW.state = 'PENDING_APPROVAL') THEN RAISE EXCEPTION 'only a valid draft is pending approval' USING ERRCODE = '23514'; END IF;
        IF (NEW.definition ->> 'enabled') IS DISTINCT FROM 'false' THEN RAISE EXCEPTION 'a draft definition must be disabled' USING ERRCODE = '23514'; END IF;
        IF (NEW.definition ->> 'ruleId') !~ '^[0-9a-fA-F-]{36}$' THEN RAISE EXCEPTION 'a draft definition names its rule id' USING ERRCODE = '23514'; END IF;
        NEW.created_at := now();
        NEW.definition_hash := nexus_sha256_hex(NEW.definition::text);
        NEW.content_fingerprint := ai_definition_fingerprint(NEW.definition);
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.draft_id IS DISTINCT FROM OLD.draft_id OR NEW.definition IS DISTINCT FROM OLD.definition OR NEW.definition_hash IS DISTINCT FROM OLD.definition_hash
           OR NEW.content_fingerprint IS DISTINCT FROM OLD.content_fingerprint OR NEW.provider_id IS DISTINCT FROM OLD.provider_id
           OR NEW.model_id IS DISTINCT FROM OLD.model_id OR NEW.config_hash IS DISTINCT FROM OLD.config_hash OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
           OR NEW.rationale IS DISTINCT FROM OLD.rationale OR NEW.evidence_refs IS DISTINCT FROM OLD.evidence_refs OR NEW.validation IS DISTINCT FROM OLD.validation
           OR NEW.valid IS DISTINCT FROM OLD.valid OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
            RAISE EXCEPTION 'a draft is immutable; only its decision state may change' USING ERRCODE = '55000';
        END IF;
        IF NEW.state IS DISTINCT FROM OLD.state THEN
            IF OLD.state <> 'PENDING_APPROVAL' OR NEW.state NOT IN ('APPROVED', 'REJECTED', 'EXPIRED', 'SUPERSEDED') THEN
                RAISE EXCEPTION 'draft state % cannot become %', OLD.state, NEW.state USING ERRCODE = '55000';
            END IF;
            IF NOT EXISTS (SELECT 1 FROM ai_rule_draft_decisions x WHERE x.draft_id = NEW.draft_id AND x.decision = NEW.state) THEN
                RAISE EXCEPTION 'a draft decision state needs its recorded decision' USING ERRCODE = '55000';
            END IF;
        ELSIF NEW.rule_id IS DISTINCT FROM OLD.rule_id THEN
            RAISE EXCEPTION 'a draft rule link is set only with the approval' USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    ELSE
        IF OLD.state NOT IN ('INVALID', 'REJECTED', 'EXPIRED', 'SUPERSEDED') THEN
            RAISE EXCEPTION 'a % draft is not deletable', OLD.state USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;
END
$$;

CREATE TABLE ai_rule_draft_decisions (
    decision_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    decision_order bigserial NOT NULL UNIQUE,
    draft_id uuid NOT NULL UNIQUE,
    decision varchar(12) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED', 'EXPIRED', 'SUPERSEDED')),
    draft_hash char(64) NOT NULL,
    content_fingerprint char(64) NOT NULL,
    rule_id uuid,
    rule_version_id uuid,
    rule_version_hash char(64),
    reason varchar(500),
    evidence_refs jsonb NOT NULL,
    provider_id varchar(64) NOT NULL,
    model_id varchar(128) NOT NULL,
    config_hash varchar(64) NOT NULL,
    decided_at timestamptz NOT NULL DEFAULT now(),
    actor_kind varchar(16) NOT NULL CHECK (actor_kind IN ('LOCAL_APPROVER', 'SYSTEM')),
    db_session_user name NOT NULL,
    db_current_user name NOT NULL,
    client_addr inet,
    backend_pid integer NOT NULL,
    os_user_claimed varchar(128),
    CHECK ((decision = 'APPROVED') = (rule_id IS NOT NULL AND rule_version_id IS NOT NULL AND rule_version_hash IS NOT NULL)),
    CHECK ((decision IN ('EXPIRED', 'SUPERSEDED')) = (actor_kind = 'SYSTEM')),
    FOREIGN KEY (rule_id, rule_version_id) REFERENCES rule_versions (rule_id, version_id) ON DELETE RESTRICT
);
CREATE INDEX ai_rule_draft_decisions_version ON ai_rule_draft_decisions (rule_version_id) WHERE decision = 'APPROVED';
CREATE INDEX ai_rule_draft_decisions_fingerprint ON ai_rule_draft_decisions (content_fingerprint);

CREATE FUNCTION ai_rule_draft_decisions_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
        IF NOT EXISTS (SELECT 1 FROM rule_versions v WHERE v.version_id = NEW.rule_version_id AND v.rule_id = NEW.rule_id AND v.provenance = 'AI_DRAFT'
                          AND nexus_sha256_hex(v.definition::text) = NEW.rule_version_hash AND v.definition = d.definition) THEN
            RAISE EXCEPTION 'approval must bind the exact AI_DRAFT version created from this draft' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ai_rule_drafts_guard BEFORE INSERT OR UPDATE OR DELETE ON ai_rule_drafts FOR EACH ROW EXECUTE FUNCTION ai_rule_drafts_guard();
CREATE TRIGGER ai_rule_draft_decisions_guard BEFORE INSERT ON ai_rule_draft_decisions FOR EACH ROW EXECUTE FUNCTION ai_rule_draft_decisions_guard();
CREATE TRIGGER append_only_ai_rule_draft_decisions BEFORE UPDATE OR DELETE ON ai_rule_draft_decisions FOR EACH ROW EXECUTE FUNCTION reject_rule_history_mutation();
-- Like the other history tables, TRUNCATE is not intercepted (existing maintenance and test setup truncate the rule tables with CASCADE). Losing approval rows
-- fails closed: the activation guard then refuses every AI version that lacks one.

-- Approve or reject ONE draft, atomically. Runs with the caller's privileges: the decision trigger refuses anyone who is not a local approver.
-- Approval creates the inactive AI_DRAFT rule version bound to this draft; it never activates anything.
CREATE FUNCTION ai_draft_decide(p_draft uuid, p_expected_hash text, p_decision text, p_reason text, p_os_user text) RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE
    d ai_rule_drafts%ROWTYPE;
    v uuid := gen_random_uuid();
    did uuid := gen_random_uuid();
    def jsonb;
BEGIN
    IF p_decision NOT IN ('APPROVED', 'REJECTED') THEN RAISE EXCEPTION 'decision must be APPROVED or REJECTED' USING ERRCODE = '22023'; END IF;
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

-- Expire drafts whose window has passed (each gets an EXPIRED decision, so the audit trail keeps what was never approved). Safe to run concurrently.
CREATE FUNCTION ai_draft_expire_due() RETURNS integer LANGUAGE plpgsql AS $$
DECLARE d ai_rule_drafts%ROWTYPE; n integer := 0;
BEGIN
    FOR d IN SELECT * FROM ai_rule_drafts WHERE state = 'PENDING_APPROVAL' AND expires_at <= now() ORDER BY expires_at FOR UPDATE SKIP LOCKED LOOP
        INSERT INTO ai_rule_draft_decisions (draft_id, decision, draft_hash, content_fingerprint, reason, evidence_refs, provider_id, model_id, config_hash,
                                              actor_kind, db_session_user, db_current_user, backend_pid)
        VALUES (d.draft_id, 'EXPIRED', d.definition_hash, d.content_fingerprint, 'approval window elapsed', d.evidence_refs, d.provider_id, d.model_id, d.config_hash,
                'SYSTEM', session_user, current_user, pg_backend_pid());
        UPDATE ai_rule_drafts SET state = 'EXPIRED' WHERE draft_id = d.draft_id;
        n := n + 1;
    END LOOP;
    RETURN n;
END
$$;

-- A newer valid draft for the same request supersedes older pending ones: they can no longer be approved.
CREATE FUNCTION ai_draft_supersede(p_new uuid) RETURNS integer LANGUAGE plpgsql AS $$
DECLARE n ai_rule_drafts%ROWTYPE; d ai_rule_drafts%ROWTYPE; c integer := 0;
BEGIN
    SELECT * INTO n FROM ai_rule_drafts WHERE draft_id = p_new;
    IF NOT FOUND OR n.state <> 'PENDING_APPROVAL' THEN RETURN 0; END IF;
    FOR d IN SELECT * FROM ai_rule_drafts WHERE request_digest = n.request_digest AND state = 'PENDING_APPROVAL' AND created_at < n.created_at AND draft_id <> p_new
             ORDER BY created_at FOR UPDATE SKIP LOCKED LOOP
        INSERT INTO ai_rule_draft_decisions (draft_id, decision, draft_hash, content_fingerprint, reason, evidence_refs, provider_id, model_id, config_hash,
                                              actor_kind, db_session_user, db_current_user, backend_pid)
        VALUES (d.draft_id, 'SUPERSEDED', d.definition_hash, d.content_fingerprint, 'superseded by ' || p_new::text, d.evidence_refs, d.provider_id, d.model_id, d.config_hash,
                'SYSTEM', session_user, current_user, pg_backend_pid());
        UPDATE ai_rule_drafts SET state = 'SUPERSEDED' WHERE draft_id = d.draft_id;
        c := c + 1;
    END LOOP;
    RETURN c;
END
$$;

-- Retention: remove the BODY of unapproved drafts older than the window. Approved drafts and every decision record are never removed.
CREATE FUNCTION ai_draft_purge(p_retention interval) RETURNS integer LANGUAGE plpgsql AS $$
DECLARE n integer;
BEGIN
    IF p_retention < interval '1 day' THEN RAISE EXCEPTION 'draft retention is at least one day' USING ERRCODE = '22023'; END IF;
    DELETE FROM ai_rule_drafts WHERE state IN ('INVALID', 'REJECTED', 'EXPIRED', 'SUPERSEDED') AND created_at < now() - p_retention;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END
$$;

-- THE GUARD. A version may become a rule's active version only if it is not an AI draft, or an approval bound to its exact content exists.
-- A verbatim copy of an unapproved draft (same content under another rule id, so another provenance) is refused the same way.
CREATE FUNCTION rules_ai_activation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v rule_versions%ROWTYPE; fp text; approved boolean; unapproved_copy boolean;
BEGIN
    IF NEW.active_version_id IS NULL THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' AND NEW.active_version_id IS NOT DISTINCT FROM OLD.active_version_id THEN RETURN NEW; END IF;
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
    RETURN NEW;
END
$$;
CREATE TRIGGER rules_ai_activation_guard BEFORE INSERT OR UPDATE OF active_version_id ON rules FOR EACH ROW EXECUTE FUNCTION rules_ai_activation_guard();

-- Recreating `rule_versions` content under the AI provenance label from an ordinary session cannot forge anything: approvals are written only by approvers
-- (decision trigger), and the label is part of an immutable version row.
