# Phase 4 acceptance ledger

Date: 2026-10-15.

Status: **P4.0 FINALIZED; P4.1 IMPLEMENTED (uncommitted). 142 scenario IDs defined; 124 PLANNED, 17 COVERED, 1 DEFERRED. P4.1 is implemented; only scenarios fully proven by P4.1 tests are COVERED.**

Normative requirements: [reliability-contract.md](reliability-contract.md). Execution and dependency plan: [p4-implementation-ticket.md](p4-implementation-ticket.md). This ledger owns testable scenarios and evidence status; it does not redefine the contract.

## Status discipline

- PLANNED: the required scenario or test has not been implemented and verified. Every scenario starts here.
- COVERED: only after the exact automated test and its assertions have been inspected and a successful result is recorded in the "Exact test" and "Notes" columns.
- DEFERRED: an explicitly approved deferral with reason and milestone; it is not a pass.
- Failed or incomplete evidence stays visible and is never counted complete. A planned test or a passing suite does not close an untested scenario. Assertions, timeouts, isolation and skips must not be weakened to obtain green results.
- Evidence comes only from disposable PostgreSQL and Mosquitto. The development database is never queried, migrated or repaired.

## Totals

| Status | Count |
|---|---|
| PLANNED | 124 |
| COVERED | 17 |
| DEFERRED | 1 |
| **Total** | **142** |


## A. Atomic acceptance and persistence

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-001 | Acceptance and outbox commit atomically | PostgreSQL integration | One transaction commits ActionHistory ACCEPTED_FOR_DISPATCH, desired state, desired transition, verification initialisation, outbox row QUEUED and state-history ACCEPTED and QUEUED; all exist after commit. | R07, R08 | P4.2 | PLANNED | not yet written |
| P4-002 | Rollback after outbox insert leaves nothing | PostgreSQL integration | Failure injected after the outbox insert: no ActionHistory, desired state, transition, outbox or state-history row survives and nothing is published. | R07, R08 | P4.2 | PLANNED | not yet written |
| P4-003 | Outbox insert failure aborts acceptance | PostgreSQL integration | A constraint violation on the outbox insert fails the whole acceptance; the caller receives a rejection and no desired intent is stored. | R08 | P4.2 | PLANNED | not yet written |
| P4-004 | Acceptance performs no MQTT I/O | PostgreSQL + Mosquitto integration | With the broker stopped, acceptance still commits and the command is QUEUED; publication is never attempted inside the acceptance transaction. | R07, R08 | P4.2 | PLANNED | not yet written |
| P4-005 | Payload is frozen at enqueue | PostgreSQL integration | The stored payload text and timestamp equal the bytes later published; the timestamp is the acceptance-time epoch second, not the publish time. | R11 | P4.2 | PLANNED | not yet written |
| P4-006 | Payload hash integrity enforced | PostgreSQL integration | A stored SHA-256 must match the payload; a mismatch is a permanent failure recorded as PAYLOAD_INTEGRITY and never published. | R11, R12 | P4.2 | PLANNED | partial (P4.1): the database rejects a wrong or malformed hash (CommandOutboxSchemaTest.aCorrectPayloadHashIsAcceptedAndAWrongOneIsRejected, ...aHashThatIsNotLowercaseHexIsRejected) and OutboxModelTest.aTamperedPayloadFailsVerification; the dispatcher refusal as PAYLOAD_INTEGRITY is not implemented |
| P4-007 | One outbox row per command | PostgreSQL integration | The command id is the primary key; a second row for the same command is rejected by the database. | R04 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSchemaTest.thereIsExactlyOneOutboxRowPerCommand; ...anOutboxRowMustReferenceAnExistingActionHistoryCommand |
| P4-008 | Outbox constraints enforce a coherent state | PostgreSQL integration | Check constraints reject unknown states, attempts above the maximum, lease fields inconsistent with state and a terminal state without a terminal reason. | R04, R05 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSchemaTest.anUnknownStateIsRejected; ...leaseMetadataExistsIfAndOnlyIfTheRowIsInFlight; ...terminalRowsRecordTheirOutcomeCoherently; ...attemptLimitsAreBoundedByTheDatabase; ...aQueuedRowNeedsItsEligibilityTime |
| P4-009 | State history is append-only | PostgreSQL integration | An UPDATE of a state-history row is rejected by the database; the repository exposes no update. | R06 | P4.2 | PLANNED | not yet written |
| P4-010 | State-history sequence is gapless under concurrency | Concurrency (multiple connections / instances) | Concurrent appends for one command receive unique, increasing sequence numbers with no gaps and no duplicates. | R06 | P4.2 | PLANNED | not yet written |
| P4-011 | Rule-origin commands keep their gates | PostgreSQL integration | A paused rule, stale version or stale epoch produces no outbox row and no accepted command; the Phase 3 guard order is unchanged. | R08, R24 | P4.2 | PLANNED | not yet written |
| P4-012 | Outbox timestamp is generated at acceptance only in outbox mode | PostgreSQL integration | With the flag ON the payload timestamp is fixed at acceptance; with the flag OFF it is generated at publish exactly as today. | R11, R22 | P4.2 | PLANNED | not yet written |

## B. Claim, lease and concurrency

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-013 | Concurrent workers claim disjoint rows | Concurrency (multiple connections / instances) | With 2 to 4 workers and 50 queued commands each command is claimed once per attempt; no row is claimed by two workers. | R09 | P4.2 | PLANNED | not yet written |
| P4-014 | No double IN_FLIGHT across instances | Concurrency (multiple connections / instances) | Two application instances with in-process caches and locks disabled never hold the same command IN_FLIGHT at the same time. | R09 | P4.2 | PLANNED | not yet written |
| P4-015 | Claim records lease owner, expiry and attempt | PostgreSQL integration | A claim sets IN_FLIGHT, lease owner, lease expiry from the database clock and increments the attempt counter in a short committed transaction before any MQTT I/O. | R07, R09 | P4.2 | PLANNED | not yet written |
| P4-016 | Stale worker is fenced out | Concurrency (multiple connections / instances) | A worker whose lease expired or was reassigned cannot write an outcome; the conditional update affects zero rows and state does not regress. | R09 | P4.2 | PLANNED | not yet written |
| P4-017 | Expired lease is recovered as an uncertain attempt | Fault injection | An IN_FLIGHT row with an expired lease is recorded as ATTEMPT_UNCERTAIN and moves to RETRY_WAIT or DEAD_LETTER according to attempts and expiry remaining. | R09, R10, R15 | P4.2 | PLANNED | not yet written |
| P4-018 | Older unresolved commands are not overtaken (FIFO claimed only if proven) | Concurrency (multiple connections / instances) | Only one command per device is IN_FLIGHT (V37 unique index) and an older unresolved command of a device is claimed before a newer one under concurrent workers. Strict per-device FIFO is stated as a guarantee only if this scenario is COVERED; otherwise it remains a requirement. | R09 | P4.2 | PLANNED | not yet written |
| P4-019 | Other devices are not blocked | Concurrency (multiple connections / instances) | A slow or failing command for device A does not delay delivery for device B. | R09, R18 | P4.2 | PLANNED | not yet written |
| P4-020 | Database clock governs claims | Fault injection | An instance with a skewed application clock claims, leases and expires rows consistently because comparisons use the database clock. | R09 | P4.2 | PLANNED | not yet written |
| P4-021 | Claim batch size is bounded | PostgreSQL integration | A worker never claims more rows than the configured batch size. | R09, R18 | P4.2 | PLANNED | not yet written |
| P4-022 | In-process nudge does not bypass claiming | PostgreSQL integration | The post-commit nudge only triggers the normal claim query; it cannot publish a command that is not leased. | R09 | P4.2 | PLANNED | not yet written |
| P4-023 | Graceful shutdown loses no state | Fault injection | Stopping the dispatcher during an attempt either completes the outcome or leaves a lease that expires and is recovered; no row is stranded. | R09, R15 | P4.2 | PLANNED | not yet written |

