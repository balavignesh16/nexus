# Phase 6.3 operator guide: AI rule drafts and local approval

Status: P6.3 (2026-10-18). Read `intelligence-contract.md` sections 7 and 8 first. This guide says what the approval boundary guarantees, what it does not, and how to run it.

## 1. What the model can and cannot do

A model may **propose** a rule. `POST /api/v1/intelligence/drafts {"goal": "..."}` asks the configured provider for one strict JSON object. The platform then:

1. converts it with a strict mapper (unknown or wrong-typed fields refuse the whole proposal),
2. requires at least one `device:<id>` evidence reference, each of which must be a device that was shown to the model,
3. applies `DraftPolicy` (bounded size, a cooldown of 30 s to 24 h, no `WEBHOOK` egress, a device may be commanded only if it is cited),
4. runs the Phase 3 `RuleCapabilityValidator` exactly as for a human draft,
5. adds warnings for overlap with active rules, and
6. stores the result in `ai_rule_drafts` with provider, model, configuration hash, prompt template version, request digest, evidence references, the validation verdict and the creation time.

A valid proposal is `PENDING_APPROVAL`; an invalid one is stored `INVALID` for audit and can never be approved. Nothing is written to `rules`, `rule_versions`, the outbox or the broker. The model has no path to approval: `IntelligenceArchitectureTest` fails the build if an AI package imports the approval code, a rule writer, an executor or the broker, or if any HTTP route mentions a decision.

## 2. Local approval (the only way to record a decision)

`com.nexus.rule.approval.DraftApprovalCli`, run by an operator on the database host. It reads its own database login from `NEXUS_APPROVER_DB_URL`, `NEXUS_APPROVER_DB_USER` and `NEXUS_APPROVER_DB_PASSWORD` (never arguments; unknown options are usage errors).

```
list
show    --draft <uuid>
approve --draft <uuid> --hash <definition hash printed by show> --reason <text>
reject  --draft <uuid> --hash <definition hash printed by show> --reason <text>
```

Approval creates an **inactive** rule version (`provenance = AI_DRAFT`) whose content is exactly the reviewed draft. It does not activate it. Activation is the existing lifecycle operation (`POST /api/v1/rules/{id}/lifecycle/activate`, `POST /api/v1/rules/{id}/enable`, or the CLI-independent service call), which validates the version again against the current device contracts; the database then accepts the change only because an approval of that exact version exists.

### Provisioning an approver (once, by the database administrator)

```sql
-- nexus_approver is created NOLOGIN by migration V42 when the migrating account may; otherwise: CREATE ROLE nexus_approver NOLOGIN;
CREATE ROLE alice_approver LOGIN PASSWORD '<secret>';
GRANT nexus_approver TO alice_approver;          -- DIRECT membership is what counts
GRANT SELECT, INSERT, UPDATE ON ai_rule_drafts, rules, rule_versions, rule_version_workflow TO alice_approver;
GRANT SELECT, INSERT ON ai_rule_draft_decisions TO alice_approver;
GRANT USAGE ON SEQUENCE ai_rule_draft_decisions_decision_order_seq TO alice_approver;
```

The application's own login must **not** be a member of `nexus_approver`. Superuser status alone does not make a login an approver.

## 3. What the database enforces (V42)

| Property | Mechanism |
|---|---|
| Only an approver can record APPROVED or REJECTED | `BEFORE INSERT` trigger on `ai_rule_draft_decisions` checks that `session_user` is a direct member of `nexus_approver` (`pg_auth_members`), so it holds for the CLI and for hand-written SQL alike |
| Actor provenance is verified, not claimed | the trigger overwrites `db_session_user`, `db_current_user`, `client_addr`, `backend_pid` and the timestamp from the connection; the operating-system account is stored as `os_user_claimed` and is explicitly a claim |
| Decisions are append-only and one per draft | update and delete are refused; `UNIQUE (draft_id)` |
| Approval binds one exact version | the decision stores the SHA-256 of the version's definition; the trigger requires the version to be the `AI_DRAFT` version equal to the reviewed draft |
| A stale review is refused | `approve` and `reject` take the definition hash that `show` printed; a different hash fails with 40001 |
| A draft cannot change | content, hash, provenance, evidence and validation are immutable; only the decision state moves, only from `PENDING_APPROVAL`, only with its recorded decision |
| No AI version becomes active without its approval | `BEFORE INSERT OR UPDATE OF active_version_id` trigger on `rules` refuses a version whose provenance is `AI_DRAFT`, or whose content matches an unapproved draft or a rejected one, unless an approval of that exact version exists. It applies to every writer of `active_version_id`: lifecycle activate and resume, legacy save, reconciliation, and direct SQL |
| One outcome under concurrency | the draft row is locked `FOR UPDATE`; eight concurrent decisions leave exactly one decision, one rule and one version |
| Retention | drafts expire after `nexus.ai.draft.retention-days` (30, range 1 to 365) with a system `EXPIRED` decision; unapproved drafts older than that are removed by `ai_draft_purge`; **decisions, their evidence references and approved drafts are never removed** |

