# Phase 4 reliable event and action pipeline contract

Date: 2026-10-17 (P4.3 amendments: R33, plus notes in R10 and R12; P4.4 implementation notes: R34; P4.5 implementation notes: R35).

Status: **P4.0 CONTRACT APPROVED AND FINALIZED (open points A1 to A9 resolved below). P4.1 (V37 command_outbox, configuration, retention protection) and its safety corrections (V38 device-deletion guard and database backstops, per-category retention with desired-state and outbox guards, forensic findings F1, F2 and F7) are implemented, tested and committed (1936c2b). P4.2 (V39 command_state_history and the conservative legacy backfill, atomic acceptance, the bounded dispatcher with lease, fencing and terminal-only failure handling, the flag-gated coordinator, queued-versus-published status separation, and the R31 safety requirements) is implemented, tested and committed (e91f2f7), together with the corrections of its forensic review (one validated authoritative flag and an unconditional dispatcher so rollback drains; the per-device hold after an uncertain outcome, R32; conservative NOT_SENT classification; no definitive failure signal for an uncertain outcome; database time as the single clock of the outbox lifecycle; real ACK-handler tests). The outbox feature flag still defaults OFF and nothing enables it. P4.3 to P4.8 are not started.**

P4.3 (evidence-gated retries, persisted jittered backoff, RETRY_EXHAUSTED and EXPIRED dead letters, crash-safe failure signals) is implemented and tested; the approved safe retry policy and everything it changes are recorded in R33. P4.4 (durable ACK facts, the persisted ACK timeout worker, runtime legacy reconciliation) is implemented and tested, committed together with its own notes in R34, and needs no migration. P4.5 to P4.8 are not started.

Baseline: branch `main`, HEAD `1b3055826d196a7149aa102d5e94d351d5ee3a51` (P3.6), latest migration V36. Phase 3 behaviour is preserved.

Document ownership:

- This contract owns semantics, invariants, limits and scope boundaries.
- [Implementation ticket](p4-implementation-ticket.md) owns slices, dependencies, source ownership, rollout and validation gates. It references clauses and does not redefine them.
- [Acceptance ledger](p4-acceptance-ledger.md) owns scenario IDs, required evidence and statuses. A planned test is not proof.
- Current committed source and schemas remain the authority for what exists today. ROADMAP Phase 4 provides the goal and exit criteria; it is not edited here.

Approved decisions that bind this contract (user approval for P4.0):

| ID | Decision |
|---|---|
| D1 | Add a durable command state history. |
| D2 | Preserve the immutable commandId, timestamp and payload across retries; use a bounded retry window compatible with firmware freshness and clock uncertainty; never refresh timestamps to bypass expiry. |
| D3 | Pre-V37 commands without outbox provenance remain DELIVERY_UNKNOWN and are never automatically resent. |
| D4 | Deduplicate telemetry only with an explicit messageId. |
| D5 | The general domain-event outbox is deferred unless inspection identifies a specific unrecovered requirement. |
| D6 | `firmware/**` is not modified; backend and simulator protocol extensions only. |
| D7 | The outbox feature flag defaults OFF until migration, safety and recovery validation passes; the enablement procedure is documented (R22). |

Finalized decisions on the open points of P4.0 (approved 2026-10-15; the clauses below already reflect them):

| ID | Decision | Where applied |
|---|---|---|
| A1 | An invalid `messageId` is rejected as invalid input (HTTP 400; MQTT acknowledge and drop). There is no silent deduplication bypass. | R17 |
| A2 | Provisional default delivery window 15 seconds, maximum 20 seconds. The ceiling is derived from the actual firmware 30 second freshness check minus an itemised uncertainty budget (timestamp precision, clock skew, network delay, safety). | R11 |
| A3 | Bounded retry and lease defaults are selected against the existing infrastructure (single shared MQTT client, 15 second blocking-publish wait, scanner cadences) and are documented with trade-offs and test criteria. | R12 |
| A4 | Safety-sensitive command ordering is preserved. Strict per-device FIFO is NOT claimed unless implemented and proven under concurrent workers; V37 itself only guarantees at most one IN_FLIGHT command per device. | R09 |
| A5 | A duplicate HTTP telemetry submission returns an explicit duplicate/no-op response and causes no second Twin transition. | R17 |
| A6 | ActionHistory, current desired Twin state, retention and foreign keys were inspected before V37; the ON DELETE policy is explicit and retention cannot destroy an unresolved obligation. | R04, R19 |
| A7 | The existing REAL-mode dispatch restriction in `CommandExecutor.prepare` is preserved. | R08, R26 |
| A8 | Existing simulator behaviour (including its 300 second stale-window default) is preserved until a separately approved parity correction; tests model the actual 30 second firmware freshness semantics in a test helper. | R25 |
| A9 | A persisted ACK timeout is distinct from Twin verification and from physical convergence; it never changes a verification state. | R14 |

<a id="r01"></a>
## R01. Scope, authority and non-goals

In scope: a transactional outbox for outgoing commands; a durable command state history; bounded retry, expiry and dead-letter handling; persisted ACK timeout state; crash recovery; reconciliation of legacy commands; telemetry deduplication by explicit messageId; backpressure; traceability; fault-injection acceptance.

Not in scope and not implemented by Phase 4:

- Authentication, authorisation, MQTT ACLs, TLS, per-device credentials or a manual requeue capability (Phase 7; see R21).
- Protocol adapters (Phase 5) and any firmware change (D6).
- Exactly-once physical actuation, device-side persistent command deduplication or device-side queues (R03, R26).
- Physical verification claims, hidden unit conversion, historical replay, manual approval, cron schedules.
- A general domain-event outbox (D5, R28), generic MQTT inbox beyond telemetry messageId, or a change to the Phase 3 rule decision pipeline.

If a clause cannot be met inside the approved transaction and locking boundaries, implementation stops and reports a blocker. A gate is never weakened, and Phase 5 to 9 work is not pulled forward to conceal an incompatibility.

<a id="r02"></a>
## R02. Vocabulary and fact separation

Six facts are distinct and are stored in distinct fields. No fact implies a later one.

| Fact | Meaning | Proven by | Does not prove |
|---|---|---|---|
| Accepted | NEXUS durably accepted a desired intent under all guards | Acceptance transaction commit (ActionHistory ACCEPTED_FOR_DISPATCH plus desired state) | That anything was sent |
| Queued | A delivery obligation exists | Outbox row in state QUEUED, RETRY_WAIT or IN_FLIGHT | That a publish was attempted |
| Published | The MQTT client completed a QoS 1 publish (broker PUBACK) | Outbox state PUBLISHED; ActionHistory SUCCESS with published_at | That the device received or applied the command |
| Acknowledged | The device software reported a status | ACK fields on ActionHistory | That the actuator moved |
| Converged | A qualified readback matched the desired value | Twin verification state (P2.4), not a delivery state | Physical actuation by itself |
| Physically actuated | The real-world effect occurred | Independent physical evidence only | Never recorded by software |

`ExecutionStatus.SUCCESS` keeps its existing meaning (publish success). Roadmap lifecycle names map as follows: CREATED = accepted; QUEUED = outbox QUEUED/RETRY_WAIT/IN_FLIGHT; DISPATCHED = outbox PUBLISHED; ACKNOWLEDGED/REJECTED = ACK status; TIMED_OUT = persisted ACK timeout; FAILED = DEAD_LETTER or existing publication failure. CONVERGED remains Twin verification.

<a id="r03"></a>
## R03. Delivery guarantee and explicitly unsupported guarantees

Supported, once the outbox is enabled (R22):

- An accepted command and its delivery obligation are **durably persisted together**, in one transaction.
- Delivery attempts are **bounded** (attempt count and a delivery window) and **may be repeated**.
- A backend restart **must not silently erase** a pending delivery obligation; after a restart it is delivered within its window, expired visibly, or marked uncertain.

Not guaranteed:

- **Successful publication.** If the broker stays unavailable, or the command expires before an attempt succeeds, the command ends DEAD_LETTER and is never published.
- Delivery to, or application by, the device.

MQTT QoS 1 is at-least-once, and a transactional outbox with retries is at-least-once as well. Therefore:

1. **Duplicate publications can occur.** A broker may redeliver; an attempt whose result is uncertain may be retried and both attempts may have reached the broker. Duplicates carry identical bytes (R11).
2. **Exactly-once physical actuation is not promised and not claimed.** It would require persistent device-side deduplication keyed by commandId, which the firmware does not have and which D6 forbids changing.
3. Exactly-once is also not claimed for ACK processing or broker delivery; only first-ACK-wins state handling is guaranteed (R13).
4. The current command shapes set absolute targets (an LED state, a relay channel state, a servo angle), so a duplicate delivery re-applies the same target. Introducing a command with non-idempotent semantics (a toggle, a pulse, an increment) is prohibited until device-side deduplication exists.
5. No document, API description or UI string may state or imply exactly-once physical actuation (ledger scenario P4-123).

<a id="r04"></a>
## R04. Persisted model and ownership

All new tables arrive through forward-only Flyway migrations V37 and later (R23). Migrations V1 to V36 are never edited.

**`command_outbox`** (one row per accepted command in outbox mode; owner: the acceptance transaction creates it, the dispatcher updates it):

| Column | Rule |
|---|---|
| `command_id` | UUID, primary key, equal to `action_history.request_id`; foreign key **ON DELETE RESTRICT** (see the deletion policy below) |
| `device_id` | UUID, not null; foreign key to `devices` ON DELETE **RESTRICT** (V38; V37 created it as CASCADE, which silently erased obligations, see R30) |
| `topic` | text, not null |
| `payload_text` | text, not null; the exact JSON published on every attempt (R11) |
| `payload_sha256` | char(64), not null |
| `payload_epoch_seconds` | bigint, not null; the `timestamp` field inside the payload |
| `state` | QUEUED, IN_FLIGHT, RETRY_WAIT, PUBLISHED, DEAD_LETTER |
| `attempt_count`, `max_attempts` | integers, check `0 <= attempt_count <= max_attempts` |
| `next_attempt_at` | timestamptz, null when not waiting |
| `lease_owner`, `lease_expires_at` | set only while IN_FLIGHT |
| `expires_at` | timestamptz, not null (R11) |
| `first_publish_confirmed_at` | timestamptz, set once |
| `terminal_at`, `terminal_reason` | set only in a terminal state (DEAD_LETTER or PUBLISHED); reasons RETRY_EXHAUSTED, EXPIRED, PERMANENT_FAILURE, PAYLOAD_INTEGRITY, UNCERTAIN_EXPIRED |
| `last_error_code`, `correlation_id`, `created_at`, `updated_at` | audit and joining |

