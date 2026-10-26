# Phase 6 live UI verification - 2026-10-20

This is focused UI/API acceptance evidence, not the final integrated certification gate.
Starting branch: feature/p6-intelligence. HEAD remained 3ed380a29ac768d147c1c901db5f1d24f169c62b; UI changes are uncommitted.

## Live instance identity

The supervisor ran from D:/nexus-p6 with frontend cwd D:/nexus-p6/frontend and backend cwd D:/nexus-p6/backend.
`.nexus/runtime.json` records that root and the listener ownership. Backend PID 26708 listens on 127.0.0.1:8080;
its Spring Boot argfile contains D:/nexus-p6/backend/target/classes. Startup logs confirm profile `phase6-local`.
Frontend PID 27640 listens on localhost:5173; its command line names D:/nexus-p6/frontend/node_modules/.../vite/bin/vite.js.
Supervisor PID at verification: 19232. PIDs are observations, not permanent configuration.
The guarded `python start_phase6.py` recognized this same instance and returned the assistant URL without starting duplicates.

GET /api/v1/platform-health returned database UP, mqtt CONNECTED. GET /api/v1/assistant/evidence/lookups returned all 12 lookup names.
The database's latest existing successful migration was V43 before startup; no new migration or schema reset was needed.

## Focused checks

* Frontend: `npm run test -- --run src/pages/Phase6Page.test.tsx src/api/intelligenceApi.test.ts src/pages/AiInsightsPage.test.tsx src/api/aiApi.test.ts src/test/App.test.tsx`: **30/30 PASS, five files**.
* Startup guard: `python -m unittest discover -s tests -p test_phase6_startup.py -v`: **3/3 PASS**.
* `npm run build`: PASS. Existing large-chunk warning remains; it is not a claim of optimal bundle size.
* Targeted ESLint over the new page, API client and their tests: PASS.
* `git diff --check`: PASS.
* Real Chromium browser with the actual frontend and backend: **11 checks PASS**, no browser runtime errors.

Unit tests explicitly mock responses to exercise error/loading/escaping behavior. They are NOT the end-to-end proof.
The live script `frontend/tests/e2e/p6-live.mjs` uses no response interception or seeded assistant answers.
Its final combined report and screenshots are preserved at `D:/nexus-p5-evidence/p6-ui-final-verified-20261020/`.
The final run also asserted that 50 non-empty historical telemetry records rendered with their original timestamps.
Earlier diagnostic runs are preserved separately at `p6-ui-live-20261020/` and `p6-ui-live-20261020-run2/`:
the first run requested an unqualified bare UUID and correctly received NEEDS_CLARIFICATION; the successful run used the registered name.
The test was corrected to exercise the supported named-device question, not to fabricate or relax an ANSWERED response.
Further diagnostics remain at `p6-ui-verified-20261020`, `p6-ui-complete-20261020` and `p6-ui-accepted-20261020`.
They exposed a test-only SPA navigation race (selecting the outgoing form before Trends mounted) and a legitimate HTTP 429
before the disabled-model refusal check. The test now waits for the destination heading and applies its bounded 429 backoff
to that negative check too; it still requires the actual HTTP 422 refusal. No backend limiter or UI success assertion was weakened.

| Browser action | Actual backend outcome |
|---|---|
| Open overview, click NEXUS Assistant, submit `What is the status of Verify Device?` | ANSWERED; DETERMINISTIC routing; two resolvable supporting evidence items, rendered in the chat |
| Ask `Is it online?` | Same conversation ID, second visible message; NO_EVIDENCE for absent connectivity, not invented online status |
| Open Energy Intelligence and load report | available=false; no stored power/energy readings in the window; exact unavailable reason shown |
| Open Trends, select stored device, load trend and raw readings | INSUFFICIENT_EVIDENCE, sampleCount=0 in the recent window; 50 historical records rendered with their stored timestamps |
| Open AI Insights, load recommendations | Actual backend recommendation report rendered |
| Select device and load anomalies | Actual device anomaly report and evaluation timestamp rendered |
| Enter a stored rule ID and load non-match explanation | Actual deterministic non-match response rendered |
| Follow recorded rule-match link | Existing explanation/history screen remains reachable |
| Select a recorded match | HTTP 200, generatedBy=DETERMINISTIC_FALLBACK; exact returned summary rendered without a model |
| Load AI Rule Drafts | Actual empty stored-draft list; no approval/activation controls |
| Request a model-based draft with model disabled | HTTP 422 MODEL_REFUSED, modelRefusal=DISABLED; refusal rendered; draft count unchanged |

The live script waits between groups to honor the existing production rate limiter; neither limits nor assertions were weakened.
No successful draft lifecycle was fabricated from the empty development database. Persisted draft rendering, validation fields and
approval boundaries are implemented; model-free hard-limit proposals remain documented but were not created during this smoke.

An additional focused `node tests/e2e/p6-live.mjs --trends-only` run passed with non-empty stored telemetry:
50 actual records rendered, including sample timestamp `2026-09-12T12:57:11Z`. Its recent-window trend correctly remained
INSUFFICIENT_EVIDENCE with zero usable samples. Evidence: `D:/nexus-p5-evidence/p6-ui-trends-stored-20261020/`.
This distinguishes historical records from recent data rather than treating them as new sensor measurements.

## Source inventory for this UI task

```text
frontend/src/App.tsx
frontend/src/components/AppShell.tsx
frontend/src/pages/AiInsightsPage.tsx (stale comment only; old route preserved)
frontend/src/pages/Phase6Page.tsx
frontend/src/pages/Phase6Page.test.tsx
frontend/src/api/intelligenceApi.ts
frontend/src/api/intelligenceApi.test.ts
frontend/tests/e2e/p6-live.mjs
backend/src/main/resources/application-phase6-local.yml
start.bat
start_phase6.py
tests/test_phase6_startup.py
architecture/phase6/ui-operator-guide.md
architecture/phase6/ui-verification-20261020.md
```

## Safety and remaining work

All new read screens call existing fixed APIs. No browser-side model call, approval credential, approval bypass, activation API,
fake sensor feed or successful-response fallback was added. The local profile explicitly binds loopback and disables model/evidence export.
The release-blocking injection scan, retrieval hardening, approval workflow, migrations, full-gate budget and cleanup code are untouched.
Protected D:/nexus, D:/NEXUS_Review_Prep, firmware, hardware-tests and results were not modified.

Existing certification documents/ledger are preserved. Original 95/95 and extension 57/59 COVERED remain, with P6-241/P6-242 PLANNED.
The UI change set needs review and a new clean candidate before final integrated certification evidence can be produced.
Real-model reliability, missing live sensors/power readings, adverse-distribution anomaly figures and other previously documented limitations remain.
The production build still reports its existing large-bundle warning. The unchanged Overview components also emitted duplicate React-key
warnings (`event-undefined` / `action-undefined`) during live navigation; no uncaught page errors occurred in the Phase 6 flows.
No full gate, commit, merge, push, tag or Phase 7 development was performed for this UI request.
