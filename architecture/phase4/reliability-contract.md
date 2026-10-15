# Phase 4 reliable event and action pipeline contract

Date: 2026-10-15.

Status: **P4.0 CONTRACT APPROVED AND FINALIZED (open points A1 to A9 resolved below). P4.1 (V37 command_outbox, configuration, retention protection) and its safety corrections (V38 device-deletion guard and database backstops, per-category retention with desired-state and outbox guards, forensic findings F1, F2 and F7) are implemented and tested but uncommitted; the outbox is not used by production dispatch and its feature flag defaults OFF. P4.2 to P4.8 are not started.**

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
4. Offline or unknown device, disconnected broker client and invalid targets continue to be rejected at acceptance by `CommandExecutor.prepare` before any durable write, exactly as today.
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

<a id="r10"></a>
## R10. Publication outcomes, uncertainty and duplicates

An attempt has exactly one of three outcomes:

| Outcome | Meaning | Action |
|---|---|---|
| Confirmed | The client completed the QoS 1 publish | PUBLISHED (T4) |
| Definite non-publication | Failure before any bytes were handed to the broker (client not connected, serialisation error) | Transient retry or permanent failure per classification |
| **Uncertain** | Timeout, connection loss or lease expiry after the publish may have reached the broker | ATTEMPT_UNCERTAIN; retry allowed; duplicate publication possible and accepted |

Uncertain outcomes are never recorded as definite failures and never as successes. If an uncertain attempt cannot be retried because the window or attempts are exhausted, the terminal reason is UNCERTAIN_EXPIRED and ActionHistory carries the message `DELIVERY_UNCONFIRMED`, not "publish failed".

A retry republishes the stored bytes (R11). The backend does not try to suppress duplicate broker messages; it guarantees that duplicates produce one lifecycle (first ACK wins, R13). The exception classification table is defined in the ticket and tested row by row.

<a id="r11"></a>
## R11. Immutable payload, timestamp and delivery window (D2)

1. At acceptance the command payload is built once and stored as text with its SHA-256. Every attempt publishes those exact bytes: same `commandId`, `capability`, `command`, `value` and `timestamp`. The timestamp (epoch seconds, backend application clock) is set **at acceptance**, not at publish, and is never refreshed. The dispatcher verifies the hash before every attempt; a mismatch is PERMANENT_FAILURE PAYLOAD_INTEGRITY and is never published.
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
- **Never resent:** the reconciler and migration publish nothing and create no outbox row. A DELIVERY_UNKNOWN command is not retried automatically under any flag setting. A device that did receive it may still ACK; the ACK is recorded as a fact (R13) and does not imply NEXUS resent it.
- The API exposes `deliveryState = DELIVERY_UNKNOWN` additively (R21). Desired state is untouched.

<a id="r17"></a>
## R17. Telemetry inbox deduplication (D4)

- Telemetry may carry an optional `messageId` (text, 1 to 64 characters from `A-Z a-z 0-9 . _ : -`). **Deduplication happens only when a messageId is present.** Samples without one are never deduplicated, even if their timestamp, value and capability are identical.
- The key is `(source, device_id, message_id)` in `inbound_message_dedupe` with a unique constraint; `source` distinguishes the MQTT and HTTP entry points only for diagnostics and both share one key space per device.
- The dedupe insert (`INSERT ... ON CONFLICT DO NOTHING`) and the telemetry write are in the same transaction (T8). A duplicate stores no telemetry row, makes no Twin update and emits no event or transition. A duplicate is acknowledged to the broker and counted. A duplicate HTTP submission returns an **explicit duplicate/no-op response** (decision A5: a success status with an additive `duplicate: true` field and the original submission reference where available); it is never an error and never a second Twin transition.
- The same messageId with a different value is a conflicting duplicate: the first wins, nothing is stored and the conflict is logged and counted.
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
- **Rollback is drain-only:** setting the flag to false stops creating outbox rows but the dispatcher keeps draining existing QUEUED, RETRY_WAIT and IN_FLIGHT rows to a terminal state or expiry (at most one window). No row is stranded. Rollback never deletes outbox or history data.

<a id="r23"></a>
## R23. Migrations

Forward-only, checksummed, tested on a populated disposable database as well as an empty one:

| Migration | Content |
|---|---|
| V37 | `command_outbox`, constraints, indexes, update and delete guard triggers. **Delivered in P4.1.** Starts empty; backfills nothing. |
| V38 | **Delivered in P4.1 (safety corrections).** `command_outbox.device_id` becomes ON DELETE RESTRICT; BEFORE DELETE trigger on `devices` with stable messages `DEVICE_HAS_UNRESOLVED_COMMANDS` and `DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE`; check that a RETRY_WAIT row has an attempt left; claim-after-expiry rejection in the update trigger. Modifies no earlier migration. |
| V39 | `command_state_history`, append-only trigger, and the legacy DELIVERY_UNKNOWN backfill (R16). **Moved to slice P4.2** (it is needed only once acceptance writes history). |
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

## Remaining items after finalization

- **Margin and defaults:** the 10 second uncertainty budget and the retry, lease and cap defaults are provisional (A2, A3). They may be changed only by a contract amendment with updated tests.
- **Dispatcher publish wait:** P4.2 must use the delivery-token wait with the attempt timeout; the blocking client call waits up to 15 seconds (R12).
- **V37/V38 constraints still to be implemented by P4.2 (not enforced by the database):** payload completeness (`capability`, `command`, `value`; integer `timestamp`); expiry of a late-completing publish (an IN_FLIGHT row may still become PUBLISHED after `expires_at`); lease ownership and fencing (a same-state update can change `lease_owner`; fencing lives in the dispatcher's conditional updates); selecting only unexpired rows; exhaustion classification (the database now refuses to park an exhausted row but does not choose RETRY_EXHAUSTED for it); `TRUNCATE` is outside the guards.
- **Device-deletion relaxation:** deleting resolved delivery history is refused until retention purges it (R30); relaxing that needs the state history of V39 and a decision.
- **Strict FIFO:** not claimed until implemented and proven (A4, scenario P4-018). V37 guarantees only a single IN_FLIGHT per device, now with a proved concurrent-claim outcome (P4-133).
- **Response shape of a duplicate telemetry submission:** the `duplicate: true` field is fixed in the P4.5 ticket (A5).