Indexes support claiming by `(next_attempt_at, created_at)` for QUEUED and RETRY_WAIT rows, per-device lookups of unresolved rows, lease and delivery expiry sweeps, and terminal-row purging. A unique partial index permits at most one IN_FLIGHT row per device.

**Implemented by V37 (P4.1)** and enforced by the database itself:

- Identity and payload integrity: the topic equals `nexus/devices/<device_id>/commands`; the payload is a JSON object whose `commandId` equals `command_id` and whose `timestamp` equals `payload_epoch_seconds`; `payload_sha256` is 64 lowercase hex characters and equals the SHA-256 of the UTF-8 payload text; the payload is at most 4096 characters.
- State domain, attempt bounds (`max_attempts` 1 to 5, `attempt_count` within it), lease coherence, `next_attempt_at` present exactly while QUEUED or RETRY_WAIT, attempt count consistent with state, and terminal coherence (a DEAD_LETTER row always records a reason from the fixed set; PUBLISHED carries the confirmation time and no reason).
- Expiry: `expires_at` is after the payload timestamp and at most 20 seconds after it.
- A BEFORE UPDATE trigger makes identity, payload, topic, expiry, `max_attempts` and `created_at` immutable, makes PUBLISHED and DEAD_LETTER rows immutable, allows only the transitions of R05, and requires `attempt_count` to increase by exactly one when a row is claimed and never otherwise.

**Relationship and deletion policy (decision A6, inspected before V37 was written):**

- `action_history.request_id` is the primary key and is already referenced by `current_desired_twin_state.command_id` with ON DELETE RESTRICT (V21). `action_history.device_id` references `devices` ON DELETE SET NULL.
- `command_outbox.command_id` references `action_history` ON DELETE **RESTRICT**: an ActionHistory row cannot take its delivery obligation with it. (An earlier draft proposed CASCADE; it is rejected because a cascade could silently delete an unresolved obligation.)
- A BEFORE DELETE trigger refuses to delete a QUEUED, IN_FLIGHT or RETRY_WAIT row while its device exists. Only resolved rows (PUBLISHED, DEAD_LETTER) can be deleted, by the retention purge of R19.
- `command_outbox.device_id` references `devices` ON DELETE **RESTRICT** (changed from CASCADE by V38 after forensic finding F1): removing a device never removes a delivery obligation or delivery record. Policy, mechanism and tests are in R30.

**`command_state_history`** (append-only; D1; owners listed in R06). **`inbound_message_dedupe`** (telemetry; R17).

`DELIVERY_UNKNOWN` is a state-history fact and an API projection for commands without outbox provenance (R16). It is not an outbox state because such commands have no outbox row.

<a id="r05"></a>
## R05. Delivery state machine

Transitions are the only permitted ones; any other write is rejected by the repository and by database constraints.

| From | To | Trigger | Transaction |
|---|---|---|---|
| none | QUEUED | Acceptance commit | T1 |
| QUEUED, RETRY_WAIT | IN_FLIGHT | Claim, only if `next_attempt_at <= now()` and `now() < expires_at` and no other command of the same device is IN_FLIGHT | T2 |
| IN_FLIGHT | PUBLISHED | Broker completion of a QoS 1 publish | T4 (fenced) |
| IN_FLIGHT | RETRY_WAIT | Transient failure, or uncertain attempt recovered, with attempts and time remaining | T4 or T5 |
| IN_FLIGHT | DEAD_LETTER | Permanent failure, attempts exhausted, expiry reached, or uncertain attempt with no time or attempts remaining | T4 or T5 |
| QUEUED, RETRY_WAIT | DEAD_LETTER | Expiry sweep (reason EXPIRED) | T5 |

PUBLISHED and DEAD_LETTER are terminal for delivery. Post-publication facts (ACK, timeout, verification references) are state-history facts and never change the outbox state. No scheduled job or restart moves a DEAD_LETTER row back to a queued state (R12, R21).

<a id="r06"></a>
## R06. Command state history (D1)

`command_state_history` (created by **V39, in slice P4.2**, not by P4.1) is append-only and owns the durable, ordered story of one command. Columns: `command_id` (foreign key to `action_history`), `sequence` (unique per command, gapless, assigned under a row lock or an equivalent atomic counter), `fact_type`, `from_state`, `to_state`, `attempt`, `reason_code`, `source`, `correlation_id`, `occurred_at` (backend time), bounded `detail`. A database trigger rejects UPDATE; the repository exposes no update. Deletion occurs only through the retention purge (R19).

Fact types: ACCEPTED, QUEUED, ATTEMPT_STARTED, ATTEMPT_FAILED_TRANSIENT, ATTEMPT_UNCERTAIN, PUBLISHED, DEAD_LETTERED, DELIVERY_UNKNOWN, ACK_ACCEPTED, ACK_REJECTED, ACK_CONFLICT_IGNORED, ACK_TIMED_OUT, ACK_AFTER_TIMEOUT, ACK_AFTER_TERMINAL. Sources: ACCEPTANCE, DISPATCHER, ACK_HANDLER, TIMEOUT_SCANNER, RECONCILER, MIGRATION. Verification outcomes remain in the Twin verification tables and are joined, not copied. `detail` never contains secrets or full command parameters.

Every outbox state change and every post-publication fact writes its history row in the same transaction as the change it describes.

**Implemented in P4.2 (V39).** The foreign key to `action_history` is ON DELETE CASCADE, deliberately: history can never block the retention of action_history (ledger P4-139) and leaves only with its action row. UPDATE is always rejected; DELETE is rejected unless the parent row is already gone (the cascade). Sequence numbers are assigned by `CommandStateHistory` under a per-command transaction-scoped advisory lock that is always the LAST lock a transaction takes, so it cannot join a cycle with the dispatcher order outbox row then action_history row; the primary key is the backstop. The writer refuses to run outside a transaction. Contrast with `command_outbox`, whose action_history foreign key is RESTRICT because an unresolved obligation must never be destroyed.

<a id="r07"></a>
## R07. Transaction boundaries and ownership

| Tx | Owner | Contents | MQTT I/O |
|---|---|---|---|
| T1 Acceptance | `CommandAcceptanceService.accept` (existing `REQUIRES_NEW`, device row lock) | Existing guards and writes plus outbox row QUEUED, history ACCEPTED and QUEUED, queue-cap check (R18) | Never |
| T2 Claim | Dispatcher | `SELECT ... FOR UPDATE SKIP LOCKED` batch; set IN_FLIGHT, lease owner and expiry, increment attempt; history ATTEMPT_STARTED. Short, committed before any I/O | Never |
| T3 Publish | Dispatcher | The MQTT publish itself | Yes, outside any database transaction |
| T4 Outcome | Dispatcher | Fenced conditional update on `(command_id, lease_owner, attempt_count)`; outbox state; history; conditional ActionHistory update using existing methods | Never |
| T5 Sweeps | Expiry and lease recovery scanners | Expire QUEUED and RETRY_WAIT rows; recover expired leases as uncertain attempts | Never |
| T6 ACK | Existing `DeviceCommandAckMessageHandler` | Existing guarded update plus ACK history rows in the same transaction | Never |
| T7 Timeout | ACK timeout scanner | ACK_TIMED_OUT history after the deadline | Never |
| T8 Telemetry | Existing telemetry service | Dedupe insert and telemetry write together (R17) | Never |

Rule-originated publication-failure facts remain committed in their own transaction after T4, exactly as in `CommandDispatchCoordinator` today; a failure to record that derived fact fails closed and is never retried by resending the command.

<a id="r08"></a>
## R08. Atomic acceptance and authorisation finality

1. With the outbox enabled, acceptance and outbox persistence are one atomic unit. Either the command is accepted and queued, or nothing is written.
2. Authorisation happens once, at acceptance, inside T1 and in the existing order: device row lock, capability and contract validation, prepare, `RuleExecutionAuthorization.check`, then the writes. Phase 3 gates (ACTIVE, version, epoch, reconciliation, target validity) are unchanged.
3. **Delivery never reauthorises.** The dispatcher does not call `RuleExecutionAuthorization`, does not re-run rule or contract validation, and does not re-evaluate connectivity. A command durably accepted keeps its P2.4 lifecycle and may finish delivery after its rule is paused, as the Phase 3 contract (C13) already states. The delivery window (R11) is the only time bound.
4. Offline or unknown device, disconnected broker client and invalid targets continue to be rejected at acceptance by `CommandExecutor.prepare` before any durable write, exactly as today. (P4.2 clarification: acceptance therefore does NOT commit while the backend client is disconnected; it performs no MQTT I/O of its own, and the earlier ledger wording of P4-004 that suggested otherwise was amended.)
5. `prepare` also refuses devices in REAL mode ("Command dispatch to REAL devices is not yet enabled"). Phase 4 does not change that and makes no claim about real-device delivery.

<a id="r09"></a>
## R09. Claim and lease semantics

- Claiming uses `FOR UPDATE SKIP LOCKED` ordered by `created_at`, limited by a batch size. Only rows with `next_attempt_at <= now()` and `now() < expires_at` are eligible.
- **Per-device ordering and single in-flight (decision A4):** at most one command per device may be IN_FLIGHT; V37 enforces that with a unique partial index and nothing more. **Strict per-device FIFO is not claimed.** Ordering is a safety requirement (a later accepted desired state must not be overtaken by an earlier command), and the dispatcher of P4.2 must claim an older unresolved command of a device before a newer one. That ordering may be stated as a guarantee only after it is implemented and proven under concurrent workers (ledger scenario P4-018); until then it is a requirement, not a claim. Different devices proceed independently.
- A lease records `lease_owner` (instance and worker identity) and `lease_expires_at`. Lease duration must exceed the attempt timeout by a validated margin (R12).
- **Fencing:** every T4 write is conditional on `lease_owner` and `attempt_count`. A worker whose lease expired or was reassigned writes nothing; zero affected rows means abandon.
- **Clock:** claim eligibility, lease expiry and delivery expiry compare against the database clock (`now()`), so instances with skewed application clocks agree. The application clock supplies only the device-facing payload timestamp (R11).
- Lease recovery treats the attempt as **uncertain** (R10): the publish may have reached the broker. Recovery appends ATTEMPT_UNCERTAIN and applies R12.
- After commit, acceptance nudges the local dispatcher to preserve low latency. The nudge only triggers the normal claim query and cannot bypass it.

