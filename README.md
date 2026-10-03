# NEXUS — The Physical Intelligence Framework

## Reproducible development baseline

Supported environment: Java 21 LTS (newer JDKs best-effort), Node.js 22+,
Python 3.11+, Docker with modern Compose, TimescaleDB 2.16.1/PostgreSQL 16,
and Mosquitto 2.x. Ports: backend 8080, frontend 5173, MQTT 1883, PostgreSQL
5432. Copy .env.example to .env for overrides; never commit real keys.

    python start_nexus.py
    python start_nexus.py --help
    python start_nexus.py --no-simulator
    python start_nexus.py --duration 60 --infra-down-on-exit
    python stop_nexus.py --list
    python stop_nexus.py --infra
    python validate_nexus.py --fast
    python validate_nexus.py --full

Startup rejects occupied ports unless --reuse verifies an independently running
NEXUS service. A second live orchestrator is always rejected. Readiness is
bounded; Vite stays on 5173. Ignored .nexus/ contains runtime ownership, logs,
and validation reports. --clean never removes databases or Docker volumes.

Full validation requires a stopped application environment. For collisions run
python stop_nexus.py --infra. For readiness or migration failures inspect
.nexus/logs and .nexus/validation/latest.txt.

Automated end-to-end evidence is an **integrated simulated
physical-intelligence software loop**, not proof of a physical ESP32 loop.

### Capability-definition foundation

The backend exposes additive capability metadata through GET/POST
/api/v1/capability-definitions and GET/PUT
/api/v1/capability-definitions/{id}. Definitions use stable lower-snake-case
string keys and typed constraints. Active telemetry, Twin, Rule, command,
frontend, and simulator paths still use CapabilityType compatibility; arbitrary
new definitions do not yet flow end-to-end. Device templates and runtime
cutover remain later work.

A framework for transforming physical-world telemetry into contextual intelligence.

## Core Vision
NEXUS transforms physical-world telemetry into intelligence through a fundamental processing loop:
**SENSE → CONNECT → UNDERSTAND → DECIDE → ACT → LEARN**

## Simulation-First Principle
NEXUS is designed simulation-first. The backend does not differentiate between a simulated device and physical hardware. Both communicate over the exact same MQTT contract, ensuring the software architecture is robust before physical devices are integrated.

## High-Level Architecture & Tech Stack
- **Backend:** Modular Monolith using Java 21 & Spring Boot 3
- **Frontend:** Single Page Application using React 19, TypeScript, Vite & React Query
- **Messaging:** Eclipse Mosquitto (MQTT) & Spring STOMP WebSockets
- **Database:** PostgreSQL / TimescaleDB (JPA-backed persistence for telemetry, events, actions, pipeline traces, and digital twin state)
- **Simulator:** Python-based virtual device engine & Frontend Scenario Engine

## Repository Structure
This is a monorepo containing the following components:
- `backend/` — The Java/Spring Boot core platform.
- `frontend/` — The React/TypeScript/Vite Operations Center UI.
- `simulator/` — The Python virtual device and telemetry generation engine.
- `infrastructure/` — Docker Compose, Mosquitto, and PostgreSQL configuration.
- `architecture/` — ADRs, Component Diagrams, and RC1 Validation reports.

## Development Status
**Current Phase: F0 — Platform Stabilization & Baseline Freeze**

The framework currently features a fully functional decoupled Realtime Platform (F7) and Pipeline Observability module (F7.5). The backend implements the complete loop from Telemetry to Events to Rules to Actions. 

### Current Data Flow
`Device ➔ MQTT ➔ Telemetry ➔ Twin ➔ Event ➔ Rule ➔ Action ➔ Pipeline ➔ Realtime STOMP ➔ Frontend React Query ➔ UI`

### Hybrid Operating Modes
The NEXUS frontend supports multiple execution environments. The shipped default is **live-backend, not demo** (`DEMO_MODE` defaults to `false`, overridable via the `VITE_DEMO_MODE` build env var):
- **Backend Mode (default):** Connects to the real Spring Boot backend; the realtime WebSocket connects live rather than parking in a demo state, so React Query polling and live push both reflect real data.
- **Demo Mode:** Operates entirely offline using a frontend `ScenarioEngine` and `DemoRepository` mock data. Still available by setting `VITE_DEMO_MODE=true`, but is opt-in rather than the default.
- **Hybrid Mode:** The global `DataSourceResolver` still falls back to `DemoRepository` fixtures per-call when a live backend request fails or returns an empty result (e.g. a freshly-restarted backend with no rules registered yet will show a sample rule rather than an empty list) — this fallback is independent of the `DEMO_MODE`/`VITE_DEMO_MODE` setting above and has not been removed.
- **Realtime Mode:** Uses WebSockets to push live data directly into the React Query cache via `RealtimeRepositorySync`.

## Current Limitations & Known Issues (Pre-F8)
NEXUS is currently in a pre-production baseline state and has several critical limitations:
1. **Rule State Is Still Volatile:** Telemetry, Events, Action History, Pipeline Traces, and Digital Twin state are now persisted to PostgreSQL/TimescaleDB via JPA (verified against a real TimescaleDB instance, including restart/hydration). Rules and rule-match history, however, remain in an in-memory `RuleRegistry`/`BoundedRuleMatchStore` and are still lost on backend restart.
2. **No Security:** The platform lacks authentication (AuthN), authorization (AuthZ), JWT, and Spring Security. The MQTT broker runs with `allow_anonymous true`.
3. **Hardware Integration:** Real ESP32/C++ edge gateways do not exist yet.
4. **Digital Twin Limitations:** Digital Twins currently only support "Current State" reporting. Desired state reconciliation is not yet implemented.
5. **No AI:** Decisions are made by the deterministic rule engine described above; no AI/ML model, inference, or anomaly-detection code exists in the codebase yet, despite the project's long-term framing.
6. **Action Execution Is Stubbed:** The Action Execution Framework correctly dispatches matched rules and persists a real history of each attempt, but the registered executors (alert, command, webhook) are stub implementations that log and record a result rather than calling a real external system or physical actuator.

## Local Development
Prerequisites: Docker and Docker Compose.

1. **Configuration:** Copy `.env.example` to `.env` in the root directory.
2. **Start Infrastructure:** `docker compose -f infrastructure/docker-compose.yml --env-file .env up -d`
3. **Start Backend:** `cd backend && mvnw spring-boot:run`
4. **Start Frontend:** `cd frontend && npm run dev`
5. **Run Simulator:** `cd simulator && python -m nexus_simulator`

*Alternatively, run `start.bat` on Windows to launch all services.*

> **Security Warning:** The Mosquitto MQTT broker is strictly configured for trusted local development. Do not expose this service to the internet without configuring TLS and password authentication.
