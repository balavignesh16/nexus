# Phase 6.4 guide: deterministic anomaly detection and read-only recommendations

Status: P6.4 (2026-10-19). No model is needed for any of this. A model may optionally reorder and summarise the result (section 7); it can add nothing.

## 1. What it is, and what it is not

* **Anomaly findings** are deterministic OBSERVATIONS about persisted telemetry: "these readings are unusual for this capability of this device". They are not diagnoses. `confirmedFault` is always `false`, no probability or confidence exists anywhere in the output, and every finding says what is known and what is not.
* **Recommendations** are suggestions for a person to look at something, built from findings and from persisted Phase 3 and Phase 4 facts. Each carries support counts, the parameters applied, the window and resolvable evidence references, and the fixed statement that nothing was changed.
* Everything runs on request, in a **read-only transaction** (the database itself refuses a write) with a 5 s (analysis) / 8 s (recommendations) statement timeout. There is no scheduler, no async executor, no event listener and no persistence: results are recomputed from the same persisted facts and carry stable ids, so there is nothing to duplicate, expire or clean up.

## 2. Evidence sources and whose clock stamps them

| Fact | Table | Stamped by | How the window is bounded |
|---|---|---|---|
| Readings | `telemetry_history` (device_id, capability_key, timestamp, value_type, numeric_value, unit) | the **device** (event time, per the ingestion contract; receipt time is not stored with history) | `[from, to)` on event time, `to` = backend clock at evaluation; readings stamped more than `skew-tolerance-seconds` (300) after the backend instant are excluded and counted |
| Command failures | `command_state_history` joined to `action_history` | the **database** clock (`clock_timestamp()`, Phase 4 contract R37) | `[dbNow - window, dbNow)` on the database clock |
| Verification trouble | `current_twin_verification_state` (current status, not a history) | the backend clock | `[now - window, now)` |
| Rule suppressions | `rule_action_candidates` via the rule's versions and `rule_matches` | the backend clock | `[now - window, now)`, through the `(rule_version_id, matched_at)` index, at most 5,000 matches per rule |
| Rule thresholds | active rule versions | n/a | the telemetry window |
| Connectivity | `device_connectivity` (current status only) | the backend clock | n/a; shown beside findings, never merged into them |

Why the table matters: a first version bounded command history by the backend clock. Against the database clock, which runs a few milliseconds ahead here, the newest dead-letter fact could fall outside the window and be missed. Each source is now evaluated on the clock that stamped it, and a test with the backend clock an hour behind pins it.

## 3. Detectors (all pure functions; same readings in any order give the same findings)

Readings are first cleaned: non-finite values dropped, future-dated readings (beyond the skew tolerance) excluded, readings sorted by event time then value, exact duplicates (same time and value) collapsed, two **different** values at one timestamp removed (no basis to choose), and a window with mixed units or mixed value types refused. **BOOLEAN, STRING, ENUM and OBJECT capabilities are never analysed as continuous readings**: they answer `NOT_APPLICABLE`. Every count of what was dropped is reported in the finding's `quality`.

The window is split into a **baseline** `[from, split)` and a **recent** part `[split, to)` (defaults 24 h and the last 120 min). Each detector estimates its scale from the series' own baseline, so a thermometer and a power meter are judged by their own variation; there is no universal per-sensor constant. Intervals are half-open: a reading exactly at `split` is recent, one exactly at `to` is outside.

| Detector | Observed when | Refuses (INSUFFICIENT_EVIDENCE) when |
|---|---|---|
| FLATLINE | every recent reading is equal within the series' own **reading step** (the smallest positive gap between distinct baseline values) over at least 5 reporting intervals, while the baseline varied | baseline < 100 readings, recent < 10, baseline has fewer than 3 distinct values (a setpoint-like quantity is flat by nature), span too short |
| OUTLIER | modified z-score `0.6745 · |x − median| / MAD` of a recent reading against the baseline exceeds **k = 4.0** (mean-absolute-deviation form when MAD is 0) and the deviation is at least 3 reading steps | baseline < 100, no recent readings, baseline has no variation (scale undefined) |
| SPIKE | the same robust score applied to the **change between consecutive readings** (pairs no wider than 3 reporting intervals) exceeds **k = 5.0** and is at least 3 reading steps | baseline < 100, fewer than 50 baseline changes, no recent consecutive pair, no variation |
| GAP | a silence longer than **5 x the device's own median reporting interval** (and at least 60 s) between recent readings, or since the last reading, when the baseline cadence is regular (MAD/median of intervals ≤ 0.5) | baseline < 9 readings, cadence undefined or irregular |

A GAP finding is worded as "no reading with an event time after X is persisted". It never says the device is offline: connectivity is a separate fact, and a device clock error shifts the silence.

### Thresholds and why

* **k = 4.0, not the textbook 3.5.** On clean Gaussian noise, 3.5 flags about one series in twenty when ~120 recent readings are judged; 4.0 about one in a hundred.
* **Minimum baseline 100**, from a measurement (`BaselineSizeSweepTest`, clean Gaussian noise, 600 series per row, 16 recent readings):

| baseline readings | outlier false flags | spike false flags |
|---|---|---|
| 20 | 9.3 % | 2.7 % |
| 30 | 5.5 % | 1.2 % |
| 50 | 3.3 % | 0.7 % |
| 100 | 1.3 % | 0.0 % |
| 300 | 0.2 % | 0.0 % |

  An earlier default of 20 failed this on a real-pipeline series (an ordinary reading scored 4.3 against a MAD estimated from 24 readings), which is why it is 100. With a full day of one-minute readings (1,320 baseline) the measured rate over 400 clean series was 6/400 = 1.5 %, all from the outlier detector. **These are rates for synthetic Gaussian noise. Real sensors have heavier tails and will flag more; the figures are not a guarantee about any device.**