**Implemented in P4.2.** `OutboxDeliveryOperations` selects candidates (QUEUED, due, `now() < expires_at`, nothing older unresolved for the device: an older QUEUED or RETRY_WAIT or any IN_FLIGHT command of the device blocks) and claims each in its own short transaction with `FOR UPDATE SKIP LOCKED`, re-checking every condition in the UPDATE itself. Order is by `created_at`; acceptance stamps `created_at` strictly after every earlier outbox row of the device (the device row lock serialises acceptances), so equal timestamps cannot invert claim order. A claim that loses the single-in-flight unique index race (SQLSTATE 23505) is a normal empty result. The claim lease uses the database clock; an outcome is applied only if the row is still IN_FLIGHT for the same lease owner and attempt (zero rows means abandon, nothing is written); a lease owner is unique per claim. Workers are bounded (`nexus.command.outbox.workers`, 1 to 16, default 4) and a claim is never made without a free worker, so a lease never waits for a thread.

**What is claimed about ordering.** Per-device CLAIM order is proven (scenario P4-018) under concurrent workers, two instances, stalled leases and failures: a newer command is never claimed or sent while an older one of the same device is unresolved. This is the order in which this backend sends commands. It is NOT a claim about the order in which a device receives or acknowledges them (broker redelivery or duplicates can reorder at the device) and it is re-verified with retries in P4.3.

<a id="r10"></a>
## R10. Publication outcomes, uncertainty and duplicates

An attempt has exactly one of three outcomes:

| Outcome | Meaning | Action |
|---|---|---|
| Confirmed | The client completed the QoS 1 publish | PUBLISHED (T4) |
| Definite non-publication | Failure before any bytes were handed to the broker (client not connected, serialisation error) | Transient retry or permanent failure per classification |
| **Uncertain** | Timeout, connection loss or lease expiry after the publish may have reached the broker | ATTEMPT_UNCERTAIN; retry allowed; duplicate publication possible and accepted |

**Amended in P4.3 (approved 2026-10-17; the original wording above and in the row "Uncertain" is retained for history):** an UNCERTAIN outcome is NEVER retried automatically, so NEXUS itself never produces a duplicate publication of an accepted command; the "retry allowed; duplicate publication possible and accepted" wording of that row is superseded by R33. Duplicates can still come from the MQTT client or broker (QoS 1 redelivery) and remain possible and visible (R03).

Uncertain outcomes are never recorded as definite failures and never as successes. If an uncertain attempt cannot be retried because the window or attempts are exhausted, the terminal reason is UNCERTAIN_EXPIRED and ActionHistory carries the message `DELIVERY_UNCONFIRMED`, not "publish failed".

**Interim handling in P4.2 (no retries yet).** Until P4.3 every attempt ends in a terminal state with an honest reason; a command is never silently dropped, never endlessly claimed and never reported as published unless the broker confirmed it inside the window:

| Outcome | Outbox | ActionHistory | Reason |
|---|---|---|---|
| Confirmed inside the window | PUBLISHED | SUCCESS, `published_at`, ACK deadline | none |
| Definite non-publication (client not connected or refused the hand-over) | DEAD_LETTER PERMANENT_FAILURE, `last_error_code` NOT_SENT_<code> | FAILURE "the command was not sent ... automatic retry is not available in this release" | no retry exists; P4.3 turns this into a transient retry |
| Uncertain (timeout or failure after the hand-over) | DEAD_LETTER UNCERTAIN_EXPIRED | FAILURE with message `DELIVERY_UNCONFIRMED ...` | duplicate-safe: it is not resent |
| Completion recorded after `expires_at` | DEAD_LETTER UNCERTAIN_EXPIRED, `PUBLISH_COMPLETED_AFTER_EXPIRY` | FAILURE `DELIVERY_UNCONFIRMED` | late and uncertain, never a normal publish |
| Lease expired (crashed or stalled worker) | DEAD_LETTER UNCERTAIN_EXPIRED, `LEASE_EXPIRED_UNCERTAIN`; the original worker is fenced | FAILURE `DELIVERY_UNCONFIRMED` | never re-claimed |
| Window ended before any attempt | DEAD_LETTER EXPIRED | FAILURE "expired ... never sent" | never sent |
| Stored payload no longer matches its hash | DEAD_LETTER PAYLOAD_INTEGRITY, nothing sent | FAILURE | permanent |

**Definitive failure signals are reserved for PROVEN non-publication** (not sent, expired unsent, integrity refusal). For those, the Twin verification service is told once (`commandFailed`) and the COMMAND_PUBLICATION_FAILED rule fact is emitted once, only when the outcome actually changed ActionHistory, after the outcome committed and outside the dispatcher's transaction. For an UNCERTAIN outcome NEITHER is emitted: the command may already have reached, or may still reach, the device, and a rule reacting to a publication failure could issue a compensating command for a command that may execute. The uncertainty stays visible instead: ActionHistory carries `DELIVERY_UNCONFIRMED ...`, the state history has ATTEMPT_UNCERTAIN and DEAD_LETTERED, the pipeline trace and the realtime message say UNCERTAIN, and the manual-command response carries `deliveryState` DEAD_LETTER with `deliveryReason` UNCERTAIN_EXPIRED. A device ACK recorded before the outcome suppresses every failure signal (the device demonstrably received the command) and an ACK is never written, erased or overwritten by the dispatcher. These reactions run in `OutboxOutcomeReactions`; a failing reaction is logged and never changes the committed outcome. Expiry handling and lease recovery exist in P4.2 only as terminal transitions (a minimal scope addition), because without them an expired or lost command would block its device queue forever.

**Classification of the hand-over (forensic finding M1).** An attempt is NOT_SENT only with explicit evidence that the message never entered the client's send path: the client reports itself not connected before the hand-over; building the message or resolving the topic object fails before any client I/O; the client refuses the hand-over with one of the reason codes it raises before it stores or queues anything (not connected, client closed, disconnecting, in-flight limit); or the client cannot store the message (persistence failure). Everything else is UNCERTAIN, including an unexpected runtime exception thrown by the hand-over call, any unlisted reason code, a delivery-token timeout and a connection loss after the hand-over, because the client may already hold the message in its in-flight state and resend it on reconnect (clean session off, in-memory persistence).

A retry republishes the stored bytes (R11). The backend does not try to suppress duplicate broker messages; it guarantees that duplicates produce one lifecycle (first ACK wins, R13). The exception classification table is defined in the ticket and tested row by row.

<a id="r11"></a>
## R11. Immutable payload, timestamp and delivery window (D2)

1. At acceptance the command payload is built once and stored as text with its SHA-256. Every attempt publishes those exact bytes: same `commandId`, `capability`, `command`, `value` and `timestamp`. The timestamp is an integer of epoch seconds set **at acceptance**, not at publish, and is never refreshed. **Amended in the P4.2 corrections (finding M3):** in outbox mode the value is read from the DATABASE clock inside the acceptance transaction (not from the application clock), and so are the creation and scheduling timestamps and therefore the expiry, so an application clock that is wrong in either direction cannot make a command unclaimable, immortal or stale; the value is still exactly what the firmware compares with its own NTP clock. With the flag OFF the legacy publish-time application clock is unchanged. Recorded outcome timestamps (terminal_at, ActionHistory times, history `occurred_at`) remain informational application-clock values; no decision depends on them. The dispatcher verifies the hash before every attempt; a mismatch is PERMANENT_FAILURE PAYLOAD_INTEGRITY and is never published.
2. With the flag OFF the timestamp is generated at publish time exactly as today.
3. The committed firmware rejects a command as STALE when the absolute difference between its clock and the command timestamp exceeds 30 seconds (`COMMAND_STALENESS_WINDOW_SECONDS`), and the check applies in both directions. Therefore:
   - `expires_at = payload_timestamp + window`; no attempt starts at or after `expires_at`.
   - The window ceiling is **20 seconds** = 30 s minus a 10 s uncertainty budget: epoch-second truncation of the payload timestamp (up to 1 s), NTP clock error between backend and device in either direction (5 s), broker and network delay (2 s), and a safety allowance (2 s). The ceiling is enforced three times: at application startup, in the entity factory and by a V37 check constraint. Configuration above it fails application startup.
   - The window must be a whole number of seconds (the payload timestamp has second precision) between 2 s and 20 s.
   - The default window is **15 seconds** (provisional, decision A2).
   - Validation basis: the firmware reads its own clock and compares the absolute difference to the command timestamp, so a command published at the end of the window can appear up to window + 1 s + skew + delay old to the device. The test criteria are: ceiling arithmetic proven in unit tests, a boundary test at the edge of the window against a model of the 30 second firmware check (P4.3), and no attempt starting at or after `expires_at`. The margin is revisited only with measured clock error.
4. The window is measured from the payload timestamp, so queue wait counts against it.
5. A command that expires is DEAD_LETTER EXPIRED. It is **never resent with a refreshed timestamp**. A new attempt to do the same thing is a new command that must pass full acceptance again (R21).
6. A message that the broker holds for an offline subscriber and delivers after the window is protected only by the device's own staleness check; the resulting STALE rejection, if the device acknowledges it, arrives as a late ACK (R13). This is a recorded limitation (R26).

<a id="r12"></a>
## R12. Retry policy and dead letter

Initial defaults (to be validated in P4.1; changes need a contract amendment):

| Property | Default | Bound |
|---|---|---|
| `max-attempts` | 3 | at least 1, at most 5 |
| `attempt-timeout` | 3 s | positive |
| backoff | 500 ms initial, multiplier 2, cap 4 s, jitter plus or minus 20% | positive |
| `lease-duration` | 6 s | must exceed `attempt-timeout` by at least 2 s |
| `delivery-window` | 15 s | ceiling 20 s (R11) |

Selection against the existing infrastructure (decision A3):