## C. Publication, duplicates and uncertain outcomes

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-024 | Confirmed publication is recorded atomically | PostgreSQL + Mosquitto integration | After the broker completes a QoS 1 publish, one transaction sets the outbox row PUBLISHED, records PUBLISHED in state history and conditionally updates ActionHistory to SUCCESS with published_at and the ACK deadline. | R07, R10 | P4.2 | PLANNED | not yet written |
| P4-025 | ACK that arrives before the publish record is preserved | PostgreSQL + Mosquitto integration | An ACK processed before the publish outcome transaction is kept; the later outcome does not erase it. | R13 | P4.2 | PLANNED | not yet written |
| P4-026 | Transient publish failure schedules a retry | PostgreSQL + Mosquitto integration | A broker disconnect yields ATTEMPT_FAILED_TRANSIENT and RETRY_WAIT with the same command id and payload. | R10, R12 | P4.3 | PLANNED | not yet written |
| P4-027 | Uncertain publish outcome is recorded honestly | Fault injection | A timeout or connection loss during publish is recorded as ATTEMPT_UNCERTAIN, never as a definite failure, and a retry may duplicate the message. | R10 | P4.3 | PLANNED | not yet written |
| P4-028 | Duplicate publication is visible and safe | PostgreSQL + Mosquitto integration | Republishing a command yields two broker messages with identical bytes; the backend records exactly one first-ACK fact and one lifecycle. | R03, R10, R13 | P4.3 | PLANNED | not yet written |
| P4-029 | Simulator tolerates a duplicate command | Simulator (pytest) | The simulator applies an absolute-state command twice with the same final state and ACKs each receipt; evidence is simulator-only, not firmware. | R26 | P4.3 | PLANNED | not yet written |
| P4-030 | Every attempt publishes identical bytes | PostgreSQL + Mosquitto integration | A broker capture of all attempts shows identical payload bytes including commandId and timestamp. | R11, R12 | P4.3 | PLANNED | not yet written |
| P4-031 | Delivery does not re-check connectivity or authorisation | PostgreSQL integration | The dispatcher does not re-evaluate device connectivity, rule lifecycle or any acceptance guard; it only delivers the frozen payload within its window. | R08, R24 | P4.2 | PLANNED | not yet written |
| P4-032 | Each attempt outcome is recorded once | Concurrency (multiple connections / instances) | Duplicate or late completion callbacks for one attempt create one state-history outcome and do not double-count attempts. | R06, R10 | P4.3 | PLANNED | not yet written |
| P4-033 | PUBLISHED means broker publication only | API (MockMvc) | The API and labels describe PUBLISHED as broker publication; no field or text claims device receipt, convergence or physical effect. | R02, R21 | P4.7 | PLANNED | not yet written |

## D. Retry, expiry and dead letter

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-034 | Backoff schedule is deterministic and bounded | Unit | With an injected clock and random source the delays follow the configured initial, multiplier, cap and jitter bounds. | R12 | P4.3 | PLANNED | not yet written |
| P4-035 | Maximum attempts is enforced | PostgreSQL integration | After the configured maximum attempts the command becomes DEAD_LETTER RETRY_EXHAUSTED and no further attempt is made. | R12 | P4.3 | PLANNED | not yet written |
| P4-036 | Expiry stops attempts | PostgreSQL integration | No attempt starts at or after expires_at; the sweep moves the row to DEAD_LETTER EXPIRED. | R11, R12 | P4.3 | PLANNED | not yet written |
| P4-037 | Window above the firmware-compatible ceiling is refused | Unit | Configuration with a delivery window greater than the ceiling derived from the 30 second firmware freshness bound minus the clock-uncertainty margin fails application startup. | R11 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSettingsTest.theWindowAboveTheFirmwareDerivedCeilingIsRejected; ...anInvalidConfigurationStopsApplicationStartup; CommandOutboxSchemaTest.theDeliveryWindowIsPositiveAndNeverExceedsTwentySeconds; OutboxModelTest.firmwareFreshnessLimitsDeriveTheTwentySecondCeiling |
| P4-038 | Schedule must fit the window | Unit | Configuration whose worst-case retry schedule exceeds the window is rejected. | R11, R12 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSettingsTest.theRetryScheduleMustFitTheWindow; ...theDefaultWorstCaseScheduleFitsInsideTheWindow |
| P4-039 | Permanent failures are not retried | Unit | Serialisation or payload-integrity errors go straight to DEAD_LETTER PERMANENT_FAILURE. | R12 | P4.3 | PLANNED | not yet written |
| P4-040 | Failure classification is explicit | Unit | A table-driven test maps each client exception type to transient, uncertain or permanent. | R10, R12 | P4.3 | PLANNED | not yet written |
| P4-041 | Dead letter updates the lifecycle truthfully | PostgreSQL integration | ActionHistory records a failure with the reason, verification is told once, and the desired state is not cleared. | R12, R24 | P4.3 | PLANNED | not yet written |
| P4-042 | Terminal publication failure emits one rule fact | PostgreSQL integration | The COMMAND_PUBLICATION_FAILED fact is emitted once on the terminal outcome only, with fail-closed behaviour unchanged. | R12, R24 | P4.3 | PLANNED | not yet written |
| P4-043 | Dead letters are never resent automatically | Fault injection | No scheduled job or restart resends a DEAD_LETTER command, including one that expired. | R12, R21 | P4.3 | PLANNED | not yet written |
| P4-044 | Retries do not reauthorise | PostgreSQL integration | Across all retry attempts RuleExecutionAuthorization and the acceptance service are invoked zero times; a rule paused after acceptance still delivers within the window. | R08, R24 | P4.3 | PLANNED | not yet written |
| P4-045 | Uncertain-then-expired is labelled honestly | PostgreSQL integration | The reason UNCERTAIN_EXPIRED and the message DELIVERY_UNCONFIRMED are stored; a later ACK is still recorded. | R10, R12, R13 | P4.3 | PLANNED | not yet written |
| P4-046 | Removing the attempt cap or expiry check fails a test | Mutation check | Mutating the cap and the expiry guard each makes the corresponding scenarios fail; the mutation is reverted and recorded. | R12, R29 | P4.8 | PLANNED | not yet written |

