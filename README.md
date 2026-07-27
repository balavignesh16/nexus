# NEXUS — The Physical Intelligence Framework

A framework for transforming physical-world telemetry into contextual intelligence.

## Core Vision
NEXUS transforms physical-world telemetry into intelligence through a fundamental processing loop:
**SENSE → CONNECT → UNDERSTAND → DECIDE → ACT → LEARN**

## Simulation-First Principle
NEXUS is designed simulation-first. The backend does not differentiate between a simulated device and physical hardware. Both communicate over the exact same MQTT contract, ensuring the software architecture is robust before physical devices are integrated.

## High-Level Architecture & Tech Stack
- **Backend:** Modular Monolith using Java 21 & Spring Boot 3
- **Frontend:** Single Page Application using React, TypeScript & Vite
- **Messaging:** Eclipse Mosquitto (MQTT)
- **Database:** PostgreSQL (Relational schema & JSONB for telemetry)
- **Simulator:** Python-based virtual device engine

## Repository Structure
This is a monorepo containing the following components:
- `backend/` — The Java/Spring Boot core platform.
- `frontend/` — The React/TypeScript/Vite command center UI.
- `simulator/` — The Python virtual device and telemetry generation engine.
- `infrastructure/` — Docker Compose, Mosquitto, and PostgreSQL configuration.
- `docs/` — Architecture notes, API specifications, and project documentation.

## Development Status
**Current Phase: M0 — Architecture & Engineering Foundation**

The framework is currently in its initial foundation phase. No functional capabilities have been implemented yet. It is NOT production-ready.

## Development Workflow
NEXUS follows a strict **trunk-based development** workflow:
- **Test-Before-Commit:** Code must compile, pass linting, pass all automated tests, and meet acceptance criteria before a Git commit is created.
- **Conventional Commits:** All commits must follow the conventional commit format (e.g., `feat(module): description`, `fix(module): description`).

## Local Development
Prerequisites: Docker and Docker Compose.

1. **Configuration:** Copy `.env.example` to `.env` in the root directory.
2. **Start Infrastructure:** Run `docker compose -f infrastructure/docker-compose.yml --env-file .env up -d`
3. **Service Ports:** 
   - PostgreSQL is exposed on port 5432.
   - MQTT (Mosquitto) is exposed on port 1883.
4. **Shutdown:** Run `docker compose -f infrastructure/docker-compose.yml stop`
5. **Reset Data:** Run `docker compose -f infrastructure/docker-compose.yml down -v`

> **Security Warning:** The Mosquitto MQTT broker is currently configured with `allow_anonymous true`. This is strictly for trusted local development and simulation purposes. Before any real physical hardware (e.g., ESP32) communicates outside a trusted local environment, MQTT authentication must be enabled.

## Frontend Development

**Prerequisites:** Node.js (v22+)

1. **Install Dependencies:** `npm ci` (inside the `frontend/` directory)
2. **Start Dev Server:** `npm run dev`
3. **Build:** `npm run build`
4. **Lint & Test:** `npm run lint` and `npm run test`

## Simulator Development

**Prerequisites:** Python 3.11+

1. **Setup Virtual Environment:** `python -m venv .venv` (inside the `simulator/` directory)
2. **Activate:** `.venv\Scripts\Activate.ps1` (Windows) or `source .venv/bin/activate` (Mac/Linux)
3. **Install Dependencies:** `pip install -e .[dev]`
4. **Run Simulator:** `python -m nexus_simulator`
5. **Run Tests & Linting:** `pytest`, `ruff format .`, `ruff check .`
*(Note: MQTT and Sensor simulation are deferred to M3)*