| Property | Reason for the default | Trade-off | Test criterion |
|---|---|---|---|
| `max-attempts` 3 | Three tries plus backoff fit the 15 s window; further tries would land near the edge where the device may reject the command as stale | Fewer chances than a longer window would allow | Worst-case schedule 10.8 s is less than 15 s (unit test); cap enforced (P4.3) |
| `attempt-timeout` 3 s | The dispatcher must bound each publish itself. The shared MQTT client waits up to its connect timeout (10 s) plus grace (5 s) = **15 s** for a blocking publish (`MQTTConfiguration`), which would consume the whole window, so P4.2 must wait on the delivery token with this explicit timeout rather than call the blocking publish | A slow broker acknowledgement is classified uncertain after 3 s | Timeout classified as uncertain (P4.3) |
| backoff 0.5 s, x2, cap 4 s, jitter 20% | Retry quickly inside the window; jitter keeps instances from retrying in lockstep | More load on a recovering broker than a longer backoff | Bounds and growth (unit test) |
| `lease-duration` 6 s | Longer than one attempt plus a 2 s margin so a live attempt is not stolen, short enough that a crashed claim is recovered while a retry still fits the window | A crashed worker delays that command by up to the lease | Lease margin validated at startup; recovery test (P4.2) |
| `claim-batch-size` 10, `poll-interval` 250 ms, `expiry-scan-interval` 1 s | Existing scanners run every 1 s to 30 s (`RuleTimerWorker` 1 s, `RuleAckTimeoutScanner` 5 s); a 250 ms poll with the post-commit nudge keeps latency low | Idle polling cost | Bounds validated at startup |

Startup validation rejects any configuration where the worst-case schedule (attempts times attempt timeout plus all backoffs) does not fit in the window, where the lease is not longer than the attempt timeout plus margin, or where the window exceeds the ceiling.

Only transient and uncertain outcomes are retried. Permanent failures (serialisation, payload integrity) go straight to DEAD_LETTER PERMANENT_FAILURE.

**Amended in P4.3 (approved 2026-10-17):** only a PROVEN non-publication (NOT_SENT) is retried; an uncertain outcome is never retried; a payload that fails its integrity check is dead-lettered as PAYLOAD_INTEGRITY and never retried (serialisation errors are refused at acceptance, so no outbox row exists for them). The reason PERMANENT_FAILURE stays in the V37 vocabulary but has no producer after P4.3. See R33.

Database backstops (V38; they do not replace the dispatcher): a RETRY_WAIT row must still have an attempt left (`attempt_count < max_attempts`), so an exhausted row cannot be parked and must become DEAD_LETTER RETRY_EXHAUSTED; a row cannot be claimed (`-> IN_FLIGHT`) at or after `expires_at` (checked against the database clock at claim time). The dispatcher of P4.2 must still select only unexpired rows, expire the rest and classify exhaustion itself; nothing in V37 or V38 is a dispatcher and expiry is **not** yet enforced by one.

On DEAD_LETTER: ActionHistory is set to FAILURE with the reason, the Twin verification service is told once (`commandFailed`), and the existing COMMAND_PUBLICATION_FAILED rule fact is emitted **once** at the terminal outcome, not per attempt. The desired state is never cleared automatically. A dead letter is visible (R20, R21) and is never resent automatically. Candidate outcomes recorded by the Phase 3 plan are not rewritten by retries because delivery remains an ActionHistory fact.

<a id="r13"></a>
## R13. ACK handling and late ACK

The existing handler semantics are preserved: the device identity comes from the topic, the command id correlates to ActionHistory, a guarded update accepts only the first ACK, and permanent input errors are acknowledged and dropped while transient errors propagate for broker redelivery.

Additions:

- The first ACK appends ACK_ACCEPTED or ACK_REJECTED in the same transaction.
- A duplicate identical ACK changes nothing and increments a counter. A conflicting duplicate preserves the first and appends ACK_CONFLICT_IGNORED.
- An ACK after ACK_TIMED_OUT is recorded and appends ACK_AFTER_TIMEOUT; after DEAD_LETTER it appends ACK_AFTER_TERMINAL. Nothing is erased, and the API status projection follows the existing rule that an ACK status wins.
- ACK latency is `ack_backend_received_at - published_at` (backend clocks). Device timestamps are never used for latency.
- An ACK that arrives before the publish outcome is written is preserved; the later conditional update does not erase it (existing behaviour).

<a id="r14"></a>
## R14. ACK timeout state

The ACK deadline is `first_publish_confirmed_at + nexus.command.ack-timeout` (default 30 s) and is stored on ActionHistory as today. Retries and duplicate publications never move it. A timeout scanner appends ACK_TIMED_OUT exactly once per command after the deadline when no ACK exists; it is idempotent across repeated runs and instances. The persisted ACK timeout is a delivery-lifecycle fact. It is distinct from Twin verification (P2.4) and from physical convergence: it never creates, changes or clears a verification state or a desired state (decision A9). `CommandStatus.from` continues to compute TIMED_OUT on read for compatibility; the persisted fact is additive. The existing `RuleAckTimeoutScanner` keeps sole ownership of its rule trigger fact, and the new scanner never emits a second one.

<a id="r15"></a>
## R15. Crash recovery and restart

| Crash point | Required outcome |
|---|---|
| After T1, before any claim | After restart the command is claimed and published once |
| After T2, before publish | Lease expires; recovered as uncertain; retried within the window |
| After publish, before T4 | Lease expires; recorded uncertain; a retry may duplicate; ACK still correlates |
| During T4 | The transaction rolls back; recovery completes the command |
| Database outage during an attempt | Lease expiry recovery; nothing lost |
| Backend down longer than the window | DEAD_LETTER EXPIRED on restart; no stale command is published |
| Broker down longer than the window | DEAD_LETTER EXPIRED and visible |

Recovery uses persisted state only and never fabricates evidence. Scanners and workers are safe to run on several instances at once. Restarts cannot raise the attempt count above `max-attempts`. ACK deadlines that passed during downtime are persisted after restart.

<a id="r16"></a>
## R16. Legacy reconciliation (D3)

- **Migration (slice P4.2, with V39 because it needs the history table):** when V39 runs, every `action_history` command row of type SEND_COMMAND still in ACCEPTED_FOR_DISPATCH and without an outbox row receives a DELIVERY_UNKNOWN history fact (source MIGRATION). V37 (empty, P4.1) and V39 are applied in the same Flyway run before the application serves traffic once P4.2 ships, so no row can be mid-dispatch. V37 itself backfills nothing: the P4.1 upgrade test shows the table is empty after a populated database is upgraded. Rows already PUBLISHED, acknowledged or failed are not modified.
- **Runtime reconciler:** with the outbox flag OFF the legacy path can still strand a row by a crash between commit and publish. A reconciler marks such rows DELIVERY_UNKNOWN once, only after a grace period longer than any legitimate dispatch, and never touches a row that has an outbox row or that is inside the grace period.
- **Implemented in P4.2 (V39).** The backfill appends one DELIVERY_UNKNOWN fact (source MIGRATION, reason `LEGACY_DELIVERY_UNRECORDED`) only for SEND_COMMAND rows that are still ACCEPTED_FOR_DISPATCH with NO recorded publication, NO device ACK, NO outbox row and a timestamp older than 5 minutes; it is idempotent. The grace exceeds any legitimate legacy dispatch (its blocking publish is bounded by about 15 seconds), so a recently accepted or concurrently publishing command is never classified; such a row is left for the runtime reconciler (P4.4), which must use the same grace. Published, failed, acknowledged and non-command rows are not touched, no ActionHistory row changes and no outbox row is created.
- **Never resent:** the reconciler and migration publish nothing and create no outbox row. A DELIVERY_UNKNOWN command is not retried automatically under any flag setting. A device that did receive it may still ACK; the ACK is recorded as a fact (R13) and does not imply NEXUS resent it.
- The API exposes `deliveryState = DELIVERY_UNKNOWN` additively (R21). Desired state is untouched.

<a id="r17"></a>
## R17. Telemetry inbox deduplication (D4)

- Telemetry may carry an optional `messageId` (text, 1 to 64 characters from `A-Z a-z 0-9 . _ : -`). **Deduplication happens only when a messageId is present.** Samples without one are never deduplicated, even if their timestamp, value and capability are identical.
- The key is `(device_id, message_id)` in `inbound_message_dedupe` (the P4.5 implementation, R35, keeps `source` out of the key; the original wording named it) with a unique constraint; `source` distinguishes the MQTT and HTTP entry points only for diagnostics and both share one key space per device.
- The dedupe insert (`INSERT ... ON CONFLICT DO NOTHING`) and the telemetry write are in the same transaction (T8). A duplicate stores no telemetry row, makes no Twin update and emits no event or transition. A duplicate is acknowledged to the broker and counted. A duplicate HTTP submission returns an **explicit duplicate/no-op response** (decision A5: a success status with an additive `duplicate: true` field and the original submission reference where available); it is never an error and never a second Twin transition.
- The same messageId with a different value is a conflicting duplicate: the first wins, nothing is stored and the conflict is logged and counted (as implemented in P4.5, R35: HTTP 409; MQTT acknowledge and drop).
- A messageId that violates the format is rejected as invalid input: HTTP 400, MQTT acknowledge and drop (decision A1). It is never ignored or treated as absent, so a malformed id cannot silently bypass deduplication.
- The dedupe table is separate because `telemetry_history` is a hypertable whose unique constraints must include the partition column. V40 does not alter `telemetry_history`.
- The dedupe window is bounded by retention (R19). An id purged from the table is accepted again; this limit is documented.
- The MQTT listener already acknowledges only after processing, so a crash before acknowledgement redelivers the message; the messageId makes that redelivery harmless.
- Onboarding telemetry follows the same rules and a duplicate neither advances the session nor activates the device twice.

<a id="r18"></a>
## R18. Backpressure and rate limiting

Initial defaults: global queued-command cap 500, per-device cap 10, dispatcher batch size 10, publish rate limit 20 per second.

- Caps are checked inside T1 under the device row lock. Over the cap, a manual command is rejected with a typed error (HTTP 429 or 503 with a stable code) **before** any durable write; a rule-originated command is recorded as a suppression with a reason, nothing is dispatched and no desired state is written.
- Publication is throttled by a token bucket (the same technique as `ExplanationRateLimiter`). Excess commands wait in the queue and expire visibly within the window rather than being published in a burst.
- No new unbounded in-memory queue is introduced for ingestion or dispatch. Telemetry processing remains in the MQTT callback; processing lag is exported as a metric.
- Behaviour under a flood must be bounded in memory and table growth, and each command ends PUBLISHED, DEAD_LETTER or typed rejection within its window.

<a id="r19"></a>
## R19. Retention