## E. ACK, timeout and late facts

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-047 | Accepted ACK is recorded | PostgreSQL integration | ACK_ACCEPTED is appended with the guarded ACK update; the API status projection remains ACKNOWLEDGED. | R13 | P4.4 | PLANNED | not yet written |
| P4-048 | Rejected ACK is recorded once | PostgreSQL integration | ACK_REJECTED is appended; the existing rule fact and verification failure occur exactly once. | R13, R24 | P4.4 | PLANNED | not yet written |
| P4-049 | Duplicate identical ACK changes nothing | PostgreSQL integration | A repeated identical ACK creates no state-history row and no second rule fact; a duplicate counter increments. | R13 | P4.4 | PLANNED | not yet written |
| P4-050 | Conflicting duplicate ACK preserves the first | PostgreSQL integration | A second ACK with a different status is ignored, the first is preserved and ACK_CONFLICT_IGNORED is appended for audit. | R13 | P4.4 | PLANNED | not yet written |
| P4-051 | ACK validation behaviour is unchanged | PostgreSQL integration | Device mismatch, unknown command id and malformed ACKs keep the existing acknowledge-and-drop handling. | R13, R24 | P4.4 | PLANNED | not yet written |
| P4-052 | ACK timeout is persisted once | Concurrency (multiple connections / instances) | After ack_deadline_at passes with no ACK the scanner appends ACK_TIMED_OUT exactly once across repeated scans and two instances. | R14 | P4.4 | PLANNED | not yet written |
| P4-053 | ACK deadline is anchored at first confirmed publication | PostgreSQL integration | Retries and duplicate publications never move or extend the deadline. | R14 | P4.4 | PLANNED | not yet written |
| P4-054 | Late ACK after timeout is added, not erased | PostgreSQL integration | The ACK is recorded and ACK_AFTER_TIMEOUT appended; history shows both facts and the projection is ACKNOWLEDGED as today. | R13, R14 | P4.4 | PLANNED | not yet written |
| P4-055 | Late ACK after dead letter is recorded | PostgreSQL integration | ACK_AFTER_TERMINAL is appended; nothing is silently dropped. | R13 | P4.4 | PLANNED | not yet written |
| P4-056 | Existing rule ACK-timeout scanner is unaffected | PostgreSQL integration | RuleAckTimeoutScanner still emits its trigger fact once; the new scanner creates no second fact. | R14, R24 | P4.4 | PLANNED | not yet written |
| P4-057 | ACK latency uses backend timestamps | PostgreSQL integration | ACK latency equals ack_backend_received_at minus published_at; device timestamps are never used for latency. | R13, R20 | P4.7 | PLANNED | not yet written |
| P4-058 | Convergence latency uses backend timestamps | PostgreSQL integration | Convergence latency is computed from backend acceptance and verification times. | R20 | P4.7 | PLANNED | not yet written |

## F. Crash recovery and restart

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-059 | Kill after acceptance, before claim | Fault injection | The backend is killed after the acceptance commit; after restart the command is published exactly once to the broker. | R15 | P4.8 | PLANNED | not yet written |
| P4-060 | Kill after claim, before publish | Fault injection | The lease expires, the row is recovered and published; attempts are counted honestly. | R09, R15 | P4.8 | PLANNED | not yet written |
| P4-061 | Kill after publish, before the outcome transaction | Fault injection | Recovery records an uncertain attempt; any retry may duplicate the message; the end state and ACK correlation are consistent. | R10, R15 | P4.8 | PLANNED | not yet written |
| P4-062 | Kill during the outcome transaction | Fault injection | A rolled-back outcome leaves no partial state; recovery completes the command. | R07, R15 | P4.8 | PLANNED | not yet written |
| P4-063 | Pending retry survives restart and honours expiry | Fault injection | RETRY_WAIT rows keep their schedule; a row whose window elapsed during downtime becomes DEAD_LETTER EXPIRED and is not published. | R11, R15 | P4.8 | PLANNED | not yet written |
| P4-064 | Restart storm is bounded | Fault injection | Repeated kills never exceed maximum attempts and never duplicate state-history sequence numbers. | R12, R15 | P4.8 | PLANNED | not yet written |
| P4-065 | ACK timeout survives restart | Fault injection | A deadline that passed during downtime is persisted as ACK_TIMED_OUT after restart. | R14, R15 | P4.8 | PLANNED | not yet written |
| P4-066 | Database outage during an attempt | Fault injection | Connection loss at the outcome write is recovered by lease expiry; the command is not lost. | R09, R15 | P4.8 | PLANNED | not yet written |
| P4-067 | Broker outage longer than the window | Fault injection | The command becomes DEAD_LETTER EXPIRED and is visible; no stale command is published after recovery. | R11, R12 | P4.8 | PLANNED | not yet written |

## G. Legacy reconciliation

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-068 | Migration marks pre-V37 accepted commands DELIVERY_UNKNOWN | Migration on populated database | ACCEPTED_FOR_DISPATCH commands present when V39 runs receive a DELIVERY_UNKNOWN history fact and no outbox row. | R16, R23 | P4.2 | PLANNED | not yet written |
| P4-069 | Reconciler never publishes | PostgreSQL + Mosquitto integration | A spy on the MQTT client shows zero publications for DELIVERY_UNKNOWN commands across restarts and with the flag ON. | R16 | P4.4 | PLANNED | not yet written |
| P4-070 | Runtime reconciler is idempotent and conservative | PostgreSQL integration | Stranded flag-OFF-era ACCEPTED_FOR_DISPATCH rows older than the grace period are marked once; rows with an outbox row are never touched. | R16 | P4.4 | PLANNED | not yet written |
| P4-071 | Commands mid-dispatch are not falsely marked | PostgreSQL integration | A legacy-path command inside the grace period is not marked DELIVERY_UNKNOWN. | R16 | P4.4 | PLANNED | not yet written |
| P4-072 | Historical terminal rows are unchanged | Migration on populated database | Published, acknowledged and failed rows keep identical values and counts across V37 to V40. | R23 | P4.5 | PLANNED | partial (P4.1): CommandOutboxUpgradeMigrationTest proves V37 leaves every historical row identical; V39 and V40 outstanding |
| P4-073 | DELIVERY_UNKNOWN projection is additive | API (MockMvc) | deliveryState reports DELIVERY_UNKNOWN while the existing status field keeps its prior meaning. | R21 | P4.7 | PLANNED | not yet written |
| P4-074 | Late ACK for DELIVERY_UNKNOWN is recorded truthfully | PostgreSQL integration | If the device had received the command, its ACK is recorded as a fact without implying NEXUS resent it. | R13, R16 | P4.4 | PLANNED | not yet written |

