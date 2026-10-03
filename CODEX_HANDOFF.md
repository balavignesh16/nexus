# NEXUS — Handoff Notes (Claude Code session → continuing in Codex)

## P1.1/P1.2 capability-definition foundation (2026-09-22)

The current enum-backed runtime contract is frozen in
architecture/device-capability-current-contract.md and three-device regression
fixtures. V15 adds a separate capability_definitions table seeded for every
current CapabilityType. The new definition package provides lower-snake-case
string keys, structural value types, one legacy mapping, structured typed
validation, persistence/service/DTO/controller layers, and APIs under
/api/v1/capability-definitions.

This remains a parallel foundation. DeviceCapability, telemetry, Twin identity,
rules, commands, frontend, and simulator still use the existing compatibility
model. No DeviceTemplate, generic runtime cutover, or P1.3+ feature exists yet.

## Phase 0 reproducible baseline (2026-09-22)

Phase 0 tooling is implemented but remains in progress until acceptance is
green. start_nexus.py performs environment, port, identity, dependency, and lock
preflight; bounded readiness replaces fixed sleeps; ownership is written
atomically beneath ignored .nexus/. stop_nexus.py is canonical and
stop_script.py is a compatibility shim. validate_nexus.py provides fast/full
reports and reuses existing Testcontainers isolation. Firmware, hardware tests,
and Review III evidence remain protected.

## Codex continuation (2026-09-22)

The first limitation in section 7 is now resolved end-to-end. Persistence and
rule evaluation were already keyed by `(deviceId, sensorType)`, but the REST API
still collapsed each device to one capability while the frontend expected all
capability rows. `DigitalTwinResponse` now exposes an aggregated `reportedState`
map and keeps the old top-level `latest*` fields as a compatibility summary.
Both `GET /twins` and `GET /devices/{id}/twin` return one device-level twin with
all current capability states. The Explorer twin view and overview cards render
that complete state. Backend twin/rule tests pass 76/76.

Vitest now excludes the stray `node_modules_bad2/` dependency backup and the
stale app routing harness was corrected. The actual frontend project suite is
53 files / 407 tests, all passing, and `npx tsc -b` is clean after resolving
the existing compile blockers. `architecture/api.md` and
`architecture/lifecycle.md` were refreshed to describe the real endpoints and
multi-capability reasoning behavior. Nothing was committed, and the user-owned
`firmware/platformio.ini` and `hardware-tests/` remain untouched.

Written 2026-09-22, at the end of a long Claude Code session. This file exists so a
fresh agent (or a fresh you) can pick up this repo without re-deriving everything
below the hard way. It is a snapshot of **what exists, what changed, why, and what's
still open** — not a permanent architecture doc (see `architecture/*.md` for that,
though some of it is stale — noted below).

---

## 1. What NEXUS is

A "physical intelligence platform": simulated ESP32 IoT devices publish telemetry
over MQTT → Spring Boot backend ingests it → maintains a digital twin → detects
events → evaluates automation rules → dispatches commands back to devices → devices
ACK → everything is persisted and traceable via correlation IDs. Frontend is a
React/TypeScript operations UI. Real hardware exists but is early-stage (see §7).

**Stack**: Java/Spring Boot backend (`backend/`) · React/Vite/TypeScript frontend
(`frontend/`) · Python device simulator (`simulator/`) · PostgreSQL/TimescaleDB +
Mosquitto MQTT via Docker (`infrastructure/`) · ESP32 firmware (`firmware/`,
`hardware-tests/` — **not touched this session, belongs to the user's own
concurrent work, do not modify**).

---

## 2. How to run everything

```
python start_nexus.py              # infra (docker) + backend + frontend + simulator
python start_nexus.py --duration…  # (no such flag — see grand_simulation.py instead)
python stop_script.py              # stops backend/frontend/simulator (not infra)
python stop_script.py --infra      # also stops docker compose
python stop_script.py --list       # dry run, shows what would be stopped
```