- **Per-category transactions (finding F2).** `RetentionManager` runs each category (telemetry, pipeline traces, domain events, command outbox, action history) in its own REQUIRES_NEW transaction. One blocked or failing category can never roll back the others. A failure is logged at ERROR with its cause, counted (`nexus.retention.category.failures` with a fixed `category` tag), reported in a `Report` and, for the scheduled entry point, surfaced by throwing `RetentionFailedException` after every category has been attempted. Rows deleted by the other categories stay committed.
- **Reference guards for action_history.** A row is deleted only if it is older than `nexus.retention.actions-days` **and** is not referenced by `current_desired_twin_state.command_id` **and** has no `command_outbox` row. A reference is never cleared: desired state is not cleared automatically (P2.1, P2.4), so verification guarantees are unchanged. The retained row is the one the current desired state points at; every other old row is still removed.
- **Outbox purge.** Resolved (PUBLISHED, DEAD_LETTER) `command_outbox` rows older than `nexus.retention.actions-days` are purged in their own transaction before the action_history category. Unresolved rows are never purged: the foreign key is RESTRICT and a delete trigger refuses them. Retention and device deletion interact by design (R30): purging resolved rows is what lets a device with a delivery history be deleted.
- **History of this defect.** Before the correction the single `@Transactional` retention method issued `DELETE FROM action_history` as one statement; one old row still referenced by current desired state (ON DELETE RESTRICT, V21) failed the whole statement and the shared transaction rolled back every category. Reproduced on PostgreSQL and fixed in P4.1; mutation checks of the desired-state guard and of the transaction isolation each fail a test.
- `command_state_history` (V39, P4.2) is removed together with the resolved outbox purge of its command, and its foreign key must not be able to block the action_history category (ledger P4-139).
- `inbound_message_dedupe` is purged by age; default retention 168 hours, configurable, and never shorter than the longest plausible QoS redelivery horizon.
- Outbox rows in non-terminal states are never removed by retention.
- No new table may grow without a retention rule.

<a id="r20"></a>
## R20. Traceability and observability

- Every state change and post-publication fact has a history row with correlation id, source and backend timestamp.
- Each transition creates a pipeline-trace step through the existing trace mechanism, joined by the correlation id carried from acceptance through attempts to ACK.
- Metrics (Micrometer) use fixed low-cardinality tags drawn from the state and reason codes, never identifiers: queue depth, attempts, outcomes, dead letters, publish latency, ACK latency and convergence latency (both from backend timestamps), duplicate ACKs, duplicate telemetry, dedupe conflicts and ingestion lag.
- Timed-out, dead-lettered and DELIVERY_UNKNOWN commands are listed with reason codes and backend timestamps in the API and in the Actions view.

<a id="r21"></a>
## R21. API projection, compatibility and access control

- API changes are additive. The existing `CommandStatus` and `ExecutionStatus` values keep their meaning. A new `deliveryState` field (QUEUED, IN_FLIGHT, RETRY_WAIT, PUBLISHED, DEAD_LETTER, DELIVERY_UNKNOWN or absent) and a read-only lifecycle view are added. DEAD_LETTER projects to the existing FAILED status and DELIVERY_UNKNOWN to ACCEPTED_FOR_DISPATCH. Clients must tolerate unknown `deliveryState` values.
- Read endpoints expose state, attempts, reason codes and timestamps and no more command parameters than the existing ActionHistory views.
- **No manual requeue, resend or "retry" endpoint is exposed.** The API has no unauthenticated capability that can cause a physical command to be re-sent. Because an expired dead letter cannot be resent with its original timestamp (R11), an operator retry is by definition a new command through full acceptance. Any resend or requeue capability is out of scope until Phase 7 provides access control and requires an explicit security decision. No scheduler, listener or controller may depend on a requeue operation.

<a id="r22"></a>
## R22. Feature flag, controlled enablement and rollback (D7)

- `nexus.command.outbox.enabled` defaults to **false**. With it off, acceptance, publication, timestamps and statuses are the existing behaviour and no outbox or state-history rows are written for commands. Migrations still apply.
- **Enablement procedure** (executed only after the Phase 4 gate passes): (1) apply V37 to V40 to a disposable copy and confirm checksums and reconciliation; (2) run the full validation gate including the Phase 4 crash-recovery smoke test; (3) confirm there is no unreviewed DELIVERY_UNKNOWN backlog; (4) enable on one backend instance first; (5) watch the queue depth, dead-letter, retry and duplicate metrics for a defined soak; (6) enable on remaining instances. The procedure is checkable by script against a disposable environment.
- **One authoritative, validated flag (finding H1).** `CommandOutboxSettings.enabled()` is the only decision: acceptance, the coordinator, the executor guard and the dispatcher start-up validation all ask that one bean, and it parses `nexus.command.outbox.enabled` strictly. Only the words `true` and `false` (any case) are accepted; `1`, `on`, `yes` or anything else stops startup, because Spring's own boolean conversion accepts some spellings that a property condition does not, which once let acceptance enqueue while no dispatcher existed. `CommandOutboxDispatcher` is an unconditional bean that never consults the flag. Startup also fails if the outbox is enabled and no dispatcher bean exists, and `nexus.command.outbox.dispatcher-enabled=false` (a switch for test contexts that fabricate unresolved obligations) is refused while the outbox is enabled.
- **Rollback is drain-only (implemented and tested):** setting the flag to false stops creating outbox rows but the always-present dispatcher keeps servicing existing QUEUED, RETRY_WAIT and IN_FLIGHT rows (delivery, expiry, lease recovery and the uncertain-delivery hold) to a terminal state or expiry (at most one window). No row is stranded. An idle system costs one indexed EXISTS probe per poll. Rollback never deletes outbox or history data.

<a id="r23"></a>
## R23. Migrations

Forward-only, checksummed, tested on a populated disposable database as well as an empty one:

| Migration | Content |
|---|---|
| V37 | `command_outbox`, constraints, indexes, update and delete guard triggers. **Delivered in P4.1.** Starts empty; backfills nothing. |
| V38 | **Delivered in P4.1 (safety corrections).** `command_outbox.device_id` becomes ON DELETE RESTRICT; BEFORE DELETE trigger on `devices` with stable messages `DEVICE_HAS_UNRESOLVED_COMMANDS` and `DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE`; check that a RETRY_WAIT row has an attempt left; claim-after-expiry rejection in the update trigger. Modifies no earlier migration. |
| V39 | `command_state_history`, append-only trigger, and the legacy DELIVERY_UNKNOWN backfill (R16). **Delivered in P4.2.** Foreign key to action_history ON DELETE CASCADE with a delete guard that allows deletion only with the parent row. Modifies no earlier migration. |
| V40 | `inbound_message_dedupe` and index for purging |
| V41 and later | Reserved. Not planned. A domain-event outbox would need a new approval (R28). |

Existing historical rows are never rewritten.

<a id="r24"></a>
## R24. Rule engine, Twin and Phase 3 interaction

- Phase 3 behaviour is preserved with the flag off and on. The full Phase 3 suites must pass in both modes.
- Candidate outcomes (ACCEPTED, EXECUTED and so on) stay derived from acceptance and executor evidence; retries do not rewrite them. This is also how the recorded Phase 3 limit that a decided command is not re-dispatched is addressed, without changing any plan or arbitration semantics.
- Dry-run and explanation paths remain zero-effect and must not write the new tables. Explanations must not claim publication or acknowledgement for a command that is only QUEUED, DEAD_LETTER or DELIVERY_UNKNOWN.
- Verification (P2.4) is unchanged; Twin verification deadlines and states are not copied into the outbox.
- Onboarding telemetry semantics are unchanged apart from deduplication.

<a id="r25"></a>
## R25. Protocol extensions and protected paths (D6)

- Backend: `TelemetryRequest` gains an optional `messageId`; unknown or absent fields behave as today. A payload exactly as the committed firmware sends it is accepted and stored unchanged.
- Simulator: each telemetry publication carries a new UUID `messageId`; a test hook can republish an identical message to model redelivery. The simulator command handler is **not changed**: its stale window default is 300 seconds against the firmware's 30 seconds, which is a recorded parity gap to be corrected only by a separately approved change (decision A8). Phase 4 tests model the actual firmware freshness semantics (absolute difference above 30 seconds is STALE) in a test-side helper.
- `firmware/**`, `hardware-tests/**`, `results/**` and Review III artefacts are not modified by any Phase 4 slice. The five user-owned handoff and roadmap documents are not modified without authorisation.

<a id="r26"></a>
## R26. MQTT and device-side limitations

- The broker PUBACK proves broker receipt only. Whether the broker queues a message for an offline subscriber depends on broker and device session configuration that the backend cannot assume.
- The backend client uses in-memory persistence, so in-flight QoS 1 state does not survive a JVM exit; the outbox, not client persistence, is the durable record.
- The committed firmware has no persistent command deduplication, no ACK retry, no command queue and no GPIO readback or current sensing. It supports one command (LED SET_STATE) with a closed allowlist, rejects MALFORMED, UNSUPPORTED and STALE commands, and acknowledges once per received command. A duplicate delivery produces a repeated application of the same absolute state and possibly a repeated ACK.
- **Inspection record (P4.2).** `firmware/src/main.cpp` `onMqttMessage` (lines 161 to 220) holds no set or log of seen commandIds; a duplicate delivery of an in-window LED SET_STATE command is applied again and acknowledged again. The simulator (`simulator/src/nexus_simulator/command_handler.py`) keeps a bounded commandId LRU and replays the first result for a duplicate, so duplicate suppression exists in the simulator only. The backend therefore must not, and does not, rely on device-side deduplication: P4.2 sends a command at most once, and an uncertain outcome is never resent.
- Simulator behaviour is software evidence only; it proves nothing about firmware or physical devices.
- Delivery to REAL-mode devices is currently disabled in `CommandExecutor.prepare` and is outside this contract.

<a id="r27"></a>
## R27. Fault-injection acceptance requirements

The ledger scenarios in groups B, C, D, F, G and K are the required campaign. At minimum, evidence on disposable PostgreSQL and Mosquitto must show: broker stopped before and during publish; backend killed after T1, after T2 and after publish; database connection loss at T4; duplicate and stale workers; instance clock skew; duplicate, conflicting and late ACKs; telemetry redelivery before broker acknowledgement; queue saturation; restart storms; flag-off drain; and migration on a populated database. Kill points use process termination, not graceful shutdown. The Phase 4 crash-recovery smoke test becomes part of `validate_nexus.py --full`.

<a id="r28"></a>
## R28. Deferred requirements (D5)

