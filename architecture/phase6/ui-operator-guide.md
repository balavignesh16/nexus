# Phase 6 user interface and local startup

## Why the application looked unchanged

At candidate `3ed380a29ac768d147c1c901db5f1d24f169c62b`, Phase 6 backend controllers existed, but the frontend exposed only the older
`/ai` rule-match explanation page and its legacy API client. Assistant, anomaly/recommendation, trend, draft and energy integrations
were missing. The assistant was opt-in (`nexus.assistant.enabled=true`) and the old batch launcher did not enable it or anchor its cwd.
At investigation time no backend or Vite process was listening on 8080/5173: an old running Vite process was not demonstrated.

## Start the correct worktree

From PowerShell, regardless of the current directory:

```powershell
& D:\nexus-p6\start.bat
```

Equivalent portable Python entry point:

```powershell
python D:\nexus-p6\start_phase6.py
```

Open **http://localhost:5173/assistant**. The always-visible top-bar NEXUS Assistant link also opens it from the overview.
Use `localhost`, not a LAN IP: the existing API CORS policy allows localhost:5173 and was not widened.

The wrapper checks the actual listener process paths (including Spring Boot's Windows classpath argfile), refuses applications
from other worktrees, and uses the existing supervisor for readiness, logs, ownership and cleanup. Existing verified Docker services
are reused. It enables `phase6-local`, binds the backend to 127.0.0.1 and disables model access and device-evidence export.
It starts no simulator and creates no fake telemetry. It terminates no unrelated process. When this worktree already runs correctly,
it prints the existing URL instead of launching duplicates. Keep the foreground supervisor running; Ctrl+C stops its owned app processes.

For separate terminals (only when the supervisor is not already running):

```powershell
cd D:\nexus-p6\backend
$env:SPRING_PROFILES_ACTIVE = 'phase6-local'
.\mvnw.cmd spring-boot:run
```

```powershell
cd D:\nexus-p6\frontend
npm run dev -- --host localhost --port 5173 --strictPort
```

Backend: http://localhost:8080. Infrastructure: existing nexus-postgres:5432 and nexus-mosquitto:1883.
The development database was already migrated successfully through V43 before this UI startup; no schema reset or new migration was required.
Runtime ownership is in `.nexus/runtime.json`, startup/application logs in `.nexus/logs/`.

## Screens and actual endpoints

| Screen | URL | Backend |
|---|---|---|
| NEXUS Assistant | /assistant | POST /api/v1/assistant/ask |
| AI Insights | /intelligence | GET /api/v1/intelligence/recommendations; /anomalies/devices/{id}; /explanations/rules/{id}/non-match |
| Recorded rule-match explanations | /ai (existing deep links preserved) | GET /api/v1/ai/explanations/rule-match/{id}; durable match/action history |
| Trends | /trends | GET /api/v1/intelligence/trends/devices/{id}; /api/v1/devices/{id}/telemetry |
| AI Rule Drafts | /ai/drafts | GET and POST /api/v1/intelligence/drafts |
| Energy Intelligence | /energy | GET /api/v1/intelligence/energy |

Assistant history stays in memory for the page session. A conversation ID is sent back for follow-ups. All statements, status, routing,
ordering, timestamps and evidence references come from the backend. React renders plain text: no raw stored/model HTML is interpreted.
Requests do not use the demo repository or external model URLs. Failed requests display an error, not an invented answer.

Select a stored device and a capability key for trends/anomalies. Enter an actual rule ID for a non-match explanation (also available
from the rule registry/history). Trend limits are explicit query assumptions, not saved configuration or a certified safety cutoff.
The separate raw-reading list can include records older than the selected analysis window and says so.

## Try it

Use a device's exact registered name, not an unqualified bare UUID. The deterministic parser may ask for clarification otherwise.
On the inspected development database these are examples, not bundled responses:

* `What is the status of Verify Device?`
* `Is it online?` (follow-up: an absent connectivity observation honestly returns NO_EVIDENCE)
* `What is the latest temperature of ESP32-002?`
* `Why is energy consumption unavailable?`
* `What recommendations do you have for ESP32-002?`

All names and readings depend on your database. The displayed examples take device names from the real device-list API.

## Honest limits and approval boundaries

### Explicit local Ollama mode (integration follow-up)

Deterministic remains the default. An already-running process is not reconfigured by new launcher arguments.
The wrapper now refuses a wrong or unverifiable model mode. Stop only this worktree's owned app first:

```powershell
cd D:\nexus-p6
python stop_nexus.py
# Deterministic-only:
.\start.bat
# OR, with your own Ollama server already running and adequate free memory:
.\start.bat --ollama --model llama3.2:3b --allow-local-evidence
```

Equivalent: `python start_phase6.py --ollama --model llama3.2:3b --allow-local-evidence`.
The explicit consent permits selected device evidence to the local server; it is not permission for hosted transmission.
The launcher forces the hosted provider off and the Ollama URL to loopback. It checks installed model metadata,
refuses cloud/remote models and never downloads weights. Closing unrelated applications is the operator's choice.

Open `/assistant` and inspect **Model connection**. GET `/api/v1/assistant/model-status` exposes safe configuration,
cached metadata (15 s), loaded state, provider-entry/response counters and the latest gateway outcome. No prompt,
raw answer or evidence is included. Metadata probes do not run inference. A loaded state of unknown is not false.
Configured/installed does not imply loaded or used. `SCHEMA_ACCEPTED` means gateway parsing succeeded, not that
the downstream evidence selector accepted the result. Each answer's `orderedBy` remains authoritative for ordering:
`MODEL_SELECTED` means validated model ordering; `DETERMINISTIC` means not used or fallback.
Gateway counters are process-wide, not per conversation; attempt counts are provider entry, not proof of HTTP success.

Live metadata smoke: from frontend, `node tests/e2e/p6-ollama-live.mjs`.
Real inference smoke: `node tests/e2e/p6-ollama-live.mjs --inference` (Windows check requires at least 4 GiB free
RAM before sending the request, a conservative supervised-run threshold, not a model minimum specification).
No request interception is used. Respect the existing rate limits. Low-memory HOLD is not a passing inference test.
The last observed host had only about 1.7-2.5 GiB free: do not assume GPU VRAM alone establishes safe host resources.

To return to offline mode, stop the owned app and launch `start.bat` without model flags. Stop/unload your Ollama
server separately if desired; the NEXUS stop tool does not terminate the user-owned Ollama process.
Focused integration evidence is recorded separately in `ollama-ui-integration-20261020.md`; it is not certification.
The later resource-safe retry in that report verified one genuine llama3.2:3b response and a MODEL_SELECTED
assistant answer with two evidence items; the earlier low-memory HOLD remains documented as history.

The default local profile calls no model. Deterministic assistant answers, trend statistics and energy analysis do not need one.
General-language model-based draft generation is refused when model access/consent is disabled; the UI displays the actual refusal.
The model-free hard-limit proposal syntax remains available through the assistant as documented in `assistant-guide.md`.
Creating any draft is an explicit user action, never an automatic side effect of opening an analysis page.

Drafts show persisted validation/conflict details and lifecycle state. There is no approve or activate button/API helper.
Approval still requires the local, role-restricted command in `approval-operator-guide.md`; activation is a separate normal lifecycle step.
Model configuration is a separate deliberate operator choice; do not paste provider credentials into the browser.

No power/energy reading means unavailable consumption, not zero. Old or missing telemetry produces insufficient trend evidence.
Measured, simulated and estimated provenance remains labelled and separate. No live sensor or physical execution is claimed.
The backend's existing rate limits remain enforced; on HTTP 429 wait and retry rather than repeatedly clicking.
NEXUS has no general user authentication: this opt-in profile is for loopback/private development only, not public exposure.

## Verification and certification impact

Focused frontend tests are explicitly mocked unit tests; the separate `frontend/tests/e2e/p6-live.mjs` uses an actual browser and
the running backend, with no response interception. Run it from frontend with `node tests/e2e/p6-live.mjs`.
Set `P6_UI_EVIDENCE` to an evidence directory outside tracked source to preserve its JSON report and screenshots.
It respects the real rate limiter and requires at least one stored device; missing fixtures are not silently fabricated.

This UI work is after the candidate commit and is **not Phase 6 certification**. Original 95/95 and extension 57/59 ledger
statuses remain unchanged; P6-241/P6-242 remain PLANNED. Candidate/source inventory, UI acceptance evidence, frontend build and
the final full gate must be refreshed on a newly reviewed clean candidate. Do not reuse an earlier gate as certification of these changes.
No full validation gate, merge, tag, push or Phase 7 work is part of this UI task.