Editing an approved draft is an ordinary rule edit: it creates a new human-authored version (`DRAFT_EDIT`). The old approval does not carry over, and the edit follows the usual optimistic-concurrency checks.

## 4. Legacy API inspection (result)

Every statement that can set `rules.active_version_id` is in `RuleLifecycleService` (`legacySave`, `transition(activate|resume)`, `reconcile`). The REST routes that reach them are `POST /rules`, `PUT /rules/{id}`, `POST /rules/{id}/enable`, `POST /rules/{id}/lifecycle/{operation}` and the draft routes; none writes `active_version_id` directly and the JPA `RuleEntity` does not map it. Findings:

* **Laundering by re-posting a draft's content** to `POST /rules` with `enabled=true` returns 409 from the guard and leaves no rule (`DraftWorkflowIntegrationTest.p6_050_copying...`). A hand-written rule with different content is an ordinary human rule and is not affected.
* **Reusing the AI version through `PUT /rules/{id}`** with an identical definition (the lifecycle reuses the working version) is refused by the guard unless the version is approved.
* **Residual route**: a person who is free to call the API can still write a *different* rule by hand. That is the existing, unauthenticated behaviour for every rule and is not an AI path.

## 5. Limitations that remain (stated plainly)

1. **NEXUS has no authenticated user model.** Every HTTP endpoint, including rule creation, update and activation, is open to anyone who can reach the service. A CLI cannot change that. This work guarantees that **an AI proposal cannot become an active rule without a recorded, version-bound approval made by a database login that is an approver**. It does **not** guarantee that every active rule was approved by a human, and it does not authenticate people: the approver is a database role, not a person, unless the operator gives each person their own login.
2. **Approval does not authorise activation by itself, and activation is not privileged.** After approval, anyone who can reach the lifecycle API can activate the approved version (after validation). Making activation privileged needs the identity phase.
3. **The guard is not a defence against a database administrator.** A superuser can disable triggers, grant themselves `nexus_approver`, or edit history tables. `TRUNCATE` is not intercepted (as for the other rule history tables); losing approval rows fails closed because the guard then refuses every AI version.
4. **The operating-system account is a claim.** It is stored for the audit trail only.
5. **Verbatim-copy detection is content based**: a rule that differs in any field (including metadata) is a different, human-authored rule.
6. **Conflict analysis (P6-043, P6.6).** While a draft is created, `DraftConflictAnalyzer` compares each command target (device, capability, relay channel: the arbiter's own target key) with the ACTIVE version of every active rule and with the persisted exclusion groups, inside one read-only REPEATABLE READ snapshot. The stored result (`validation.conflictAnalysis`, shown by `show` as `conflicts`) is CLEAR, CONFLICTS, UNRESOLVED or NOT_APPLICABLE (no command). A finding names the rule, its id, the group, the member's rank and the other members (`MUTUAL_EXCLUSION_TARGET_GOVERNED`), or the arbitration relationship at the draft's priority (`TARGET_CONFLICT_EQUAL_PRIORITY`, `TARGET_DRAFT_WOULD_OVERRIDE`, `TARGET_DRAFT_WOULD_BE_SUPPRESSED`; an identical command is information). Paused, draft, validated and archived rules and superseded versions never produce a conflict; paused or unreconciled rules are listed as ignored. When the comparison cannot be made (unreadable rules or groups, an uncomputable target, more than 2,000 active rules) the status is UNRESOLVED and the draft is stored INVALID with `CONFLICT_ANALYSIS_UNRESOLVED`: it is never declared safe. `approve` refuses unless the status is CLEAR or NOT_APPLICABLE or the approver passes `--acknowledge-conflicts yes` (recorded in the decision reason). The analysis is compared again when it matters: `show` prints `freshness CURRENT` or `STALE`; `approve`, the first activation of the approved version and every later resume of that rule recompute a fingerprint of the rules and exclusion groups the draft was checked against, under a lock that stops relevant changes from committing between the check and the transition, and refuse with `stale conflict analysis` (SQLSTATE P0A01) when it differs. `--acknowledge-conflicts yes` acknowledges a conflict that was found; it never bypasses a stale analysis. After a stale refusal create the draft again. While an approval or an AI activation is in flight, changes to rules and exclusion groups wait for it to commit (a short delay). The analysis only reads: it never activates, approves or executes anything. The older `OVERLAPS_ACTIVE_RULE` warning is kept.
7. The model is an optional provider; no real provider was called in testing (see P6.2 limits).
