-- V39__command_state_history.sql
--
-- Phase 4 P4.2: durable, append-only, ordered story of one command (contract R06) and the conservative
-- DELIVERY_UNKNOWN reconciliation of commands accepted before the outbox existed (contract R16).
-- Forward-only: V1 to V38 are not modified. This migration publishes nothing, creates no outbox row and
-- replays nothing; the legacy backfill only appends a history FACT.
--
-- Table design:
--   * (command_id, sequence) is the primary key: the sequence is unique per command and assigned gaplessly by the
--     writer (CommandStateHistory) under a per-command advisory lock; the primary key is the backstop.
--   * command_id references action_history(request_id) ON DELETE CASCADE. This is deliberate and is what keeps
--     history from ever BLOCKING the retention of action_history (ledger P4-139): when retention removes an old
--     action_history row its history goes with it, in the same statement. Contrast with command_outbox, whose
--     foreign key is RESTRICT because an unresolved obligation must never be destroyed.
--   * UPDATE is always rejected. DELETE is rejected unless the parent action_history row is already gone (the
--     cascade), so history cannot be edited or removed by any other path. TRUNCATE is outside row triggers
--     (privileged operation), as for command_outbox.
--   * No device foreign key: removing a device is already refused by V38 while delivery evidence exists, and
--     history must not be able to block or be orphaned by device removal.

CREATE TABLE command_state_history (
    command_id     UUID         NOT NULL REFERENCES action_history(request_id) ON DELETE CASCADE,
    sequence       INTEGER      NOT NULL,
    fact_type      VARCHAR(32)  NOT NULL,
    from_state     VARCHAR(16),
    to_state       VARCHAR(16),
    attempt        INTEGER      NOT NULL DEFAULT 0,
    reason_code    VARCHAR(64),
    source         VARCHAR(16)  NOT NULL,
    correlation_id VARCHAR(255),
    occurred_at    TIMESTAMPTZ  NOT NULL,
    detail         VARCHAR(255),

    CONSTRAINT pk_command_state_history PRIMARY KEY (command_id, sequence),
    CONSTRAINT ck_csh_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_csh_attempt CHECK (attempt >= 0 AND attempt <= 5),
    CONSTRAINT ck_csh_fact_type CHECK (fact_type IN (
        'ACCEPTED', 'QUEUED', 'ATTEMPT_STARTED', 'ATTEMPT_FAILED_TRANSIENT', 'ATTEMPT_UNCERTAIN', 'PUBLISHED',
        'DEAD_LETTERED', 'DELIVERY_UNKNOWN', 'ACK_ACCEPTED', 'ACK_REJECTED', 'ACK_CONFLICT_IGNORED',
        'ACK_TIMED_OUT', 'ACK_AFTER_TIMEOUT', 'ACK_AFTER_TERMINAL')),
    CONSTRAINT ck_csh_states CHECK (
        (from_state IS NULL OR from_state IN ('QUEUED', 'IN_FLIGHT', 'RETRY_WAIT', 'PUBLISHED', 'DEAD_LETTER', 'DELIVERY_UNKNOWN'))
        AND (to_state IS NULL OR to_state IN ('QUEUED', 'IN_FLIGHT', 'RETRY_WAIT', 'PUBLISHED', 'DEAD_LETTER', 'DELIVERY_UNKNOWN'))),
    CONSTRAINT ck_csh_source CHECK (source IN (
        'ACCEPTANCE', 'DISPATCHER', 'ACK_HANDLER', 'TIMEOUT_SCANNER', 'RECONCILER', 'MIGRATION')),
    CONSTRAINT ck_csh_reason_format CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z0-9_]{1,64}$')
);

CREATE INDEX idx_command_state_history_time ON command_state_history (occurred_at);

CREATE FUNCTION command_state_history_guard_update() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'command_state_history is append-only (command %, sequence %)', OLD.command_id, OLD.sequence
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_command_state_history_no_update BEFORE UPDATE ON command_state_history
    FOR EACH ROW EXECUTE FUNCTION command_state_history_guard_update();

CREATE FUNCTION command_state_history_guard_delete() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM action_history WHERE request_id = OLD.command_id) THEN
        RAISE EXCEPTION 'command_state_history is append-only; rows leave only with their action_history row (command %)', OLD.command_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_command_state_history_guard_delete BEFORE DELETE ON command_state_history
    FOR EACH ROW EXECUTE FUNCTION command_state_history_guard_delete();

-- Legacy reconciliation (R16, decision D3). A command accepted before the outbox existed and still ACCEPTED_FOR_DISPATCH
-- has an unknown delivery outcome: the legacy path publishes after commit, so a crash between the two strands the row.
-- The fact is appended conservatively:
--   * only SEND_COMMAND rows still ACCEPTED_FOR_DISPATCH with NO recorded publication and NO device ACK (a row that was
--     published, failed or acknowledged already says what happened and is not modified);
--   * only rows older than 5 minutes. The legacy dispatch publishes within milliseconds (its blocking publish is bounded
--     by about 15 seconds), so a recently accepted or concurrently publishing row is never classified; such a row stays
--     untouched here and is the business of the runtime reconciler (P4.4), which uses the same grace;
--   * never a row that has an outbox row (nothing has one at this point, but the guard is part of the contract);
--   * idempotent: a row that already has a history fact is skipped.
-- No outbox row is created, nothing is published, and the ActionHistory row is not changed.
INSERT INTO command_state_history (command_id, sequence, fact_type, from_state, to_state, attempt, reason_code, source,
                                   correlation_id, occurred_at, detail)
SELECT a.request_id, 1, 'DELIVERY_UNKNOWN', NULL, 'DELIVERY_UNKNOWN', 0, 'LEGACY_DELIVERY_UNRECORDED', 'MIGRATION',
       NULL, now(), 'Accepted before the command outbox existed; delivery was never recorded and is never retried automatically'
FROM action_history a
WHERE a.action_type = 'SEND_COMMAND'
  AND a.status = 'ACCEPTED_FOR_DISPATCH'
  AND a.published_at IS NULL
  AND a.ack_status IS NULL
  AND a."timestamp" < now() - INTERVAL '5 minutes'
  AND NOT EXISTS (SELECT 1 FROM command_outbox o WHERE o.command_id = a.request_id)
  AND NOT EXISTS (SELECT 1 FROM command_state_history h WHERE h.command_id = a.request_id);