## H. Telemetry inbox deduplication

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-075 | Redelivered messageId is processed once | PostgreSQL + Mosquitto integration | The same messageId delivered twice stores one telemetry row, makes one Twin update and emits no new event or transition. | R17 | P4.5 | PLANNED | not yet written |
| P4-076 | Dedupe row and telemetry commit atomically | Fault injection | A rollback or crash leaves neither a dedupe row nor a telemetry row; they commit together. | R07, R17 | P4.5 | PLANNED | not yet written |
| P4-077 | Messages without messageId are never deduplicated | PostgreSQL integration | Two samples with identical timestamp and value but no messageId are both stored. | R17 | P4.5 | PLANNED | not yet written |
| P4-078 | MessageId is scoped by device | PostgreSQL integration | The same messageId from two devices is stored twice. | R17 | P4.5 | PLANNED | not yet written |
| P4-079 | Conflicting duplicate keeps the first | PostgreSQL integration | Same messageId with a different value stores nothing new, logs and counts a conflict. | R17 | P4.5 | PLANNED | not yet written |
| P4-080 | Invalid messageId is rejected | API (MockMvc) | A messageId that is non-text, too long or outside the allowed characters is treated as invalid input (HTTP 400, MQTT acknowledge and drop). | R17 | P4.5 | PLANNED | not yet written |
| P4-081 | Crash before the MQTT acknowledgement is deduplicated | PostgreSQL + Mosquitto integration | A message processed but not acknowledged to the broker is redelivered after restart and suppressed. | R17 | P4.5 | PLANNED | not yet written |
| P4-082 | Dedupe window purge is bounded and documented | PostgreSQL integration | Expired ids are purged without touching telemetry; a purged id is accepted again and this limit is documented. | R17, R19 | P4.5 | PLANNED | not yet written |
| P4-083 | HTTP ingestion uses the same semantics | API (MockMvc) | POST /api/v1/telemetry with a repeated messageId returns an explicit duplicate/no-op response (success status, additive duplicate field), stores nothing and causes no second Twin transition; the MQTT path behaves identically. | R17, R21 | P4.5 | PLANNED | not yet written |
| P4-084 | Onboarding telemetry is not double-counted | PostgreSQL integration | A duplicate first onboarding reading neither advances the session twice nor activates the device twice. | R17, R24 | P4.5 | PLANNED | not yet written |
| P4-085 | Concurrent duplicates store once | Concurrency (multiple connections / instances) | Two threads submitting one messageId store exactly one row via the unique constraint. | R17 | P4.5 | PLANNED | not yet written |
| P4-086 | Hypertable is untouched | Migration on populated database | V40 creates only the dedupe table; telemetry_history schema and the V1 to V36 checksums are unchanged. | R17, R23 | P4.5 | PLANNED | not yet written |

## I. Backpressure and rate limiting

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-087 | Global queue cap rejects new manual commands | PostgreSQL integration | Above the cap a typed rejection is returned before any ActionHistory, desired state or outbox row is written. | R18 | P4.6 | PLANNED | not yet written |
| P4-088 | Per-device queue cap | PostgreSQL integration | Above the device cap a typed rejection occurs and other devices are unaffected. | R18 | P4.6 | PLANNED | not yet written |
| P4-089 | Rule-origin command over the cap is suppressed visibly | PostgreSQL integration | A recorded suppression with a reason is stored, nothing is dispatched and no desired state is written. | R18, R24 | P4.6 | PLANNED | not yet written |
| P4-090 | Publish rate limit bounds publications | PostgreSQL + Mosquitto integration | Publications per second never exceed the configured limit; excess commands wait and expire visibly rather than burst. | R18 | P4.6 | PLANNED | not yet written |
| P4-091 | Cap checks are race-safe | Concurrency (multiple connections / instances) | Concurrent acceptances cannot exceed the cap because the count is checked under the device lock. | R18 | P4.6 | PLANNED | not yet written |
| P4-092 | No new unbounded in-memory queue | Validation gate / audit | An architecture test and review confirm that ingestion and dispatch add no unbounded in-memory queue; ingestion lag is exported as a metric. | R18 | P4.6 | PLANNED | not yet written |
| P4-093 | Flood is bounded | Fault injection | A command flood keeps memory and table growth within limits and every command reaches PUBLISHED, DEAD_LETTER or a typed rejection within its window. | R18, R12 | P4.8 | PLANNED | not yet written |

## J. Traceability, API and access

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-094 | Every transition is recorded with a backend timestamp | PostgreSQL integration | Each outbox state change appends a state-history row with correlation id, source and backend time. | R06, R20 | P4.7 | PLANNED | not yet written |
| P4-095 | Pipeline trace steps exist per transition | PostgreSQL integration | Transitions create pipeline trace steps through the existing trace mechanism. | R20 | P4.7 | PLANNED | not yet written |
| P4-096 | One correlation id joins acceptance to ACK | PostgreSQL integration | Rows from acceptance, attempts, publication and ACK share the same correlation id. | R20 | P4.7 | PLANNED | not yet written |
| P4-097 | Lifecycle read API is additive | API (MockMvc) | GET lifecycle returns state history and deliveryState; existing action and command endpoints keep their schema and values. | R21 | P4.7 | PLANNED | not yet written |
| P4-098 | No mutating requeue endpoint exists | API (MockMvc) | POST, PUT and DELETE on any requeue or dead-letter path return 404 or 405; no HTTP or MQTT route can resend a command. | R21 | P4.7 | PLANNED | not yet written |
| P4-099 | No internal requeue is reachable | Validation gate / audit | An architecture test shows no controller, listener or scheduler depends on a requeue operation; the operation is absent or disabled. | R21 | P4.7 | PLANNED | not yet written |
| P4-100 | Metrics use fixed low-cardinality tags | Unit | Tag values come only from fixed reason and state codes, never identifiers. | R20 | P4.7 | PLANNED | not yet written |
| P4-101 | API does not widen payload exposure | API (MockMvc) | Responses expose no more command parameters than the existing ActionHistory views. | R21 | P4.7 | PLANNED | not yet written |
| P4-102 | Frontend labels facts separately | Frontend (Vitest) | The UI shows queued, published, acknowledged and verified as separate labels and never states physical actuation. | R02, R21 | P4.7 | PLANNED | not yet written |
| P4-103 | Timed-out and dead-lettered commands are visible | API (MockMvc) | The lifecycle API and the Actions view list ACK_TIMED_OUT, DEAD_LETTER and DELIVERY_UNKNOWN commands with their reason codes and backend timestamps. | R14, R20, R21 | P4.7 | PLANNED | not yet written |
| P4-104 | Frontend tolerates unknown delivery states | Frontend (Vitest) | An unrecognised deliveryState renders a neutral label without throwing. | R21 | P4.7 | PLANNED | not yet written |

## K. Feature flag, migration and compatibility

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-105 | Outbox flag defaults to OFF | Unit | The default configuration and the shipped application.yml have the outbox disabled. | R22 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSettingsTest.theShippedApplicationYmlLeavesTheOutboxDisabledWithTheDocumentedDefaults; CommandOutboxPersistenceIntegrationTest.theOutboxFeatureFlagIsOffInTheRunningApplication |
| P4-106 | Flag OFF is byte-for-byte legacy behaviour | PostgreSQL integration | With the flag OFF no outbox or state-history rows are created for commands and the legacy publish path is unchanged. | R22, R24 | P4.2 | PLANNED | not yet written |
| P4-107 | Rollback drains the queue | Fault injection | Turning the flag OFF with queued rows lets the dispatcher drain them to a terminal state or expiry; none is stranded. | R22 | P4.8 | PLANNED | not yet written |
| P4-108 | Migrations are forward-only and checksum-safe | Migration on populated database | V37 to V40 apply on a populated disposable database; V1 to V36 checksums are asserted unchanged. | R23 | P4.5 | PLANNED | partial (P4.1): V37 proven by CommandOutboxUpgradeMigrationTest (V1 to V36 checksums unchanged, populated upgrade); V39 and V40 outstanding |
| P4-109 | Mixed historical rows migrate correctly | Migration on populated database | A database containing ACCEPTED_FOR_DISPATCH, PUBLISHED, acknowledged and failed commands migrates with the expected outcome for each. | R16, R23 | P4.2 | PLANNED | partial (P4.1): CommandOutboxUpgradeMigrationTest shows mixed rows unchanged and no outbox row created by V37; the V39 backfill is not implemented |
| P4-110 | Phase 3 regression with the flag OFF | Validation gate / audit | The complete Phase 3 backend suite passes unchanged with no weakened assertion. | R24, R29 | P4.8 | PLANNED | not yet written |
| P4-111 | Phase 3 regression with the flag ON | Validation gate / audit | The Phase 3 rule dispatch, arbitration and plan outcome suites pass with outbox delivery; candidate outcomes are not rewritten by retries. | R24, R29 | P4.8 | PLANNED | not yet written |
| P4-112 | Two instances deliver once per command | Concurrency (multiple connections / instances) | Two backend instances with the flag ON produce one confirmed publication path per command. | R09 | P4.8 | PLANNED | not yet written |
| P4-113 | Dry-run and explanation remain zero-effect | PostgreSQL integration | Fingerprints of outbox, state-history and dedupe tables are unchanged by dry-run and explanation requests. | R24 | P4.8 | PLANNED | not yet written |
| P4-114 | Explanations stay truthful about delivery state | PostgreSQL integration | An explanation of a command that is only QUEUED or DEAD_LETTER does not claim publication or ACK. | R02, R24 | P4.7 | PLANNED | not yet written |
| P4-115 | Enablement procedure is documented and checkable | Validation gate / audit | The documented checklist can be run as a script against a disposable environment and fails when a prerequisite is missing. | R22 | P4.8 | PLANNED | not yet written |
| P4-116 | Validation gate includes Phase 4 checks | Validation gate / audit | validate_nexus.py --full runs the Phase 4 crash-recovery smoke test; counts, skips and durations are recorded. | R27, R29 | P4.8 | PLANNED | not yet written |