**General domain-event outbox: deferred.** Inspection of `EventDispatcher` found an in-process listener list; a listener failure is logged and swallowed, and the WebSocket broadcast is best effort. Rule routing is already durable through `rule_trigger_facts` with a recovery scanner, and Phase 3 already fails closed when source or match persistence fails. No command-delivery requirement depends on domain-event delivery. No unrecovered specific requirement was identified, so no event-outbox table, dispatcher or migration is planned. This is recorded as ledger scenario P4-124 (DEFERRED) and is re-opened only by an explicit new requirement.

<a id="r29"></a>
## R29. Evidence discipline

Focused regressions run first and broad gates only when appropriate. Failures are preserved; assertions, timeouts, isolation and skips are never weakened to obtain green results. Actual counts, skips and evidence layers are recorded. COVERED requires the exact test and its assertions to be inspected. Mutation checks are reverted and recorded. Evidence uses only disposable PostgreSQL and Mosquitto; the development database is never queried, migrated or repaired, and `start.bat`, `start_nexus.py` and `seed.ps1` are not used for isolated validation. Nothing is staged, committed, tagged or pushed without an explicit request.

<a id="r30"></a>
## R30. Device deletion and delivery-evidence policy (forensic finding F1)

**Defect (reproduced on PostgreSQL).** With V37 a device deletion cascaded through `command_outbox` and silently erased QUEUED, IN_FLIGHT and RETRY_WAIT obligations (and PUBLISHED and DEAD_LETTER rows). The ActionHistory rows stayed ACCEPTED_FOR_DISPATCH with a null device: an outstanding command disappeared without a durable terminal record. For an IN_FLIGHT command that may already have been published, the physical device could act with no record at all.

**Policy (fail closed, enforced by the database so no deletion path bypasses it):**

1. A device with any QUEUED, IN_FLIGHT or RETRY_WAIT obligation **cannot be deleted**. The rejection is typed: `DEVICE_HAS_UNRESOLVED_COMMANDS`.
2. A device with **any** outbox row, including PUBLISHED and DEAD_LETTER, also cannot be deleted: `DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE`. Reason: the evidence mirror that would make deleting resolved rows safe (a terminal fact in a durable state history keyed by the command, surviving device removal) does not exist yet (V39, P4.2), and the outcome facts in ActionHistory alone do not preserve the dead-letter reason, attempts or payload bytes. If that cannot be guaranteed under the foreign-key graph the system fails closed.
3. **Retention and audit policy for resolved rows:** resolved delivery rows are retained until `nexus.retention.actions-days` (default 90 days) after they resolved and are then purged by the retention job (R19). Once purged, the device can be deleted. A later migration may relax rule 2 to "unresolved only" once a durable state history mirrors every terminal outcome; that needs an explicit decision.
4. A refused deletion changes nothing: the device, ActionHistory rows, desired state and verification evidence are untouched, and the surrounding transaction is rolled back like any failed statement.
5. A device with no outbox row (including every device in a deployment that never enabled the outbox, and every legacy command) is deleted exactly as before. No cascade from site, building or space reaches `devices` (those keys are RESTRICT), so the guard also covers every path that removes a device.

**Mechanism.** V38 changes the key to ON DELETE RESTRICT and adds a BEFORE DELETE trigger on `devices` with distinct stable messages. The application adds a port, `DeviceDeletionGuard` (device module), implemented by `OutboxDeviceDeletionGuard` (action module, independent of the feature flag), so `DeviceService.deleteDevice` rejects before the database does with `DeviceDeletionBlockedException`, mapped to HTTP 409 with `{status, error, code, message, timestamp}`. If a concurrent acceptance wins after the check, the database trigger's message is translated into the same typed exception.

**Races (all proved by test).** An acceptance that has inserted an obligation holds a key-share lock on the device row, so a concurrent deletion waits for it and is then refused. A deletion that commits first makes a later acceptance fail on the foreign key instead of leaving an orphan. A deletion racing a claim or a resolution is refused (it either sees an unresolved row or waits for the other transaction and then sees delivery evidence). Refusal is always the fail-closed outcome.

**Limits.** `TRUNCATE` bypasses row-level triggers; it needs table-owner privilege and is outside the application and the retention job. Devices that have an onboarding session are already undeletable (`device_onboarding_sessions.device_id` is RESTRICT, V20); that is unchanged.

<a id="r31"></a>
## R31. Mandatory safety requirements for P4.2

The dispatcher slice must satisfy all of the following; each is a ledger scenario and none is satisfied by P4.1.

1. **No direct publication bypass when the outbox flag is enabled.** `CommandExecutor.execute` and `publishPrepared` publish without acceptance; `ActionExecutionEngine`'s four-argument constructor leaves the coordinator null. With the flag on, every SEND_COMMAND must pass acceptance and the outbox, direct publication must be refused, and an architecture test must prove that `mqttClient.publish` is reachable only from the dispatcher (P4-137).
2. **Atomic acceptance.** ActionHistory, desired state, desired transition, verification initialisation and the outbox row commit in the one existing acceptance transaction, in the existing device-first lock order (R07, R08; P4-001 to P4-004).
3. **No SUCCESS for a merely QUEUED command.** `ExecutionStatus.SUCCESS` means broker publication. The acceptance outcome, the engine's executed/failed events, the pipeline trace, the realtime broadcast and the manual-command API response must not report SUCCESS for a command that is only queued; the manual API returns ACCEPTED_FOR_DISPATCH with a delivery state (P4-138).
4. **No dispatch of expired commands.** The claim query selects only rows with `now() < expires_at`; expired rows become DEAD_LETTER EXPIRED; a completion that arrives after expiry is recorded as late and uncertain, not as a normal publish (P4-036, P4-140). The V38 claim backstop is not a substitute.
5. **No automatic replay of pre-V37 commands** (R16, P4-069).
6. **No misleading exactly-once claim** anywhere in code, API text or UI (R03, P4-123).
7. **No unauthenticated manual requeue endpoint** and no internal requeue reachable from HTTP or MQTT (R21, P4-098, P4-099).
8. **No deletion of unresolved obligations**, by device deletion, retention or any other path (R19, R30, P4-125).
9. **Lock order.** The dispatcher locks the outbox row, then the action_history row, and never the device row. The ACK handler locks device then action_history; device deletion is refused while obligations exist, so there is no cascade left to race (P4-141).
10. **Payload completeness.** V37 proves `commandId`, `timestamp`, topic and hash only; the builder must also guarantee `capability`, `command` and `value`, with an integer `timestamp`, as the firmware requires (P4-142).
11. **Single-in-flight handling.** A claim that loses the unique-index race (SQLSTATE 23505) is a normal outcome to handle, not an error to propagate (P4-133).

**Status after P4.2.** Item 1 (P4-137), item 2 (P4-001, P4-002, P4-003), item 3 (P4-138), item 4 (P4-036 and P4-140), item 7 (P4-098 and P4-099), item 9 (P4-141), item 10 (P4-142) and item 11 (P4-133, covered in P4.1 and exercised again by the dispatcher) are implemented and covered by tests. Item 5 (no replay of pre-V37 commands) is implemented by V39, which creates no outbox row and publishes nothing (P4-068). Item 6 holds by source audit only (`OutboxArchitectureTest`); the documentation audit P4-123 belongs to P4.8. Item 8 is covered since P4.1 (P4-125). Remaining caveats: lease fencing, expiry and payload completeness are application-level and are proven by tests, not enforced by the database; `TRUNCATE` bypasses every row trigger (privileged operation).

<a id="r32"></a>
## R32. Uncertain-delivery barrier (forensic finding H2)

**Hazard.** A publish whose confirmation timed out may still be delivered: the Paho client keeps the QoS 1 message and resends it on reconnect (clean session off, in-memory persistence), and the broker may redeliver. The committed firmware applies any command whose timestamp is within 30 seconds of its own clock (absolute difference, both directions) and has no commandId deduplication. If the next command of the device were sent at once, the late copy of the earlier one could be applied after it: A = ON delayed past B = OFF leaves the LED ON while the desired state is OFF.

**Rule.** While an earlier command of the same device ended UNCERTAIN_EXPIRED, later commands of that device are not claimed. The hold ends at `payload timestamp + 40 s` (30 s firmware freshness plus the 10 s clock/network margin of R11), measured on the database clock, but never later than `creation + 60 s` on the database clock, so a wrong payload clock can neither shorten it unboundedly nor extend it beyond the cap. After the horizon the firmware rejects any copy as STALE. The hold is persisted in the outbox rows themselves (no new table), so it survives restarts and is identical for every instance. An ACK does **not** shorten it, because a duplicate delivery of the same message would still be applied. A predecessor that expired unsent, failed with proven non-publication, failed integrity or was published does not hold the device.

**Visibility.** A held command stays QUEUED; once, a state-history fact (QUEUED to QUEUED, reason HELD_UNCERTAIN_PREDECESSOR), an ActionHistory message ("Held: ... not sent before <time>"), a pipeline trace (HELD) and a listener callback record the hold. If its own delivery window ends during the hold it is dead-lettered EXPIRED with `last_error_code` HELD_BY_UNCERTAIN_PREDECESSOR and is never sent (with the 15 s window and the 40 s horizon, a command accepted right behind an uncertain one normally expires: that is the price of not overtaking, it is visible, and the desired state it recorded is not cleared).

**Not claimed.** No physical ordering guarantee beyond this. A duplicate of a CONFIRMED publish (the broker redelivering to a device that missed the PUBACK) is not covered; it needs device-side deduplication, which needs a firmware change that Phase 4 does not make (R25).

<a id="r33"></a>
## R33. Retry policy as implemented in P4.3 (approved amendment, 2026-10-17)

**Eligibility (one place).** `OutboxRetryPolicy.decide` is the only decision, `OutboxDeliveryOperations.notSent` the only writer of RETRY_WAIT (both proven by `OutboxArchitectureTest`). A command is retried only when ALL hold: the attempt that just ended is a proven NOT_SENT (R10 classification; every unlisted Paho reason code is UNCERTAIN, proven by the table-driven `PahoClassificationTableTest` over every reason code the client defines); the attempt number is below the stored `max_attempts`; the next attempt would START strictly before `expires_at` on the DATABASE clock; the fenced outcome write still owns the claim (lease owner and attempt). Anything else is refused: UNCERTAIN, an expired lease and PAYLOAD_INTEGRITY are never retried.

