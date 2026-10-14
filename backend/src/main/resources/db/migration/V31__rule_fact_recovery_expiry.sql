-- V31: a committed trigger fact whose delayed recovery is no longer eligible is recorded as EXPIRED (M-A).
-- Forward-only: V1-V30 are untouched. EXPIRED is terminal and distinct from ROUTED (it was never evaluated for rule matches) and from
-- QUARANTINED (it did not fail). Writers: RuleTriggerRouter via RuleFactDisposition. Purge: RuleRetentionService, like ROUTED facts.

ALTER TABLE rule_trigger_facts DROP CONSTRAINT rule_fact_routing_status;
ALTER TABLE rule_trigger_facts ADD CONSTRAINT rule_fact_routing_status
    CHECK (routing_status IN ('PENDING','ROUTED','FAILED','QUARANTINED','EXPIRED'));

DROP INDEX rule_fact_purge;
CREATE INDEX rule_fact_purge ON rule_trigger_facts(recorded_at) WHERE routing_status IN ('ROUTED','QUARANTINED','EXPIRED');

-- The rule versions and epochs that were ACTIVE when the fact was recorded: {ruleId: {"v": versionId, "e": epoch}}. Recovery evaluates a
-- fact only against exactly those authorities, so a version activated, resumed or replaced after the fact never acts on it.
-- NULL (facts recorded before this migration) is treated as "not recorded" and such a fact cannot be matched by recovery.
ALTER TABLE rule_trigger_facts ADD COLUMN rule_authority jsonb;
