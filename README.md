# NEXUS — Physical Intelligence Framework

**Canonical checkout: `D:\nexus`, branch `main`.** Phase 4.5/4.6, Phase 5, Phase 6 and the latest
operator-interface, Ollama, three-ESP demo and USB work are integrated here. Older worktrees are recovery
copies, not dependencies. Read [the consolidation record](architecture/consolidation-20261026.md).

## Included features

- Generic capability definitions/templates, onboarding and Site → Building → Space placement.
- Separate reported/desired Twin state, history, quality and qualified convergence.
- Versioned rules, guarded execution, arbitration, cooldown/hysteresis and backend dry-run.
- Durable telemetry intake, command outbox, fenced dispatch, retry and uncertain-delivery holds.
- Adapter contracts/SDKs, bounded model access and evidence-grounded assistant/analysis screens.
- Local Ollama with explicit consent and deterministic fallback; no AI activation/approval bypass.
- Three SIMULATED ESPs, infrastructure creation, typed telemetry and the updated navigation/rule builder.
- Separate REAL USB identities, reconstructed firmware/bridge, preserved ESP1 button sketch and project reports.

This is not a new certification. P6-241 and P6-242 remain PLANNED. A device ACK, qualified convergence and
independently observed physical effect are distinct evidence. The physical LED/servo issue is not certified fixed.

## Start

Prerequisites: Java 21 LTS, Node.js 22+, Python 3.11+, Docker Desktop/Compose.
Ports: PostgreSQL5432, MQTT1883, backend8080, frontend5173, demo8766; optional Ollama11434.

```powershell
cd D:\nexus
.\start.bat
```

For the interactive ESP demonstration, use a second terminal:

```powershell
cd D:\nexus
.\start_demo.bat
# On a fresh database only, explicitly provision before first use:
# .\start_demo.bat --provision
```

The migrated ignored configuration preserves existing device UUIDs. Do not launch duplicate app/bridge processes.
Stop the demo/bridge with Ctrl+C, then use `python stop_nexus.py` in this folder to stop the app.
Do not delete PostgreSQL/MQTT data or SQLite delivery journals to force recovery.

### Optional Ollama

Start your installed Ollama server separately; no model is downloaded automatically:

```powershell
.\start.bat --ollama --model llama3.2:3b --allow-local-evidence
```

Default startup is deterministic-only. Free sufficient memory before inference; the supervised inference smoke
retains its4GiB free-memory gate. The assistant explains stored platform evidence, not general chatbot answers.

### Optional physical USB bench

Read [USB instructions](usb-integration/README.md), close Arduino Serial Monitor/Plotter, and use the bench
launcher INSTEAD of start.bat when deliberately enabling the exact REAL-device allowlists:

```powershell
.\usb-integration\start_app.ps1
# In a second terminal:
python usb-integration/bridge.py --config usb-integration/config.local.json
```

For a lightweight simulation demo leave the USB bridge stopped. It never needs to be running to show the
SIMULATED ESPs. GPIO/software state and ACKs are not independent physical measurements.

## Open

| Screen | URL |
|---|---|
| Home | http://localhost:5173 |
| Three ESP demo | http://localhost:5173/simulation |
| Telemetry | http://localhost:5173/telemetry |
| Infrastructure creation | http://localhost:5173/infrastructure |
| Device onboarding | http://localhost:5173/framework/onboarding |
| Assistant/model status | http://localhost:5173/assistant |
| Rules/visual builder | http://localhost:5173/rules |
| Actions/delivery history | http://localhost:5173/actions |

The ESP demo is behavioural software simulation, not circuit simulation or calibrated gas evidence.
MQTT backlog can delay stored telemetry; inspect timestamps, not just Live badges.

## Layout and validation

`backend/`, `frontend/`, `simulator/`: platform, UI and original simulator.
`demo-simulation/`, `usb-integration/`: interactive model, firmware and serial bridge.
`sdk/`, `contracts/`, `infrastructure/`: SDKs, wire schemas and local services.
`architecture/`: contracts, acceptance ledgers and dated evidence.
`ARDUINO_TEST/` and root PDFs/DOCX files: preserved project/review materials.
Ignored `.nexus/`: runtime ownership, private configs, delivery journal, logs and recovery evidence.

Run focused tests first. `python validate_nexus.py --full` is an expensive separately controlled certification
gate, not implied by a passing build or this consolidation. Never rewrite existing migration checksums or reset
the development database to make startup pass. Existing evaluation/anomaly-quality failures remain documented.

**Local/private use only:** no general user authentication; do not expose anonymous MQTT or the app publicly.