Both scripts are new this session, at repo root, **not yet committed**. They're
small, dependency-free orchestration scripts (see file docstrings). Windows
`.cmd`/`.bat` executables (`mvnw.cmd`, `npm.cmd`) **must** be launched via
`["cmd.exe", "/c", "mvnw.cmd", ...]`, not directly — Windows `CreateProcess` can't
exec a batch file directly under `shell=False`, and this environment additionally
has `NoDefaultCurrentDirectoryInExePath` set, which breaks a bare `mvnw.cmd` even
through `cmd.exe /c` unless you spell it `.\mvnw.cmd` (relative-path form). Both
scripts already handle this correctly — if you ever hit `WinError 2` again, this is
why.

**The simulator**: `start_nexus.py` launches `grand_simulation.py` (repo root, new
this session) as the default simulator — **not** the plain `python -m
nexus_simulator`. `grand_simulation.py` is a richer, scenario-driven simulation
covering all 9 demo scenarios (see §4). It falls back to the plain simulator only if
`grand_simulation.py` is deleted. **Never run both at once** — they use the same
MQTT client-ID scheme (`nexus-sim-{deviceId}`) and will fight over connections.

Backend: http://localhost:8080 · Frontend: http://localhost:5173 · Mosquitto: 1883
· Postgres: 5432 (`nexus_user`/`nexus_dev_pass`/`nexus_db`, see
`infrastructure/docker-compose.yml`).

---

## 3. Current git status — uncommitted, on purpose

**Nothing has been committed this session** (explicit instruction throughout). Run
`git status --short` to see the live list, but roughly:

**New files at repo root:**
- `start_nexus.py`, `stop_script.py` — dev orchestration (see §2)
- `grand_simulation.py` — the 9-scenario physical-world simulator (see §4)
- `results/` — Review III evidence package: `report.html` (published as a Claude
  Artifact), `NEXUS_Results_Obtained.xlsx`, `NEXUS_Review_III.pptx` (15 slides),
  3 PNG graphs, several CSV/JSON data files, and the Python scripts that generate
  them (`make_graphs.py`, `make_excel.py`, `make_pptx.py`). All numbers in these
  are real, sourced from live DB queries against an actual `grand_simulation.py`
  run — see `results/validation_results.json` for the full provenance trail
  (before/after DB counts, exact queries, disclosed limitations).

**Modified frontend files** — all bug fixes, see §5 for what each one was.

