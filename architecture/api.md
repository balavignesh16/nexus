# NEXUS API Overview

## Base URL
`http://localhost:8080/api/v1`

## Device Management
- `POST /sites` - Create a Site
- `GET /sites` - List all Sites
- `POST /buildings` - Create a Building
- `POST /spaces` - Create a Space
- `POST /devices` - Provision a Device
- `GET /devices` - List all Devices
- `PUT /devices/{id}` - Update a Device

## Telemetry
- `POST /telemetry` - Submit Telemetry (HTTP Ingestion)
- `GET /telemetry/devices/{id}/latest` - Get latest telemetry for a device

## Digital Twin
- `GET /twins/{id}` - Get real-time Twin state for a device
- `GET /twins` - List all Twin states

## Event Engine
- `GET /events` - Read the bounded event store (debugging)

## Rules Engine
- `POST /rules` - Create a new rule
- `GET /rules` - List all rules
- `POST /rules/{id}/enable` - Enable a rule
- `POST /rules/{id}/disable` - Disable a rule
- `GET /rule-matches` - Read the bounded rule match store (debugging)
