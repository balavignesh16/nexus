# Local Ollama UI integration - 2026-10-20

Worktree: D:/nexus-p6, feature/p6-intelligence, base HEAD 3ed380a29ac768d147c1c901db5f1d24f169c62b.
This change preserves the earlier 14 pending UI paths. No commit or integrated certification is claimed.

## Implementation

* `start_phase6.py`: explicit `--ollama --model llama3.2:3b --allow-local-evidence`; default forces both
  providers and evidence consent off. Local mode forces the hosted provider off, loopback URL, installed/local
  metadata preflight, no redirects/downloads, supervisor argument validation and verified-mode reuse.
  An existing backend cannot be silently reconfigured. `start.bat` documents the flags.
* Local profile: explicit Ollama/evidence opt-in, one model call at a time, no retries, 60 s guard/request limits.
  Context, output, grounding, cloud rejection and rule approval boundaries are not relaxed.
* ModelGateway adds process-wide provider-entry and returned-response counts and safe latest outcome/duration.
  Existing closed-vocabulary counters remain unchanged. Counts are not per-request attribution; provider entry
  can fail before an HTTP chat request, and SCHEMA_ACCEPTED is not downstream evidence-selection acceptance.
* GET `/api/v1/assistant/model-status`: assistant opt-in only, read-only configuration and metadata.
  OllamaProbe is reused, with loaded state from `/api/ps`. Metadata caches for 15 s, per-request timeout 2 s,
  aggregate wait bounded to 9 s using at most one daemon worker and no queue. A stalled worker cannot spawn
  unbounded replacements. Unavailable metadata is unverified, not proof the selected model is absent.
* ModelStatusPanel renders actual NEXUS API metadata and attempt/refusal states. Answers retain routedBy/orderedBy
  and explicit validated-model-ordering versus deterministic labels. No browser-to-Ollama call, raw model prose,
  approval, activation or command helper was added.

## Actual environment observations

Ollama 0.21.2 is user-owned PID 11160, loopback 127.0.0.1:11434. Server tags report llama3.2:3b, llama3.2:latest,
deepseek-r1:1.5b, phi3 variants, llama3.1:latest, qwen2.5:14b-instruct and qwen3.5:cloud.
The cloud entry reports a remote host and is excluded. No weights were downloaded.

Selected llama3.2:3b: 3.2B, Q4_K_M, 2,019,393,189 bytes, digest
`a80c4f17acd55265feec403c7aef86be0c25983ab279d83f3bcd3abbcb5b8b72`.
Read-only metadata confirms local hosting and completion/tools capabilities. Installed does not mean loaded.

Host: 15.63 GiB RAM, observed free 1.58-2.50 GiB in this implementation campaign (1.85 GiB initially).
RTX 3050 Laptop GPU: 4,096 MiB total, about 3,900 MiB free after focused checks.
Only this worktree's verified owned app processes were restarted; the user's Ollama process and unrelated apps
were not stopped. PostgreSQL remained healthy and Mosquitto running. Startup used SPRING_FLYWAY_ENABLED=false;
no migrations/schema reset were run. No firmware/hardware/results changes were made.

## Focused verification

* Python: `python -m unittest discover -s tests -p test_phase6_startup.py -v`: 9/9 PASS,
  including missing/remote model metadata and redirect refusal.
* Backend, sequential, JAVA_TOOL_OPTIONS=-Xmx512m, MAVEN_OPTS=-Xmx256m, MQTT_PORT=1:
  `./mvnw.cmd -o -Dtest=OllamaModelProviderTest,ModelGatewayTest,ModelWiringTest,AssistantBoundaryTest,IntelligenceArchitectureTest -Dsurefire.reportNameSuffix=p6-local-ui-final test`:
  37 PASS, zero failures/errors/skips, 25.541 s. Log `.nexus/logs/ollama-focused-backend-final.log`.
  These use fake providers/loopback protocol servers, not a real local-model quality benchmark.
