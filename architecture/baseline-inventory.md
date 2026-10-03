# Phase 0 baseline inventory

Recorded 2026-09-22 before Phase 0 implementation.

| Group | Files | Ownership / purpose | State | Phase 0 edit |
|---|---|---|---|---|
| Framework | backend Twin source/tests | Prior multi-capability work | modified/untracked | no |
| Architecture | API and lifecycle docs | Prior documentation | modified | no |
| Frontend | source/tests/config | Prior Twin/rule/realtime work | modified/untracked | config only |
| Tooling | environment/start/stop scripts | Development workflow | mixed | yes |
| Simulator | grand simulation | Integrated simulated loop | untracked | no |
| Firmware | firmware/** | User-owned physical-device work | modified | **never** |
| Hardware | hardware-tests/** | User-owned experiments | untracked | **never** |
| Evidence | results/** | User-owned Review III evidence | untracked | **never** |

Do not commit .nexus runtime state, locks, logs, validation reports, target/,
dist/, Python caches/virtual environments, node_modules or backup dependency
directories, simulator temporary output, validation scratch, or temporary
Docker resources. Docker volumes are never deleted by --clean.

## Baseline evidence

- Backend: 714 tests, 0 failures, 1 test-harness error. The scheduling test used
  localhost PostgreSQL instead of Testcontainers: Phase 0 configuration issue.
- Frontend: 53 files / 407 tests and production build passed. ESLint failed due
  to an inconsistent installed TypeScript-ESLint dependency tree: generated
  dependency contamination.
- Simulator: 51 passed, 3 skipped. Ruff had 72 pre-existing legacy style
  findings; Phase 0 does not rewrite unrelated simulator behavior.
- Git whitespace check passed.

Existing PostgreSQL/TimescaleDB and Mosquitto Testcontainers remain the
integration isolation strategy.
