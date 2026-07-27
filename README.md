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