**Identity.** A retry republishes the stored `payload_text` verbatim: same command id, same SHA-256, same epoch-second timestamp, same topic, same expiry and budget. The identity columns are immutable (V37 trigger), so even a defect cannot change them; a retry creates no ActionHistory row, no desired state and no new acceptance, and calls neither `RuleExecutionAuthorization` nor `CommandAcceptanceService` (R08).

**Backoff.** `delay after attempt n = min(max-backoff, initial-backoff x multiplier^(n-1))`, then jittered SYMMETRICALLY by plus or minus `jitter-fraction` (0.2 by default), never below 1 ms and never above `max-backoff x (1 + jitter-fraction)`. The jitter source is injected, so every delay is exact in tests. The schedule is persisted: `next_attempt_at = clock_timestamp() + delay` is written in the same transaction as RETRY_WAIT, so it survives a restart and is judged by the database clock only.

**Worst case, recomputed from the real settings (not assumed).** `worstCaseSchedule` = attempts x attempt-timeout + the jittered maximum of every backoff = 3 x 3 s + 0.6 s + 1.2 s = **10.8 s** for the defaults (independently recomputed in `OutboxRetryPolicyTest`). The end-to-end worst case also pays one poll interval before every claim and up to one second of payload-timestamp truncation: 10.8 + 3 x 0.25 + 1.0 = **12.55 s**, inside the 15 s default window with 2.45 s to spare. Startup now validates this end-to-end figure (`CommandOutboxSettings.worstCaseEndToEnd`), so a 12 s window, which the old 10.8 s check allowed, is refused. No window is ever extended; at the 20 s ceiling the worst age at the device is 28 s against the 30 s firmware check.

**Outcomes.** NOT_SENT with attempts and window left: RETRY_WAIT (history fact ATTEMPT_FAILED_TRANSIENT; ActionHistory stays ACCEPTED_FOR_DISPATCH with an explanatory message). NOT_SENT on the last attempt: DEAD_LETTER RETRY_EXHAUSTED. NOT_SENT when the backoff would end at or after `expires_at`: DEAD_LETTER EXPIRED ("never sent"). A RETRY_WAIT row whose window ends is expired by the sweep without any further attempt. Every dead letter above proves the command was never sent, so the existing signals apply exactly once at the terminal outcome (Twin `commandFailed`, one COMMAND_PUBLICATION_FAILED fact); an intermediate NOT_SENT raises none.

**Ordering.** A RETRY_WAIT command blocks every later command of its device exactly like an IN_FLIGHT one (the P4.2 claim guard), and it is released only by PUBLISHED or a terminal outcome; other devices are not blocked. The P4.2 uncertain-delivery hold (R32) is unchanged. This is claim order, not a device-side ordering guarantee.

**Crash safety of the failure signals.** The reactions run after the outcome commits, so a crash between the commit and the reaction could lose them. `OutboxDeliveryOperations.unreactedFailures` (scan every 5 s, bounded batch) finds proven-not-sent dead letters older than 5 s that the device did not acknowledge and whose Twin verification is still PENDING, or whose rule fact is missing within a 10 minute lookback, and re-applies ONLY those two signals through `recoverFailureSignals`. Both are idempotent (the rule fact is unique per command, `commandFailed` only moves PENDING to UNVERIFIED), nothing is broadcast again, and an uncertain or acknowledged outcome is never reported. No new table or migration is used.

**Not claimed.** No exactly-once publication or actuation, no device-side deduplication, no strict FIFO at the device, no multi-process or hardware evidence beyond what the tests state. An UNCERTAIN outcome still holds the device for 40 s (R32) and ends as DEAD_LETTER UNCERTAIN_EXPIRED; there is no manual or internal requeue (R21).

<a id="r34"></a>
## R34. ACK facts, persisted ACK timeout and legacy reconciliation as implemented in P4.4

R13, R14 and R16 are implemented as written; this section records the decisions their wording left open. **No migration was added**: the V39 vocabulary already contains every ACK fact type and the sources ACK_HANDLER, TIMEOUT_SCANNER and RECONCILER, so V40 stays reserved for P4.5 (R17).

**ACK facts (`CommandAckFacts`, written in the same transaction as the guarded ACK update of the actual `DeviceCommandAckMessageHandler`).** The first ACK appends ACK_ACCEPTED or ACK_REJECTED (reason code: the device reason in fixed-code form). If the persisted timeout fact exists and the ACK was received at or after the deadline it appends ACK_AFTER_TIMEOUT as well; if the outbox obligation is DEAD_LETTER it appends ACK_AFTER_TERMINAL as well. The earlier timeout or terminal fact is never changed (history is append-only, V39). An ACK received before the deadline but processed after the scanner recorded the timeout is an on-time ACK (the deadline decides, not the processing time) with an explanatory detail. An identical duplicate changes nothing and increments `nexus.command.ack{outcome=duplicate}`. A conflicting duplicate never overwrites the first (no last-write-wins), appends ACK_CONFLICT_IGNORED, and at most 5 such facts are written per command (further ones are only counted). A failure writing any fact rolls the ACK back and propagates, so the broker redelivers. All existing validation (topic, JSON, command id, status, timestamp, device row, command, device match) is kept and still acknowledge-and-drops; invalid messages create no fact.

**Clock.** The ACK receipt time and the persisted ACK deadline come from the DATABASE clock: the dispatcher computes `published_at`, `first_publish_confirmed_at` and `ack_deadline_at` (publication instant plus `nexus.command.ack-timeout`) from `clock_timestamp()` instead of the application clock, and the handler stamps the receipt time with the same clock, so lateness is judged in one clock domain. This corrects an interim P4.2 behaviour in which the deadline was derived from the application clock. The deadline is anchored at the first confirmed publication and is never moved (retries precede the publication; nothing re-publishes afterwards).

**ACK versus the outbox.** An ACK never changes the outbox state and never converges anything. The ACK path only READS the outbox, without a lock, because its lock order (device row, then action_history row) must not meet the dispatcher's (outbox row, then action_history row); the history advisory lock is always last. A command that already has an ACK is never claimed. If an ACK exists for a QUEUED or RETRY_WAIT command (the device holds a command NEXUS had not recorded as published), the dispatcher sweep ends the obligation as DEAD_LETTER UNCERTAIN_EXPIRED with last_error_code ACKNOWLEDGED_BEFORE_PUBLICATION and the message DELIVERY_UNCONFIRMED: no broker confirmation is claimed, no failure signal is sent (an acknowledged command is never reported as a publication failure), nothing is published, and the P4.2 per-device hold (R32) applies. An ACK does not release a hold.

**Persisted ACK timeout (`CommandAckTimeoutScanner`).** The single owner of the ACK_TIMED_OUT history fact. Eligible: SEND_COMMAND, status SUCCESS, no ACK, a stored deadline strictly in the past on the database clock, no timeout fact yet. Each candidate is re-read under the action_history row lock (the lock a first ACK takes), so an ACK and a scanner, or two scanners, serialise and exactly one outcome is written. It appends one fact (source TIMEOUT_SCANNER, reason ACK_DEADLINE_PASSED) and nothing else: no publication, no outbox change, no ActionHistory change, no desired-state or verification change (A9), no rule fact (that stays with `RuleAckTimeoutScanner`, which is unchanged). A timeout says nothing about whether the device acted. Bounded batches (100), fixed delay (`nexus.command.ack-timeout-scan-interval`, PT5S), failures logged and repeated.

**Legacy reconciliation (`LegacyDeliveryReconciler`).** The runtime counterpart of the V39 backfill with the same eligibility and grace (`nexus.command.legacy-reconcile-grace`, at least 5 minutes, enforced at startup): SEND_COMMAND, still ACCEPTED_FOR_DISPATCH, no publication, no ACK, no outbox row, no history fact, older than the grace on the database clock. It appends one DELIVERY_UNKNOWN fact (source RECONCILER, reason LEGACY_DELIVERY_UNRECORDED) and nothing else; it has no publisher, creates no outbox row, changes no ActionHistory, never fabricates evidence. Each candidate is rechecked under the action_history row lock, with the conditions on other tables evaluated in separate statements after the lock (a locking read re-checks only the locked row; evaluating them in the same statement let a second reconciler classify a command twice, found by the concurrency test). Interrupted runs are simply repeated.

**Configuration.** `nexus.command.lifecycle-scanners-enabled` (default true) switches only the SCHEDULED loops of both workers. They are always beans and do not consult the outbox flag, because legacy (flag OFF) commands carry deadlines and can be stranded too. Test contexts default it to false, as for the dispatcher; the tests that claim worker behaviour call `scan()` and `reconcile()` directly, and `CommandLifecycleScannersLiveIntegrationTest` enables the scheduled loops and asserts the beans are enabled.

**Not claimed.** An ACK is not authenticated (Phase 7) and is not proof of actuation; an ACK timeout is not proof of non-actuation; the committed firmware still has no commandId deduplication. For legacy rows the stored acceptance timestamp is an application-clock value, so an application clock that is more than the grace behind could make a still-running legacy dispatch look old (the effect is a spurious history fact only). `CommandStatus.from` still computes TIMED_OUT on read with the application clock (R14). A broker-level redelivery of an unacknowledged ACK message after a failure is not exercised (the rollback and the later record are). No multi-process evidence.

<a id="r35"></a>
## R35. Telemetry inbox deduplication as implemented in P4.5

R17 is implemented; this section records what its wording left open and the decisions approved for P4.5 (A1 poison classification, A2 fingerprint, A3 device deletion, A4 original reference). V40 is the only migration; V1 to V39 and `telemetry_history` are untouched, `firmware/**` is untouched.

**Schema (V40 `inbound_message_dedupe`).** Columns: `device_id` (UUID, references `devices` ON DELETE CASCADE), `message_id` (VARCHAR 64, `^[A-Za-z0-9._:-]{1,64}$`), `source` ('MQTT' or 'HTTP', a diagnostic label), `fingerprint` (CHAR 64, lowercase hex SHA-256), `telemetry_record_id` (UUID, nullable, no foreign key), `received_at` (database clock default). The primary key is `(device_id, message_id)`: `source` is not part of any key, so MQTT and HTTP share one key space per device. An index on `received_at` serves the purge. The cascade is compatible with R30: the table holds no delivery obligation or evidence, so deleting a device removes its entries, can neither block the deletion nor leave an orphan, and the V38 guard (which concerns `command_outbox` only) is unchanged. A deletion racing a live ingestion either waits for the ingestion transaction (the foreign-key check holds a key-share lock) or makes the claim fail on the foreign key; both end without an orphan (tested).