* Frontend: focused ModelStatusPanel, Phase6Page and intelligenceApi tests: 16/16 PASS across three files.
* Frontend build and targeted ESLint PASS; existing large-chunk warning remains.
* Real Chromium + actual running NEXUS: metadata panel PASS, selected local provider/model/digest installed,
  not loaded, zero provider attempts. Evidence `.nexus/validation/p6-ollama-live/`.
* Separate actual deterministic-mode browser answer PASS, ANSWERED with real supporting evidence and zero
  provider attempts. Evidence `.nexus/validation/p6-ollama-deterministic/`.
* Browser scripts do not intercept requests or fabricate answers. Metadata probes do not initiate inference.

## Explicit HOLD: real provider generation not verified

No actual Ollama completion was requested by this task while RAM remained low. Provider attempts/responses
were zero in the metadata smoke. This is configuration/connectivity/UI evidence, NOT proof of a provider call,
model-assisted response, fallback after a real model failure, successful draft generation or model quality.
The previously recorded historical P6.9 evaluation remains separate and its quality failures are unchanged.
The `--inference` smoke was invoked at the end and exited 1 on its RAM precondition before any model request:
HOLD, not a passing generation test or a failed model response. The final metadata/browser smoke passed again.
NEXUS is left running in explicit OLLAMA_LOCAL mode with llama3.2:3b; no model is loaded by these checks.

After adequate sustained headroom, from frontend:
`node tests/e2e/p6-ollama-live.mjs --inference`.
The Windows smoke enforces a conservative 4 GiB free-RAM precondition before sending an inference request.
It records actual attempt deltas and the answer's orderedBy independently. Rejected output must remain fallback,
not be called a valid model-assisted answer. No model timeout or assertion is weakened to force acceptance.

Original Phase 6 remains 95/95; extension remains 57/59, P6-241/P6-242 PLANNED. The new code/UI change set needs
review and a newly authorized clean candidate plus the required integrated evidence before certification.
No full gate, mutation application, Phase 7, commit, merge, push or tag occurred in this task.

## Follow-up: real inference verified after resources freed (23:48 IST)

The user freed resources and asked to try again. The previous HOLD above is retained as historical evidence,
not the current connectivity verdict. Before retry: free host RAM 5.12 GiB; GPU free 3,900 MiB; same branch/HEAD,
same running local provider, zero prior provider attempts. No code, schema, provider contract or validation limit
was changed for this retry.

Command, from frontend:
`P6_UI_EVIDENCE=../.nexus/validation/p6-ollama-inference-20261020 node tests/e2e/p6-ollama-live.mjs --inference`
(PowerShell used `$env:P6_UI_EVIDENCE='...'` before invoking Node.)

Result: exit 0, real Chromium with actual backend and local Ollama, no request interception.
The question `What is the status of Verify Device?` returned ANSWERED, routedBy DETERMINISTIC,
orderedBy MODEL_SELECTED, two supporting evidence items. Provider attempts increased 0 to 1 and returned
responses 0 to 1. Gateway SCHEMA_ACCEPTED, 6,252 ms. Thus this run proves both a genuine provider response
and downstream validated evidence selection, not merely a configured provider or HTTP 200 assistant answer.
Factual sentences remained platform-written. Browser reported no uncaught page errors.

Evidence: `.nexus/validation/p6-ollama-inference-20261020/report.json` and `assistant-model-status.png`.
The screenshot shows the submitted question, actual answer, model-ordering label and counters.
Its loaded=no indication was the correctly timestamped 15 s metadata cache from BEFORE the call;
a subsequent fresh status probe reported loaded=true without triggering additional inference.
Ollama `/api/ps` independently reported llama3.2:3b with the same digest, context 4096, allocation
3,079,757,824 bytes and VRAM allocation 2,756,540,416 bytes. After inference: free host RAM 4.33 GiB;
GPU usage 2,824 MiB and free 1,139 MiB. No unrelated process was stopped.

Current verdict: local connectivity and one validated browser/model-assisted answer VERIFIED.
This single successful sample does not overturn the historical quality failures, prove real-model fallback,
verify successful draft generation or certify Phase 6. Original 95/95, extension 57/59 and P6-241/P6-242
PLANNED remain unchanged. No full gate, commit, migration, command dispatch or subsequent phase was run.