**Untouched (not this session's, do not modify without asking):**
`firmware/platformio.ini` (user has it modified), `hardware-tests/` (user's own
in-progress work).

---

## 4. `grand_simulation.py` — what it actually does

Provisions (idempotently — safe to re-run, never duplicates) 3 simulated ESP32
devices and 7 demo rules, then cycles through 9 scenarios over ~230s (loops forever
if no `--duration` given):

- **ESP32-ENV-01**: TEMPERATURE, HUMIDITY, PRESSURE (READ)
- **ESP32-SAFETY-01**: GAS, MOTION, BUTTON (READ)
- **ESP32-ACTUATOR-01**: LED, BUZZER, SERVO, FAN, RELAY×4ch (WRITE)

Scenarios: Normal → High Temperature → Motion → Gas Alert → Multi-capability
(Gas+Motion) → Relay control demo (manual REST dispatch) → Servo demo (manual REST
dispatch) → Button press → Recovery. Every device is `DeviceMode.SIMULATED`, never
claimed as physical. All telemetry/commands travel the exact same MQTT contract a
real ESP32 would use (see §6) — nothing bypasses the backend.

Run with the simulator's own venv interpreter:
```
simulator\.venv\Scripts\python.exe grand_simulation.py
simulator\.venv\Scripts\python.exe grand_simulation.py --duration 300
```

---

## 5. Bugs found and fixed this session (frontend)

Every one of these follows the **same pattern**: a frontend file assumed a field
name or data shape that never matched what the real backend actually sends. They
were found by reading the real backend DTO/entity source and diffing it against
what the frontend read — not by guessing. Worth knowing because there are likely
**more of these** in files nobody has audited yet (see §8 for how to check).

| File | Bug | Real backend fields |
|---|---|---|
| `pages/TelemetryPage.tsx` | Sensor dropdown used legacy `TEMPERATURE_SENSOR` etc; real `sensorType` values have no `_SENSOR` suffix (`TEMPERATURE`, `GAS`, ...). Chart was blank for every device/sensor. | Also added missing PRESSURE/BUTTON options. |
| `realtime/normalizePipelineTrace.ts` + `RealtimeEvents.ts` | Assumed the WebSocket pipeline-trace payload had fields `enteredAt/exitedAt/latencyMs/succeeded/stagePayload`. The backend actually broadcasts the *same* persisted `PipelineTrace` bean it serves via REST: `startTimestamp/endTimestamp/durationMs/status/...`. Every live trace showed `Invalid Date` + `FAILURE` regardless of real outcome. | `PipelineTrace.java` |
| `components/explorer/device/tabs/RulesTab.tsx` | Read `rule.condition` (singular, doesn't exist) and `action.type`/`action.payload`. Real shape is `rule.conditions[]` (`.field/.operator/.expectedValue/.capabilityType`) and `action.actionType`/`.parameters`. Trigger/Actions boxes were always blank. | `RuleRequest`/`RuleCondition`/`RuleAction` |
| `components/explorer/device/tabs/ActionsTab.tsx` | Read `action.payload` — real field is `action.parameters`. | `ActionHistoryResponse.java` |
| `api/twinApi.ts` | `DigitalTwinResponse` type declared `runtimeState`/`lastSeenAt`, neither exists. Real: `latestSensorType/latestValue/latestUnit/lastSeen/quality/metadata`. | `DigitalTwinResponse.java` |
| `components/ui/TwinCard.tsx` | Same wrong fields as above — every twin card on the global Twins page/dashboard widget always showed "No runtime state available" / "Seen Never". | — |
| `hooks/useDeviceTwinState.ts` | "Reporting Fields" only ever showed 1 sensor (read just the single latest telemetry record). Now unions sensor types across the whole fetched history window. | — |
| `components/explorer/device/tabs/DigitalTwinTab.tsx` | Fetched `GET /twins` (which returns **one row per device**, see §7) via `.find()` and treated it as a full twin. Rewrote to correctly merge whatever rows exist for the device. | — |
| `realtime/RealtimeRepositorySync.tsx` | **Two-stage bug, both fixed.** (1) The WS handler used `.map()`-only merge for the `['twins', N]` caches — a twin not already in the cache was silently dropped forever once REST polling paused (which happens automatically once realtime connects — see `usePolling.ts`). (2) My first fix patched just `sensorType`/`value` onto the cached row, but the backend's delta **never includes `unit`** — so a device cycling between capabilities produced torn records like `sensorType: "TEMPERATURE"` paired with the *previous* capability's stale value+unit (e.g. `"1013.43 CELSIUS"`). **Final fix**: stopped hand-merging entirely — any `TwinUpdated` event now just calls `queryClient.invalidateQueries` on both twin cache keys, forcing a full, internally-consistent refetch from the backend. |

**New UI features added** (not bug fixes): `RuleDetailModal.tsx` (click a rule card
→ popup with full conditions/actions in plain language, e.g. "Set RELAY channel 1
to ON on ESP32-ACTUATOR-01" instead of raw JSON) + edit-in-place support added to
`CreateRuleModal.tsx` (`editingRule` prop, submits via `PUT` instead of `POST` so
match history/cooldown stay attached to the same `ruleId`). Both modals were also
fixed to render via `createPortal(..., document.body)` — rendered inline they were
nested inside AppShell's content pane, which has its own CSS stacking context, so
the modal's `z-50` could never actually outrank the sidebar's `z-10` (a sibling of
that pane, not a descendant) — the sidebar was visibly painting over the modal
backdrop.

All of the above have test coverage for the specific fix (not just re-passing old
tests) — full frontend suite is at **2296/2300 passing**, the 4 failures are
pre-existing and unrelated (2 in `App.test.tsx`, 2 are collection errors from a
stray `node_modules_bad2/` folder vitest shouldn't be scanning at all — nobody has
fixed the vitest config exclusion for that, still open).

---

## 6. Backend contracts (verified against source, not assumed)

**Device/Capability** (`backend/src/main/java/com/nexus/device/`):
`CapabilityType`: `TEMPERATURE, HUMIDITY, PRESSURE, GAS, MOTION, BUTTON, LED, FAN,
BUZZER, SERVO, RELAY`. `DeviceMode`: `SIMULATED, REAL`. One `DeviceCapability` row
per (device, capabilityType, direction) — `RELAY` is one row with
`metadata.channels: [1,2,3,4]`, never 4 separate rows (DB unique constraint
enforces this).

**MQTT** (topics, all QoS 1):
- Telemetry: `nexus/devices/{deviceId}/telemetry` → `{deviceId, timestamp,
  sensorType, value, unit}`
- Status/LWT: `nexus/devices/{deviceId}/status` → `"online"`/`"offline"` (retained)
- Commands: `nexus/devices/{deviceId}/commands` → `{commandId, capability,
  command:"SET_STATE", value, timestamp}`
- ACK: `nexus/devices/{deviceId}/commands/ack` → `{commandId, status:
  "ACCEPTED"|"REJECTED", timestamp, reason?}`

**Rule engine** (`backend/src/main/java/com/nexus/rule/`): a rule is
`{name, enabled, priority, conditions[], actions[], metadata, cooldownSeconds}`.
Device scoping is *only* via a `{field:"DEVICE_ID", operator:"EQ",
expectedValue:"<uuid>"}` condition — there's no separate top-level `deviceId`
field. A capability-typed condition (`{field:"VALUE", operator, expectedValue,
capabilityType}`) is evaluated against the **live Digital Twin registry** for the
resolved device, independent of which telemetry message triggered evaluation —
this is what makes multi-capability rules (e.g. `GAS>500 AND MOTION==true`) work
correctly even when the two signals arrive in separate messages at different
times. Verified live: a real match had GAS evaluated 5 seconds after MOTION.
`RuleAction`: `{actionType, parameters}` — `SEND_COMMAND`'s parameters are
`{deviceId, capability, command:"SET_STATE", value}` (must be a real JSON
boolean/int/object, never a string).

**Command/ACK correlation**: `commandId == ActionHistory.requestId`. ACK handling
is idempotent (safe under QoS1 redelivery, `DeviceCommandAckMessageHandler.java`).

**Pipeline trace / correlation IDs**: one `correlationId` minted per ingested
telemetry message (`TelemetryService.processTelemetry`), threaded through
twin-update → event-detection → rule-match → action-dispatch. Reconstructable
end-to-end via `pipeline_traces` table / `GET /pipeline/history?correlationId=`.

---

## 7. Known limitations and resolved follow-ups

1. **Resolved in the Codex continuation:** Digital Twin persistence and in-memory
   lookup are keyed by `(deviceId, sensorType)`, rules read those capability rows,
   and the REST DTO now aggregates them into `reportedState`. A multi-sensor
   device therefore exposes every current capability through both Twin endpoints
   and the frontend.
2. **`action_history.ack_received_at` cannot measure command→ACK latency.** It's
   set from the simulated device's own whole-second-truncated timestamp
   (`DeviceCommandAckMessageHandler.java`: `Instant.ofEpochSecond(...)`), not the
   backend's receipt time — comparing it against the millisecond-precision
   dispatch timestamp produces meaningless (often negative) deltas. The Review III
   latency graph uses `pipeline_traces` ACTION-stage duration instead (backend
   clock only) — a real but narrower metric. See
   `results/validation_results.json` → `command_dispatch_latency` for the full
   writeup, including an unexplained bimodal distribution (fast cluster ~5ms,
   slower cluster ~45-50ms) that's disclosed as observed-but-not-root-caused.
3. **A real backend/infra fragility, self-resolving but disclosed**: running the
   backend's own MQTT-related integration test suite (`mvnw test`) concurrently
   with a live dev backend instance can wedge the dev backend's persistent MQTT
   session (client ID collision on the shared local Mosquitto broker → broker
   drops outbound delivery to `nexus-backend-sub` until the session is manually
   reset). Fix if it happens: connect once with `clean_session=true` under that
   exact client ID to force the broker to discard the stuck session, or just
   restart the backend. Don't run `mvnw test` and the dev backend against the
   same broker at the same time if avoidable.
4. **`architecture/api.md` is stale** — e.g. it lists `GET /twins/{id}` which
   isn't a real endpoint (it's `GET /devices/{id}/twin`), and is missing several
   real endpoints (capabilities, action-history, AI explanations, rule
   enable/disable, etc). Nobody has refreshed it this session; treat it as
   directional, not authoritative — verify against the actual `@RestController`
   source before relying on it.
5. **Diff mode on the Digital Twin tab is a known-honest no-op.** `GET /twins` is
   a current-state snapshot, not a time series — there's no real "previous state"
   to diff against from that data source, so Diffs mode intentionally shows
   current-vs-current (no changes) rather than fabricating a prior state from an
   unrelated capability's row.

---

## 8. If you're continuing bug-hunting in the frontend

The pattern that found every bug in §5: **for any component reading a REST/WS
response, open the actual backend DTO/entity it corresponds to and diff field
names by hand** — don't trust the frontend's own TypeScript interface, several of
those were themselves wrong (typed to match the bug, not the API). Good places to
check next if you want to keep going: `Events` tab/page, `Analytics` page,
`Diagnostics` tab, `AI Intelligence` page — none of these were audited this
session.

---

## 9. Review III materials (`results/`)

Built for a university panel review against a specific rubric (Implementation /
Technical Accuracy / Results Obtained So Far / Presentation & Clarity). Key files:
- `report.html` — published as a Claude Artifact (URL not reproduced here, ask if
  you need it re-published from this same file)
- `NEXUS_Review_III.pptx` — 15 slides, structured explicitly around the rubric
- `NEXUS_Results_Obtained.xlsx` — 7 sheets, includes an embedded chart
- `validation_results.json` — the single source of truth every other file was
  generated from; if you regenerate anything, edit this first then re-run
  `make_graphs.py` / `make_excel.py` / `make_pptx.py`
- All 3 PNG graphs are real matplotlib output from live DB queries, not mockups

**Do not regenerate these carelessly** — every number was cross-checked against
the database at least once (see the `command_ack_results_verification` and
`known_limitation_observed_during_this_run` blocks in the JSON for two examples of
numbers that were initially wrong and had to be reconciled against raw SQL).

---

## 10. Testing conventions observed in this repo

- Backend: `backend\mvnw.cmd test` (Windows: launch via `cmd.exe /c` or
  `.\mvnw.cmd`, see §2). 709/709 passing as of last full run.
- Frontend: `npm run test -- run` (vitest; bare `npm run test` runs in watch mode,
  don't use that in a non-interactive context) or `npx vitest run <path>` for a
  subset. 2296/2300 passing (4 pre-existing, unrelated — see §5).
- Every bug fix in this session added or updated a test that specifically
  reproduces the bug being fixed, not just a happy-path assertion — that's the
  established quality bar in this repo, keep it up.
- `npx tsc -b` (frontend) currently reports 12 pre-existing type errors, all in
  files untouched this session (`TelemetryTab.tsx`, `PipelineTimeline.tsx`,
  `ActionsPage.test.tsx`, `BackendRepository.test.ts`,
  `DataSourceResolver.test.ts`) — not introduced by this session's work, not
  fixed either.

---

## 11. Explicit constraints that applied all session (carry these forward)

## 12. Phase 1 P1.3-P1.5 generic runtime slice

- Flyway is now at V19: templates (V16), bindings/backfill (V17), typed telemetry
  (V18), and typed Twin state (V19). All changes are additive.
- Exact simulator templates are `esp32_sim_001`, `esp32_sim_002`, and
  `esp32_sim_003`; legacy `DeviceCapability` and IDs are preserved.
- `RuntimeCapabilityResolver` centralizes generic-binding-first resolution and
  legacy fallback, including explicit legacy `*_SENSOR` wire aliases.
- Telemetry accepts either legacy `sensorType` numeric input or generic
  `capabilityKey` typed input. Numeric compatibility fields remain for analytics.
- Rules now deserialize optional `capabilityKey`/`unit`; generic evaluation is
  typed and evidence includes definition/binding identity. Legacy rules and
  generic/legacy SEND_COMMAND inputs remain supported.
- No onboarding, template UI, Modbus, temporal rules, or other P1.6+ work was
  introduced.

## 13. P1.6/P1.7 continuation (2026-10-02; uncommitted)

- The two-file P1.5 closure checkpoint is commit
  `eac296b8a0116963e261a029f79f3edbe590fc8c`. Other Phase 1 foundation
  files remain part of the pre-existing dirty worktree; the checkpoint alone is
  not a standalone buildable Phase 1 baseline.
- P1.6 adds V20 `device_onboarding_sessions` and permits unitless typed telemetry.
  `DeviceProvisioningService` creates Device, template attachment, bindings, and
  session in one transaction. Request key/body hash gives durable same-request
  replay, while conflicting serials return conflict. No full editable contract
  JSON is persisted.
- `OnboardingLifecycleService` is the narrow admission authority: only
  `REGISTERED` plus `WAITING_FOR_TELEMETRY` can submit onboarding telemetry.
  Status changes the session to `WAITING_FOR_TELEMETRY`; successful telemetry
  persistence and Twin update activate the Device and mark it `OPERATIONAL`.
  Invalid payloads record `TELEMETRY_REJECTED`; transient infrastructure errors
  remain retryable. A persisted status deadline drives `STATUS_TIMEOUT`, with
  retry/disable operations. Restart integration coverage uses the same database.
- `BindingConstraintService` enforces narrowing and identifier uniqueness.
  Binding mutations guard enabled Rule references. `DeviceContractService`
  derives exact MQTT topics and effective instance constraints from authoritative
  Device/template/definition/binding state. Contract generation is not device
  credential management.
- P1.7 adds `/framework/capabilities`, `/framework/templates`,
  `/framework/onboarding`, `/framework/rules`, and a device contract route. The
  wizard and progress view use backend state only; generic Rule creation uses
  backend contract types/commands. Existing legacy screens remain in place.
- Focused P1.6 tests, P1.6 fast/full gate, and nine focused P1.7 UI tests
  passed. The final post-P1.7 full validation passed: 747 backend tests with
  zero failures/errors, 416 frontend tests, production build, lint, simulator,
  legacy physical-intelligence loop, isolated MQTT command/ACK, and restart
  smokes. The live browser acceptance scenario remains unverified. Do not
  claim real-hardware validation: this proof is
  simulated and backend-driven. P1.6/P1.7 must not be committed without user
  review. Do not tag/push or begin Phase 2.

- Never modify `firmware/platformio.ini` or `hardware-tests/` — the user's own
  concurrent, unrelated work.
- Never commit without being explicitly asked.
- Hardware status is tracked separately from software validation and must never
  be conflated: PIR, Push Button, small LED, Buzzer are physically **tested**;
  BME280 is **not verified**; MQ-2 is **parked**; Servo, Fan, Relay are **not
  tested** on real hardware. Every result in `results/` is a **SIMULATED**
  validation, stated explicitly everywhere it appears.

## 14. P1.6/P1.7 live browser acceptance closure (2026-10-03)

- The live E2E now passes with a real headless Edge browser driven by Playwright
  (`frontend/tests/e2e/p17-live.mjs`). It exercised the running Vite frontend,
  Spring Boot backend, disposable PostgreSQL/TimescaleDB, and a separate
  Mosquitto listener. The MQTT-speaking test device
  (`tests/e2e/p17_mqtt_device.py`) fetched the backend-generated contract and
  used its topics and capability metadata; no API responses were mocked.
- The passing run key is `soil_moisture_e2e_1790924003`; session
  `7221e360-1e2d-4b13-bc4b-d823a7d59e80`, device
  `29167064-eba2-485c-ba44-3e005b5526ab`, Rule
  `dd897332-d7f6-4f21-ab24-e14e0f9051da`, and command
  `cd8c90aa-378c-49f6-b086-ddb8b8dd7632`. The browser created the
  capability, template and bindings, provisioned the Device, displayed the
  generated contract, observed `WAITING_FOR_STATUS` →
  `WAITING_FOR_TELEMETRY` → `OPERATIONAL`, displayed 50 then 80 PERCENT in
  the typed Twin, created `GT 70` → LED `SET_STATE true`, and showed the
  persisted Rule Match and `ACCEPTED` Action in the UI.
- SQL in the disposable DB found two `GENERIC_BINDING` telemetry rows, one
  latest typed Twin, one matching RuleMatch with definition/binding IDs,
  one correlated ActionHistory/ACK, and only TELEMETRY, TWIN, ACTION trace
  stages (5 records). No `CapabilityType` member was added. Screenshots,
  JSON, and the SQL summary are in ignored `.nexus/e2e/1790924003/`.
- A genuine P1.7 defect surfaced during the live run: a delayed capability
  selection effect overwrote the user's numeric `GT` choice with `EQ`.
  `GenericRulePage` now preserves a selected operator while it remains valid,
  and `FrameworkPages.test.tsx` covers that transition. An earlier stalled
  ACK was caused by the test harness calling Paho `wait_for_publish()` from
  inside its message callback; the harness now confirms through `on_publish`.
  Neither issue required a Phase 2 change.
- Acceptance containers used tmpfs on ports 15432/11883, with no normal
  `nexus_postgres_data` mount, and were stopped after evidence capture. Their
  test database/broker data is gone; the normal development volume and
  protected firmware/hardware/results paths were untouched.
- Post-E2E `validate_nexus.py --fast` and `--full` passed. Backend Surefire:
  747 tests, zero failures/errors. Frontend: 417 tests. Legacy loop, MQTT
  command/ACK, restart, simulator, lint and production build passed. The
  historical Windows reader-thread Unicode traceback recurred in the wrapper,
  but it exited 0 and independent test reports confirm the result; validation
  code was not changed.
- P1.6/P1.7 are ready for user review but remain uncommitted. No tag, push,
  physical LED claim, or Phase 2 implementation occurred.

## 15. Staged commit checkpoint (2026-10-03)

- The user explicitly requested commits of all current changes. The Phase 1
  framework and live acceptance harness were committed in scoped stages.
- Tooling: `099e9ef`; backend framework: `583eca9`; frontend and browser
  acceptance: `bc235e0`; simulator/MQTT test harness: `75d44c0`.
- Pre-existing hardware test files were committed separately as `62bee82`;
  simulated-results and review artifacts were committed separately as
  `c2ecca6`. These commits do not imply additional physical validation.
- No tag or push was made, and Phase 2 implementation has not started. The
  earlier uncommitted notes above describe the state at the time of the live
  acceptance run.