**Message identity.** `messageId` is optional on `TelemetryRequest` for both entry points. It must be a JSON string: a number, boolean, object or array is refused by a strict deserializer (Jackson's default would coerce 123 to "123", which would let a malformed id pass). A string outside the format, the empty string and an id longer than 64 characters violate bean validation. JSON null is the same as an absent field (no deduplication). Invalid id: HTTP 400; MQTT acknowledge and drop, counted as `INVALID_MESSAGE_ID`. A payload without a messageId (exactly what the committed firmware sends) is accepted and stored as before and is never deduplicated.

**Fingerprint contract (A2, `TelemetryFingerprint`, version prefix `v1`).** SHA-256 over a deterministic text of: `sensorType`, `capabilityKey` (the capability identity exactly as supplied), the `value` with its JSON type tag, `unit`, the device `timestamp` as epoch seconds and nanoseconds, and `readbackCommandId`. Every text field is tagged by name and length-prefixed, so no value can be mistaken for another field. An absent optional field is encoded as absent, never defaulted (no timestamp is ever invented, an absent unit differs from an empty one). Numbers are compared by value, not spelling (`1`, `1.0`, `1.00`, `1e0` are one number); booleans, text and numbers never collide (`true`, `"true"` and `1` differ); object keys are sorted, array order is kept. Excluded: the messageId (it is the key), the device id (the key is per device), the transport, the receipt time and every generated value. MQTT and HTTP build the same `TelemetryRequest`, so one logical message has one fingerprint on both. Changing the encoding changes every fingerprint.

**Outcomes.**

| Case | HTTP `POST /api/v1/telemetry` | MQTT telemetry topic |
|---|---|---|
| First sight of the id | 201 Created, stored, no `duplicate` field | stored, acknowledged after processing |
| Identical duplicate (same id, same fingerprint) | 200 OK, `duplicate: true`, `id` is the original sample's id while it is still stored and null otherwise; nothing stored | nothing stored, acknowledged without repeating any processing |
| Conflicting duplicate (same id, different fingerprint) | 409 Conflict, nothing stored | diagnostic logged, counted as `DUPLICATE_CONFLICT`, acknowledged and dropped, nothing stored |
| Invalid id | 400 | acknowledged and dropped |

`duplicate` is additive: it is omitted from every normal response, so existing clients see the same shape. The first submission always wins.

**Atomicity and ordering (invariants 6 to 12).** `TelemetryService.processTelemetry` makes the claim right after the device admission check and before every other effect: `INSERT ... ON CONFLICT DO NOTHING` through `InboundMessageDedupe.claim`, which requires the caller's transaction (propagation MANDATORY). The telemetry write, Twin mutation, verification observation, rule fact and onboarding step run in the same transaction. A failure anywhere rolls the claim back with them, so a message is never recorded as seen that was not stored; the redelivery is then a first acceptance (tested with an injected failure after the Twin update). Everything that leaves the transaction is registered after commit (realtime publication, pipeline trace, Twin projection and events); rule evaluation is routed after commit by the existing bounded queue. A duplicate returns before all of it, so it makes no Twin transition, no event, no rule evaluation and therefore no command. Two concurrent submissions of one id serialise on the unique index: the second waits for the first transaction and then sees the committed claim (a duplicate or a conflict) or, if the first rolled back, claims the id itself. Exactly one row is committed (12 concurrent threads over both transports; 8 threads with different payloads: one stored, seven conflicts).

**Poison-message classification (A1).** `TelemetryMQTTListener` acknowledges and drops only explicitly identified deterministic failures: malformed JSON, an invalid or non-text messageId, other bean-validation failures, an invalid legacy payload, a topic and payload device mismatch, `InvalidTelemetryException` (a new typed `IllegalArgumentException`, so HTTP still answers 400, thrown by `TelemetryService` for a missing or unsupported capability identity, a value of the wrong type including a numeric value for a BOOLEAN capability, a violated binding constraint and a non-numeric legacy value), `OnboardingTelemetryRejectedException` and `TelemetryMessageConflictException`. Each is counted in `nexus.telemetry.permanent.rejections` with a fixed `reason` tag. Everything else is not acknowledged and stays retryable: database and connection failures, lock and timeout failures, `IllegalStateException`, any unexpected exception, and (deliberately) a bare `IllegalArgumentException` from a deeper layer. An unknown device (`ResourceNotFoundException`) and a device that is not admitted are unchanged: still retried, which can loop for a deleted device; this is recorded below, not changed. The defect that motivated A1 (found while rehearsing the review demonstration): the repository simulator sent the numeric 1.0 for BOOLEAN capabilities, the validator rejected it, and the listener treated the deterministic failure as transient, so the broker redelivered it forever and starved the other messages. The simulator now sends the generic boolean shape.

**Retention (R19).** Entries older than `nexus.retention.inbound-dedupe-hours` (default 168, minimum 24, enforced at startup) are purged by the daily job as its own category `INBOUND_MESSAGE_DEDUPE`, in batches of 1,000 rows (`inbound-dedupe-batch-size`) with at most 100 batches per run (`inbound-dedupe-max-batches`), each batch in its own REQUIRES_NEW transaction, by the database clock (`NOW()` of each batch transaction). A failing purge never rolls back another category. Telemetry rows are never touched by this purge. **Stated limit:** protection against a redelivered message ends when its entry is purged; a purged id is accepted again. There is no finite window that covers every redelivery: with the Paho client's persistent session (clean session off) the broker keeps unacknowledged messages until the client returns, and the Mosquitto configuration sets no `persistent_client_expiration`, so the horizon is the length of a backend outage. The default of 168 hours therefore means "the protection holds for a backend outage of up to seven days"; the 24 hour floor only prevents an accidental near-zero value. Telemetry messages without a messageId are never protected at all.

**Simulator (R25).** Every telemetry publication carries a fresh UUID `messageId` (MQTT and HTTP publishers), `republish_last()` resends the last publication byte for byte, the HTTP publisher accepts 200 as success, and the repository runner sends BOOLEAN capabilities in the generic shape.

**Metrics.** `nexus.telemetry.duplicates` and `nexus.telemetry.dedupe.conflicts` (tag `source`: MQTT or HTTP), `nexus.telemetry.permanent.rejections` (tag `reason`, fixed set), `nexus.retention.rows.deleted` and `nexus.retention.category.failures` (existing, new category value). Ingestion lag is P4.6.

**Findings recorded while implementing P4.5.**
1. A lock-ordering hazard was suspected (the claim key-share-locks the device row before the Twin update asks for its exclusive lock, under an in-process monitor) and investigated: Hibernate emits `FOR NO KEY UPDATE` for `PESSIMISTIC_WRITE`, which does not conflict with the foreign key's `FOR KEY SHARE`, and 40 rounds of 6 concurrent mixed samples of one device (both transports, with and without an id) neither deadlock nor hang. No locking change was made; the test stays as a regression guard.
2. Pre-existing, unrelated: with the connection pool at 10, twelve concurrent telemetry submissions for one device with no messageId at all made two fail with `CannotCreateTransactionException` after 30 s, because every committing sample needs a second connection in its after-commit step (`PipelineService.recordTrace`) while still holding the first. The MQTT callback is single threaded, so only concurrent HTTP bursts can reach it. Not changed in P4.5.
3. Unknown device and not-admitted device are still retried forever over MQTT (see above).
4. The simulator and the grand simulation runner had been sending a payload the backend rejects; fixed here (simulator), with the listener classification above as the backend half.

**Not claimed.** No exactly-once processing or delivery: deduplication covers only messages that carry a messageId, only within the retention window, and only the backend inbox (the broker still redelivers). The messageId is chosen by the sender and is not authenticated (Phase 7): a sender that reuses another sender's id for the same device is indistinguishable from a retransmission. No device-side deduplication of commands, no multi-process evidence beyond concurrent connections to one PostgreSQL, no change to the firmware (which sends no messageId and is therefore never deduplicated).

## Remaining items after finalization

- **Margin and defaults:** the 10 second uncertainty budget and the retry, lease and cap defaults are provisional (A2, A3). They may be changed only by a contract amendment with updated tests.
- **Dispatcher publish wait:** done in P4.2 (`PahoBrokerCommandPublisher` waits on the delivery token for at most the attempt timeout; the blocking client publish is never used by the dispatcher).
- **Implemented in P4.2 and enforced by the application, not the database:** payload completeness (`capability`, `command`, `value`; integer `timestamp`); expiry of a late-completing publish (an IN_FLIGHT row may still become PUBLISHED after `expires_at`); lease ownership and fencing (a same-state update can change `lease_owner`; fencing lives in the dispatcher's conditional updates); selecting only unexpired rows; `TRUNCATE` is outside the guards. Still open for P4.3: exhaustion classification (the database refuses to park an exhausted row but does not choose RETRY_EXHAUSTED for it), the retry scheduler and RETRY_WAIT.
- **Device-deletion relaxation:** deleting resolved delivery history is refused until retention purges it (R30); relaxing that needs the state history of V39 and a decision.
- **Ordering:** per-device claim order is proven for P4.2 (scenario P4-018, see R09 for its scope). It is not an end-to-end or device-side ordering guarantee and must be re-verified with retries in P4.3.
- **Safe retry policy for P4.3 (proposed, then approved on 2026-10-17 and implemented in P4.3, see R33):** retry ONLY outcomes proven as not sent (the NOT_SENT set of R10), with backoff inside the window; NEVER automatically retry an UNCERTAIN outcome without a proven device-side idempotency mechanism, and keep the R32 hold so a later command cannot overtake it; a retried command keeps its identity, payload and timestamp; ordering with RETRY_WAIT must be re-proven. The only supported command (LED SET_STATE) sets an absolute state, so a duplicate re-applies the same target, but it is not safe against out-of-order replay, which is why uncertain outcomes are held rather than retried.
- **P4.3 dependencies:** replace the terminal-only transient and uncertain branches with RETRY_WAIT while attempts and window remain (the schema, state machine and `max_attempts` bound already support it); add the backoff schedule and attempt cap enforcement; classify exhaustion as RETRY_EXHAUSTED; keep zero reauthorisation; keep the 23505 handling; extend the ordering proof to RETRY_WAIT; retain the bounded publish and the uncertain-never-success rule.
- **Response shape of a duplicate telemetry submission:** resolved in P4.5 (R35): 200 OK with the additive `duplicate: true`; a conflicting duplicate is 409.