* **Slow reporters** (for example one reading per 30 minutes) have under 100 baseline readings in 24 h; widen `windowHours` (up to 720) so they can be judged, otherwise they answer INSUFFICIENT_EVIDENCE.

All values are in `nexus.intelligence.anomaly.*` (window-hours, recent-minutes, min-baseline-samples, min-recent-samples, outlier-k, spike-k, gap-factor, min-gap-seconds, skew-tolerance-seconds, max-samples, max-capabilities, max-finding-measurements) and are range-checked at startup.

## 4. Recommendations (all read-only)

| Category | Built from | Needs |
|---|---|---|
| INSPECT_SENSOR | an OBSERVED FLATLINE | the finding |
| CALIBRATION_CHECK | an OBSERVED OUTLIER with 3 or more flagged readings all on one side of the baseline median | the finding (an offset is "one possible explanation", never a finding) |
| INVESTIGATE_READINGS | other OBSERVED OUTLIER / SPIKE | the finding |
| INVESTIGATE_REPORTING_GAP | an OBSERVED GAP | the finding |
| REVIEW_RULE_THRESHOLD | an active rule's `VALUE` GT/GTE/LT/LTE condition on the device vs the recorded readings | at least 100 readings; recommended when the condition was never true or was true for at least half the readings; shows the sample size, counts, observed range and the 99th (or 1st) percentile as a reference, **clamped to the capability's declared range** |
| INVESTIGATE_FAILING_DEVICE | dead-lettered, ACK-timed-out and delivery-unknown commands (Phase 4 history) plus currently diverged or timed-out verification targets | at least 3 such facts; fewer are reported INSUFFICIENT_EVIDENCE with their count; devices are ranked by total, then id |
| REVIEW_RULE_CONFLICT | persisted arbitration suppressions of one rule in favour of another | at least 3 |
| MAINTENANCE_CHECK | at least **two independent groups** of facts (failures, long-standing OFFLINE, unchanging or silent telemetry) | two groups; one alone already has its own recommendation |

Every recommendation carries `support` (counts), `parameters` (the thresholds applied), a `window`, `evidenceRefs` that resolve to persisted rows (`telemetry-row:<id>`, `command:<id>/fact/<n>`, `candidate:<id>`, `rule:<id>`, `connectivity:<device>`, `finding:<id>`), and `effect` = "None. This is a suggestion for a person to review; nothing was changed, sent or scheduled." There is no confidence or probability field. Results are capped at 50 per scope (`truncated` says so).

## 5. HTTP API (GET only, unauthenticated like every NEXUS endpoint, rate limited with the shared limiter)

* `GET /api/v1/intelligence/anomalies/devices/{deviceId}?capabilityKey=&windowHours=&recentMinutes=` : findings for one device (404 for a missing or deleted device, 400 for a bad window or key).
* `GET /api/v1/intelligence/recommendations?deviceId=|ruleId=&windowHours=&telemetryHours=&explain=` : recommendations for one device, one rule's conflicts, or the fleet (at most 50 devices and 50 rules). `windowHours` (default 168, max 720) bounds Phase 3/4 facts; `telemetryHours` (default 24) the telemetry.

POST, PUT and DELETE answer 404/405. The router does not authenticate callers; the rate limiter establishes no identity.

## 6. Cost and concurrency

Every query has a window and a row limit; telemetry reads use the existing `(device_id, capability_key, timestamp)` index, command history the `occurred_at` index and arbitration the `(rule_version_id, matched_at)` index. No migration or index was added. At most 5,000 readings per capability and 20 capabilities per device are read; fleet scope is capped at 50 devices and 50 rules. A test runs 200 analyses of a 5,000-reading series beside 30 real ingestions: all ingestions succeeded and nothing was disturbed. **F1 (connection-pool exhaustion) is not fixed or measured here.** A request holds one connection for its read-only transaction, up to the statement timeout, and a fleet request runs its devices one after another; the shared limiter (4 concurrent) is the only guard.

## 7. Optional model help

`explain=true` asks the P6.2 gateway (enabled gate, separate device-evidence consent, bounded context, strict JSON) to put the RECOMMENDED items in a reading order and write a short summary. The answer is accepted only if the order is exactly a permutation of what it was shown, the summary is at most 600 characters, every number in it appears in the facts it was shown, and it contains no probability, certainty, fault or failure-prediction wording; otherwise the answer is REJECTED with a fixed reason and the deterministic list stands alone. A disabled, unconsented or failing model gives `UNAVAILABLE`. This is a narrower check than the Layer 1 claim grounding (it grounds numbers and wording, not per-claim evidence references), and it is not a defence against a model that writes a misleading but number-free sentence.

## 8. Limits stated plainly

1. Readings carry the device's own timestamps. A device whose clock is wrong cannot be told from silence or from a level change.
2. Offline **frequency** is not available: only the current connectivity status is persisted. Verification trouble is a current-state count, not a history.
3. Mutual-exclusion groups are not compared (P6-043 stays PLANNED); conflict recommendations use arbitration suppression records only.
4. False-flag figures are for synthetic Gaussian noise; heavy-tailed sensors will exceed them. No calibration was done, so no confidence is reported.
5. A request is not load-tested beyond the single concurrent-ingestion check; F1 remains an unresolved carry-forward.
6. The HTTP API does not authenticate callers; rate limiting is not identity.