## L. Simulator, protocol and documentation

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-117 | Simulator emits an optional messageId | Simulator (pytest) | Each simulated telemetry publication carries a new UUID messageId; payloads remain valid for the existing contract. | R25 | P4.5 | PLANNED | not yet written |
| P4-118 | Simulator can re-send an identical message | Simulator (pytest) | A test hook republishes the same payload and messageId to model broker redelivery. | R25 | P4.5 | PLANNED | not yet written |
| P4-119 | Firmware directory is unchanged | Validation gate / audit | git diff over firmware/** and hardware-tests/** is empty for every Phase 4 slice. | R25 | P4.8 | PLANNED | not yet written |
| P4-120 | Legacy payloads without messageId stay accepted | PostgreSQL integration | A telemetry payload exactly as the committed firmware sends it is accepted and stored unchanged. | R17, R25 | P4.5 | PLANNED | not yet written |
| P4-121 | Firmware freshness semantics are modelled without changing the simulator | Unit | A test-side helper models the firmware check (absolute difference above 30 seconds is STALE) and the delivery window is verified against it; simulator behaviour, including its 300 second default, is unchanged. | R11, R25 | P4.3 | PLANNED | not yet written |
| P4-122 | Window arithmetic at the freshness boundary | Unit | With an injected clock a command published at the window edge is accepted by a 30 second device check allowing the margin, and one past the ceiling is never produced. | R11 | P4.3 | PLANNED | partial (P4.1): OutboxModelTest.firmwareFreshnessLimitsDeriveTheTwentySecondCeiling proves the ceiling arithmetic; the boundary test against the firmware model is P4.3 |
| P4-123 | Documents never claim exactly-once physical actuation | Validation gate / audit | A documentation audit over Phase 4 documents, API descriptions and UI strings finds no exactly-once or physical-actuation guarantee. | R03, R29 | P4.8 | PLANNED | not yet written |

## M. Deferred by decision D5

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-124 | General domain-event outbox | Design decision (no test) | Deferred: inspection found no unrecovered requirement (see contract R28). No event-outbox table or dispatcher is created in Phase 4. Re-open only if a specific lost-notification requirement is identified. | R28 | none | DEFERRED | decision D5, no test |

## N. Safety corrections after the P4.1 forensic review (F1 device deletion, F2 retention, F7 V37 integrity)

| ID | Scenario | Intended layer | Required assertion / evidence | Clauses | Slice | Status | Exact test |
|---|---|---|---|---|---|---|---|
| P4-125 | Device deletion is refused while any obligation is unresolved | PostgreSQL integration | A device with a QUEUED, IN_FLIGHT or RETRY_WAIT obligation cannot be deleted by direct SQL, by the service or by the REST API; the rejection is typed (DEVICE_HAS_UNRESOLVED_COMMANDS, HTTP 409 with a stable code) and the obligation is untouched. | R30, R04, R19 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSafetySchemaTest.aDeviceWithAnUnresolvedObligationCannotBeDeletedInAnyUnresolvedState; CommandOutboxSchemaTest.removingTheDeviceIsRefusedWhileItHasADeliveryObligation; CommandOutboxDeviceDeletionApiIntegrationTest.theServiceRefusesWhileAnObligationIsUnresolvedAndNothingChanges; ...theRestApiAnswers409WithAStableCodeForAnUnresolvedObligation |
| P4-126 | Resolved delivery records block device deletion until retention purges them | PostgreSQL integration | With only PUBLISHED or DEAD_LETTER rows the deletion is refused (DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE) because no durable evidence mirror exists yet; after the resolved rows are purged the device is deleted; a device with no outbox row deletes exactly as before. | R30, R19 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSafetySchemaTest.resolvedDeliveryRecordsAlsoBlockDeletionUntilRetentionPurgesThem; ...aDeviceWithoutDeliveryRecordsIsStillDeletable; CommandOutboxDeviceDeletionApiIntegrationTest.resolvedDeliveryRecordsAreRefusedWithTheirOwnCodeUntilRetentionPurgesThem; ...aDeviceWithoutDeliveryRecordsStillDeletesNormally; ...anUnknownDeviceIsStillNotFound |
| P4-127 | A refused device deletion changes nothing and rolls back | PostgreSQL integration | ActionHistory, desired state, verification evidence and the obligation are untouched; a refused statement rolls back its whole transaction; the foreign key is RESTRICT so removing the trigger would still not allow a cascade. | R30, R04 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSafetySchemaTest.aRefusedDeletionPreservesActionHistoryDesiredStateAndVerificationEvidence; ...aRefusedDeletionInsideATransactionRollsBackTheWholeTransaction; ...aCascadeFromOtherPathsCannotBypassTheGuardEither |
| P4-128 | Device deletion races are serialised by the database | Concurrency (multiple connections / instances) | An acceptance that holds the key-share lock makes a concurrent deletion wait and then be refused; a deletion that commits first makes a later acceptance fail on the foreign key; a deletion racing a claim or a resolution is refused (fail closed). | R30, R09 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSafetySchemaTest.anAcceptanceThatCommitsBeforeADeviceDeletionWinsAndTheDeletionIsRefused; ...aDeviceDeletionThatCommitsFirstMakesALaterAcceptanceFailInsteadOfLeavingAnOrphan; ...deletingWhileAnotherTransactionClaimsOrResolvesTheRowIsNeverAllowed |
| P4-129 | Retention retains rows referenced by current desired state and clears no reference | PostgreSQL integration | An old action_history row referenced by current_desired_twin_state remains, the desired state and verification evidence stay intact, and eligible old rows are still deleted. | R19 | P4.1 | COVERED | com.nexus.action.outbox.RetentionIsolationIntegrationTest.referencedAndObligationRowsAreRetainedEligibleRowsAreDeletedAndNothingIsCleared; mutation check A (desired-state guard removed: 3 failures, restored) |
| P4-130 | Retention keeps rows with an outbox row and purges only resolved ones | PostgreSQL integration | Rows with QUEUED, IN_FLIGHT or RETRY_WAIT obligations remain with their action rows; resolved rows are purged and then their action rows; recent rows are never deleted. | R19, R04 | P4.1 | COVERED | com.nexus.action.outbox.RetentionIsolationIntegrationTest.referencedAndObligationRowsAreRetainedEligibleRowsAreDeletedAndNothingIsCleared; CommandOutboxPersistenceIntegrationTest.retentionNeverDestroysPendingOrInFlightObligationsNorTheirActionRows; ...retentionPurgesResolvedObligationsAndThenTheirActionRowsButKeepsRecentOnes |
| P4-131 | Retention categories are isolated and failures are logged, counted and surfaced | PostgreSQL integration | An induced failure in the action_history category does not roll back domain-event or other categories; the failure is logged at ERROR with its cause, counted and thrown by the scheduled entry point after all categories ran; removing the isolation or the desired-state guard fails a test. | R19, R29 | P4.1 | COVERED | com.nexus.action.outbox.RetentionIsolationIntegrationTest.aFailingCategoryIsLoggedAndSurfacedAndDoesNotRollBackTheOthers; mutation check B (single shared transaction: UnexpectedRollbackException, restored) |
| P4-132 | Retention is idempotent and survives a backend restart | PostgreSQL integration | After a fresh application context the retained records are intact and repeated retention runs delete nothing further and fail in no category. | R19, R15 | P4.1 | COVERED | com.nexus.action.outbox.RetentionIsolationIntegrationTest.afterABackendRestartTheRecordsArePreservedAndRepeatedRetentionIsIdempotent (fresh application context via DirtiesContext) |
| P4-133 | Concurrent IN_FLIGHT claims for one device serialise | Concurrency (multiple connections / instances) | Two concurrent claims of different rows of one device: the second waits on the unique index and fails with 23505 after the first commits, or proceeds if the first rolls back; many simultaneous claimers produce exactly one IN_FLIGHT row. | R04, R09 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSafetySchemaTest.twoConcurrentClaimsForOneDeviceSerialiseAndOnlyTheFirstSucceeds; ...whenTheFirstClaimRollsBackTheWaitingClaimProceeds; ...manyConcurrentClaimersOfOneDeviceNeverProduceMoreThanOneInFlightRow |
| P4-134 | A non-ASCII payload hashes identically in Java and PostgreSQL | PostgreSQL integration | A payload with accents, the euro sign and an emoji is accepted by the database hash check with the Java-computed hash and round-trips byte for byte; a fixed UTF-8 vector is verified independently. | R11, R04 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSafetySchemaTest.aNonAsciiPayloadHashesIdenticallyInJavaAndPostgreSql; OutboxModelTest.aNonAsciiPayloadHasTheIndependentlyVerifiedUtf8Hash |
| P4-135 | Attempt exhaustion cannot be parked or claimed again | PostgreSQL integration | The database refuses RETRY_WAIT for a row with no attempt left (it must become DEAD_LETTER), refuses a claim beyond max_attempts and never claims a dead letter. | R12, R04 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSafetySchemaTest.attemptExhaustionCannotBeParkedOrClaimedAgainAndMustBecomeADeadLetter |
| P4-136 | The database refuses to claim an expired row | PostgreSQL integration | A claim at or after expires_at is rejected; the row can still be dead-lettered. This is a backstop: expiry is not claimed to be enforced by a dispatcher, which does not exist yet. | R11, R12 | P4.1 | COVERED | com.nexus.action.outbox.CommandOutboxSafetySchemaTest.anExpiredRowCannotBeClaimedButCanStillBeDeadLettered |
| P4-137 | No direct command-publication path exists when the outbox flag is enabled | Validation gate / audit | With the flag on, every SEND_COMMAND passes acceptance and the outbox; CommandExecutor.execute and publishPrepared cannot be reached to publish, the engine cannot run without the coordinator, and an architecture test shows the MQTT client is reachable only from the dispatcher. | R31, R08 | P4.2 | PLANNED | not yet written |
| P4-138 | A merely queued command never reports SUCCESS | API (MockMvc) | The engine result, executed events, pipeline trace, realtime broadcast and the manual-command API response report ACCEPTED_FOR_DISPATCH with a delivery state for a queued command; SUCCESS only after broker publication. | R31, R02 | P4.2 | PLANNED | not yet written |
| P4-139 | State-history purge cannot block action-history retention | PostgreSQL integration | With V39 history rows (including DELIVERY_UNKNOWN rows that have no outbox row) the retention action_history category still deletes eligible rows and fails in no category. | R19, R06, R31 | P4.2 | PLANNED | not yet written |
| P4-140 | A completion after expiry is recorded as late and uncertain | PostgreSQL integration | An attempt that completes after expires_at is not recorded as a normal publish; it is recorded as late or uncertain and never extends the deadline or the window. | R11, R10, R31 | P4.3 | PLANNED | not yet written |
| P4-141 | Dispatcher, ACK handler and device removal cannot deadlock | Concurrency (multiple connections / instances) | Concurrent claim, outcome, ACK and device-deletion attempts never produce a lock-order deadlock: the dispatcher locks the outbox row then the action_history row and never the device row. | R31, R07 | P4.2 | PLANNED | not yet written |
| P4-142 | Payload completeness is validated before persistence | Unit | The builder guarantees capability, command, value and an integer timestamp before the row is written; the database proves only commandId, timestamp, topic and hash. | R31, R11 | P4.2 | PLANNED | not yet written |

## Coverage mapping

### By slice

| Slice | Scenario count | Scenario IDs |
|---|---|---|
| P4.1 | 17 | P4-007, P4-008, P4-037, P4-038, P4-105, P4-125, P4-126, P4-127, P4-128, P4-129, P4-130, P4-131, P4-132, P4-133, P4-134, P4-135, P4-136 |
| P4.2 | 32 | P4-001, P4-002, P4-003, P4-004, P4-005, P4-006, P4-009, P4-010, P4-011, P4-012, P4-013, P4-014, P4-015, P4-016, P4-017, P4-018, P4-019, P4-020, P4-021, P4-022, P4-023, P4-024, P4-025, P4-031, P4-068, P4-106, P4-109, P4-137, P4-138, P4-139, P4-141, P4-142 |
| P4.3 | 19 | P4-026, P4-027, P4-028, P4-029, P4-030, P4-032, P4-034, P4-035, P4-036, P4-039, P4-040, P4-041, P4-042, P4-043, P4-044, P4-045, P4-121, P4-122, P4-140 |
| P4.4 | 14 | P4-047, P4-048, P4-049, P4-050, P4-051, P4-052, P4-053, P4-054, P4-055, P4-056, P4-069, P4-070, P4-071, P4-074 |
| P4.5 | 17 | P4-072, P4-075, P4-076, P4-077, P4-078, P4-079, P4-080, P4-081, P4-082, P4-083, P4-084, P4-085, P4-086, P4-108, P4-117, P4-118, P4-120 |
| P4.6 | 6 | P4-087, P4-088, P4-089, P4-090, P4-091, P4-092 |
| P4.7 | 16 | P4-033, P4-057, P4-058, P4-073, P4-094, P4-095, P4-096, P4-097, P4-098, P4-099, P4-100, P4-101, P4-102, P4-103, P4-104, P4-114 |
| P4.8 | 20 | P4-046, P4-059, P4-060, P4-061, P4-062, P4-063, P4-064, P4-065, P4-066, P4-067, P4-093, P4-107, P4-110, P4-111, P4-112, P4-113, P4-115, P4-116, P4-119, P4-123 |
| none | 1 | P4-124 |

### By intended evidence layer

| Layer | Count |
|---|---|
| PostgreSQL integration | 61 |
| Fault injection | 17 |
| Concurrency (multiple connections / instances) | 14 |
| PostgreSQL + Mosquitto integration | 10 |
| Unit | 10 |
| API (MockMvc) | 9 |
| Validation gate / audit | 9 |
| Migration on populated database | 5 |
| Simulator (pytest) | 3 |
| Frontend (Vitest) | 2 |
| Mutation check | 1 |
| Design decision (no test) | 1 |

### Mandatory reliability semantics

| # | Requirement | Scenario count | Scenario IDs |
|---|---|---|---|
| M1 | MQTT QoS 1 plus outbox can duplicate publications (stated, tested, not hidden) | 8 | P4-013, P4-014, P4-027, P4-028, P4-049, P4-050, P4-061, P4-112 |
| M2 | No exactly-once physical actuation promised | 4 | P4-029, P4-121, P4-122, P4-123 |
| M3 | Same commandId, timestamp and payload on every retry | 11 | P4-005, P4-006, P4-007, P4-012, P4-026, P4-028, P4-030, P4-036, P4-037, P4-134, P4-142 |
| M4 | Acceptance, queue, publication, ACK, convergence and actuation kept distinct | 30 | P4-001, P4-004, P4-009, P4-024, P4-025, P4-033, P4-041, P4-045, P4-047, P4-048, P4-052, P4-053, P4-054, P4-055, P4-057, P4-058, P4-065, P4-067, P4-073, P4-074, P4-094, P4-095, P4-096, P4-097, P4-102, P4-103, P4-114, P4-123, P4-137, P4-138 |
| M5 | No automatic replay of pre-migration accepted commands | 9 | P4-043, P4-068, P4-069, P4-070, P4-071, P4-072, P4-073, P4-074, P4-109 |
| M6 | Acceptance and outbox persistence are atomic | 11 | P4-001, P4-002, P4-003, P4-004, P4-059, P4-087, P4-088, P4-089, P4-091, P4-125, P4-137 |
| M7 | Retries bounded, observable and restart-safe | 32 | P4-008, P4-010, P4-015, P4-016, P4-017, P4-018, P4-019, P4-020, P4-021, P4-022, P4-023, P4-026, P4-032, P4-034, P4-035, P4-036, P4-038, P4-039, P4-046, P4-059, P4-060, P4-062, P4-063, P4-064, P4-066, P4-067, P4-090, P4-092, P4-093, P4-107, P4-135, P4-136 |
| M8 | Uncertain publication results treated honestly | 11 | P4-006, P4-017, P4-027, P4-040, P4-041, P4-045, P4-050, P4-054, P4-055, P4-061, P4-140 |
| M9 | No reauthorisation during delivery retries | 6 | P4-011, P4-031, P4-042, P4-044, P4-089, P4-111 |
| M10 | No unauthenticated manual requeue path | 3 | P4-043, P4-098, P4-099 |
| M11 | Unresolved delivery obligations are never deleted (device deletion, retention, any path) | 5 | P4-125, P4-126, P4-127, P4-128, P4-130 |

### ROADMAP Phase 4 exit criteria

| # | Exit criterion | Scenario count | Scenario IDs |
|---|---|---|---|
| E1 | MQTT QoS redelivery cannot duplicate an action | 16 | P4-007, P4-013, P4-014, P4-016, P4-028, P4-029, P4-030, P4-049, P4-061, P4-075, P4-076, P4-081, P4-083, P4-085, P4-112, P4-118 |
| E2 | Restarting the backend cannot lose a pending command | 14 | P4-002, P4-004, P4-017, P4-023, P4-059, P4-060, P4-061, P4-062, P4-063, P4-066, P4-076, P4-081, P4-107, P4-112 |
| E3 | Timed-out commands are visible | 9 | P4-036, P4-041, P4-043, P4-052, P4-063, P4-065, P4-067, P4-093, P4-103 |
| E4 | ACK and convergence latency use backend timestamps | 3 | P4-047, P4-057, P4-058 |
| E5 | Every pipeline step is traceable | 6 | P4-009, P4-010, P4-015, P4-094, P4-095, P4-096 |

An exit criterion is satisfied only when every scenario listed for it is COVERED. Criterion E3 includes the visibility scenario P4-103 in group J.

### By contract clause

| Clause | Scenario count | Scenario IDs |
|---|---|---|
| R02 | 4 | P4-033, P4-102, P4-114, P4-138 |
| R03 | 2 | P4-028, P4-123 |
| R04 | 8 | P4-007, P4-008, P4-125, P4-127, P4-130, P4-133, P4-134, P4-135 |
| R05 | 1 | P4-008 |
| R06 | 5 | P4-009, P4-010, P4-032, P4-094, P4-139 |
| R07 | 8 | P4-001, P4-002, P4-004, P4-015, P4-024, P4-062, P4-076, P4-141 |
| R08 | 8 | P4-001, P4-002, P4-003, P4-004, P4-011, P4-031, P4-044, P4-137 |
| R09 | 16 | P4-013, P4-014, P4-015, P4-016, P4-017, P4-018, P4-019, P4-020, P4-021, P4-022, P4-023, P4-060, P4-066, P4-112, P4-128, P4-133 |
| R10 | 10 | P4-017, P4-024, P4-026, P4-027, P4-028, P4-032, P4-040, P4-045, P4-061, P4-140 |
| R11 | 15 | P4-005, P4-006, P4-012, P4-030, P4-036, P4-037, P4-038, P4-063, P4-067, P4-121, P4-122, P4-134, P4-136, P4-140, P4-142 |
| R12 | 19 | P4-006, P4-026, P4-030, P4-034, P4-035, P4-036, P4-038, P4-039, P4-040, P4-041, P4-042, P4-043, P4-045, P4-046, P4-064, P4-067, P4-093, P4-135, P4-136 |
| R13 | 12 | P4-025, P4-028, P4-045, P4-047, P4-048, P4-049, P4-050, P4-051, P4-054, P4-055, P4-057, P4-074 |
| R14 | 6 | P4-052, P4-053, P4-054, P4-056, P4-065, P4-103 |
| R15 | 11 | P4-017, P4-023, P4-059, P4-060, P4-061, P4-062, P4-063, P4-064, P4-065, P4-066, P4-132 |
| R16 | 6 | P4-068, P4-069, P4-070, P4-071, P4-074, P4-109 |
| R17 | 13 | P4-075, P4-076, P4-077, P4-078, P4-079, P4-080, P4-081, P4-082, P4-083, P4-084, P4-085, P4-086, P4-120 |
| R18 | 9 | P4-019, P4-021, P4-087, P4-088, P4-089, P4-090, P4-091, P4-092, P4-093 |
| R19 | 8 | P4-082, P4-125, P4-126, P4-129, P4-130, P4-131, P4-132, P4-139 |
| R20 | 7 | P4-057, P4-058, P4-094, P4-095, P4-096, P4-100, P4-103 |
| R21 | 11 | P4-033, P4-043, P4-073, P4-083, P4-097, P4-098, P4-099, P4-101, P4-102, P4-103, P4-104 |
| R22 | 5 | P4-012, P4-105, P4-106, P4-107, P4-115 |
| R23 | 5 | P4-068, P4-072, P4-086, P4-108, P4-109 |
| R24 | 15 | P4-011, P4-031, P4-041, P4-042, P4-044, P4-048, P4-051, P4-056, P4-084, P4-089, P4-106, P4-110, P4-111, P4-113, P4-114 |
| R25 | 5 | P4-117, P4-118, P4-119, P4-120, P4-121 |
| R26 | 1 | P4-029 |
| R27 | 1 | P4-116 |
| R28 | 1 | P4-124 |
| R29 | 6 | P4-046, P4-110, P4-111, P4-116, P4-123, P4-131 |
| R30 | 4 | P4-125, P4-126, P4-127, P4-128 |
| R31 | 6 | P4-137, P4-138, P4-139, P4-140, P4-141, P4-142 |

Clauses with no scenario of their own (definitional or covered through other clauses): R01.

## Evidence not yet produced

Everything except the five COVERED scenarios and the P4.1 evidence recorded below. In particular there is no recorded evidence yet for crash recovery, duplicate publication, retry bounds, legacy reconciliation, telemetry deduplication, backpressure or the enablement procedure.

## P4.1 evidence (2026-10-15, including the safety corrections)

The evidence below is recorded here. The 17 scenarios marked COVERED are fully proven; everything else, including the partial notes, stays PLANNED. Nothing here claims a dispatcher, crash recovery, bounded retry or expiry enforcement by a dispatcher: those are later slices.

### P4.1 tests (78 tests in 8 classes, all passing)

| Test class | Tests | Layer | What it establishes |
|---|---|---|---|
| `com.nexus.action.outbox.OutboxModelTest` | 13 | Unit | Legal transition table (25 pairs); terminal and unresolved partition; payload field order, stability, tamper detection and the independently verified non-ASCII UTF-8 hash vector; entity factory bounds; transition records; firmware-derived 20 s ceiling |
| `com.nexus.action.outbox.CommandOutboxSettingsTest` | 14 | Unit + Spring context runner | Defaults; worst-case schedule 10.8 s; every invalid configuration rejected; shipped `application.yml` leaves the flag OFF; invalid configuration stops startup |
| `com.nexus.action.outbox.CommandOutboxSchemaTest` | 22 | PostgreSQL (TimescaleDB container, Flyway V1 to V38) | V37 constraints, triggers, indexes and foreign keys; device removal now refused |
| `com.nexus.action.outbox.CommandOutboxSafetySchemaTest` | 15 | PostgreSQL, multi-connection | F1: deletion refused in every unresolved state and for resolved records, refused deletion preserves ActionHistory, desired state and verification evidence and rolls back, FK is RESTRICT; races (acceptance vs deletion both orders, claim and resolution vs deletion); concurrent claims of one device serialise (second waits, then 23505; or proceeds after rollback; 6 simultaneous claimers give exactly one IN_FLIGHT); attempt exhaustion cannot be parked or re-claimed; an expired row cannot be claimed but can be dead-lettered; non-ASCII payload hash identical in Java and PostgreSQL |
| `com.nexus.action.outbox.CommandOutboxUpgradeMigrationTest` | 1 | Migration on populated database | A V36 database with accepted, published, acknowledged, failed and desired-state-referenced commands upgrades to the latest migration with V1 to V36 checksums unchanged, every historical row identical and an empty outbox |
| `com.nexus.action.outbox.CommandOutboxPersistenceIntegrationTest` | 5 | Spring + PostgreSQL | Hibernate validation of the mapping; flag OFF; round trip; legacy ActionHistory path creates no outbox row; retention keeps unresolved obligations and purges resolved ones |
| `com.nexus.action.outbox.CommandOutboxDeviceDeletionApiIntegrationTest` | 5 | Spring + MockMvc + PostgreSQL | F1 at the application boundary: service and `DELETE /api/v1/devices/{id}` answer 409 with stable codes `DEVICE_HAS_UNRESOLVED_COMMANDS` and `DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE`; a device without records deletes (204); an unknown device is 404 |
| `com.nexus.action.outbox.RetentionIsolationIntegrationTest` | 3 | Spring + PostgreSQL, restart simulated with a fresh application context | F2: desired-state-referenced and outbox-protected rows retained, eligible rows deleted, desired state and verification intact; after a context restart the records persist and repeated runs are idempotent; an induced action_history failure leaves domain-event retention committed, is logged at ERROR, counted and thrown by the scheduled entry point |

### Existing tests changed

`com.nexus.rule.persistence.RuleV26ToV29MigrationTest` asserted that its final `migrate()` runs exactly two migrations (V35 and V36); with V37 it ran three and the first P4.1 full gate failed there. The step is pinned with `.target("36")`, so the assertion is unchanged and no assertion was weakened. V37 and V38 have their own tests.

### Mutation checks (each temporary, each reverted, restoration verified with `cmp`)

| Mutation | Result |
|---|---|
| Remove the `NOT EXISTS ... command_outbox` guard in the action_history delete | `retentionNeverDestroysPendingOrInFlightObligationsNorTheirActionRows` failed (`UnexpectedRollbackException`) |
| Remove the `NOT EXISTS ... current_desired_twin_state` guard | all 3 `RetentionIsolationIntegrationTest` tests failed on the V21 foreign key violation (the other categories still committed) |
| Collapse the per-category transactions into one shared transaction (the original defect) | `aFailingCategoryIsLoggedAndSurfacedAndDoesNotRollBackTheOthers` failed with `UnexpectedRollbackException` |

### Review experiments reproduced before the correction

On a disposable PostgreSQL with V1 to V37: deleting a device erased 5 of 5 outbox rows and left the ActionHistory rows ACCEPTED_FOR_DISPATCH with a null device; one old row referenced by current desired state made the whole action_history delete fail and rolled back the domain-event deletion in the same transaction. Both are now covered by the tests above.

### Validation results

| Run | Result |
|---|---|
| Focused new and related classes (outbox, retention, device API, command acceptance rollback, V26 to V29 migration) | 60 + 26 tests, all PASS |
| `python validate_nexus.py --fast` | PASS 8 of 8 |
| `python validate_nexus.py --full` | **PASS 11 of 11**: backend and integration suite 638 s; frontend lint, unit tests and build; simulator; whitespace; simulated loop smoke 50.3 s; MQTT command and ACK smoke 27.8 s; database restart smoke 55.6 s |

Actual counts: backend **160 test classes, 1,286 tests, 0 failures, 0 errors, 0 skipped** (surefire reports; the previous checkpoint was 157 classes and 1,262 tests); frontend **63 files, 533 tests, all passing** (re-run separately); simulator 56 passed and 3 skipped (the skips are pre-existing and unchanged).

One focused run was interrupted by me after exceeding the time limit (a test of mine waited on a statement that PostgreSQL correctly blocks); it was stopped, corrected and re-run, and its result is not counted anywhere above.

### Not implemented by P4.1

Atomic acceptance, the dispatcher, claim and lease queries, retries, expiry sweeps by a dispatcher, the command state history table (V39), dead-letter handling, ACK and timeout facts, telemetry deduplication (V40), backpressure and observability. The mandatory P4.2 safety requirements are in contract R31 and ledger P4-137 to P4-142.
