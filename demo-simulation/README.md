# Three-ESP interactive demonstration

Open **http://localhost:5173/simulation**. This is a behavioural software model, not a SPICE/circuit emulator,
calibrated MQ-2 model, physical sensor reading, or hardware certification. Every new device is SIMULATED.
Existing REAL USB boards, firmware, registrations and bridge are preserved; the demo never opens COM ports.

## Start

Start the existing NEXUS app/backend and Docker PostgreSQL/Mosquitto first. Do not start a second app or bridge
when one is already running. From D:\nexus-p6, in a separate terminal:

```powershell
# Existing SDK and isolated USB dependencies are reused; no new model/library download is required.
python demo-simulation/server.py --provision
```

The first launch creates NEXUS Live Demo → Three-Room Demonstration → three rooms and three new device
templates/registrations through normal APIs. Subsequent runs can use `python demo-simulation/server.py`.
Configuration is generated under ignored .nexus/demo-simulation.json. Port 8766 is loopback-only and is bound
before MQTT/provisioning so a second instance fails before taking over the existing MQTT identities.
Ctrl+C stops this simulator only; the real USB bridge is independent. Ollama is not required.
For the current presentation the physical USB bridge was paused to reduce backend intake load. The REAL
devices still exist and their local firmware keeps running. To resume physical telemetry later, stop this demo
with Ctrl+C and run `python usb-integration/bridge.py --config usb-integration/config.local.json` once.

## Demo sequence

1. ESP1: adjust temperature/humidity/pressure targets. The BME280 model approaches them gradually with bounded
   seeded noise. Toggle PIR; AUTO holds the LED for three seconds after motion clears. Press the simulated button
   to cycle AUTO → FORCE_ON → FORCE_OFF. Dashboard LED commands enter force modes through NEXUS.
2. ESP2: choose Warning exposure or Alarm exposure. The raw ADC model approaches the target, with baseline200,
   warning delta300, alarm delta600 and hysteresis100. RGB becomes yellow/red and the visual buzzer turns on.
   Clean air returns toward normal/green. No browser sound is generated. A manual buzzer demo lasts10 seconds;
   manual OFF cannot silence local ALARM. Real MQ-2 warm-up/baseline acquisition is deliberately omitted here;
   ADC values are demonstration inputs, never gas concentrations or safety thresholds.
3. ESP3: Blue LED ON/OFF and OPEN120/CLOSE60/CENTER90 use the existing backend command endpoint. The SG90
   model animates toward the requested angle at60 degrees/second. This speed is illustrative, not a measured
   hardware specification. Model-received command IDs are listed; Actions shows backend lifecycle evidence.

Sensor input controls only change this simulator's environment; they do not send commands to physical devices.
Actuators cannot be controlled through the local sensor-input endpoint. Backend command guards remain in place.
Telemetry/status and simulated ACKs use the existing Phase5 SDK contracts and MQTT topics. Telemetry publishes
every5 seconds, not every animation frame. No fabricated physical ACK/readback or REAL provenance is generated.
Command IDs are deduplicated up to10,000 during one simulator run; the model refuses additional IDs when full.
The demo is not a durable hardware uncertainty journal or an exactly-once execution guarantee across restarts.

## Infrastructure and onboarding

Use **http://localhost:5173/infrastructure** or Explorer → Manage / Add:

1. Select/create a Site.
2. Create/select its Building.
3. Create a Space/room in that Building.
4. Open Device Onboarding and select Template → Site → Building → Space.

Devices belong to a space within a building, not directly to a building. No database schema was changed.
Location creation is ordinary backend persistence, not a UI-only tree. Creation controls intentionally do not
delete existing infrastructure or relocate any real device. A new-tab link is provided in onboarding; create
locations before entering the wizard, or refresh it afterward to load the new hierarchy.

The Explorer telemetry repository now preserves typed booleans/strings/zero rather than showing legacy null
values; numeric charts do not coerce non-numeric telemetry into fake zero values.

## Focused verification

```powershell
python -m unittest discover -s demo-simulation -p test_model.py -v
node demo-simulation/live-check.mjs
cd frontend
npx vitest run src/repositories/BackendRepository.test.ts src/components/DeviceCommandControls.test.tsx
npm run build
```

The live check uses real UI requests, MQTT and backend lifecycle queries, not mocked API responses. It saves
evidence under .nexus/validation/three-esp-demo. A passing model test is not physical hardware verification.
No full certification gate, migration, merge, commit, push or tag is part of this demo.
