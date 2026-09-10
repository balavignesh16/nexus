# NEXUS — The Physical Intelligence Framework

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
The NEXUS frontend supports multiple execution environments:
- **Backend Mode:** Standard execution connecting to the Spring Boot backend and PostgreSQL database.
- **Demo Mode (F6.1):** Operates entirely offline using a frontend `ScenarioEngine` and `DemoRepository` mock data.
- **Hybrid Mode (F6.2):** Toggles seamlessly between data sources using a global `DataSourceResolver`.
- **Realtime Mode (F7):** Uses WebSockets to push live data directly into the React Query cache via `RealtimeRepositorySync`.

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