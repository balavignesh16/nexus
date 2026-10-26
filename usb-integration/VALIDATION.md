# USB integration bench evidence — 2026-10-21

Worktree: D:\nexus-p6, feature/p6-intelligence. No commit, merge, push, tag or full gate was performed.
Existing Phase 6 UI/model changes and certification documents were preserved. This is focused bench evidence,
not a new Phase 6 certification or a physical safety certification.

## Delivered and exercised

- Three separate versioned sketches under firmware/ESP1_USB_v1, ESP2_USB_v1 and ESP3_USB_v1.
- All three PlatformIO builds and actual uploads passed. Upload logs: .nexus/logs/usb-esp1-upload.log,
  usb-esp2-upload.log, usb-esp3-upload.log; final build log usb-firmware-final-build.log.
- Local serial checks: ESP1 3, ESP2 5 and ESP3 11 passed software-state assertions. Captures are in diagnostics/.
- Focused backend run: 88 tests, zero failures/errors/skips, 13.481 seconds; .nexus/logs/usb-final2-backend-tests.log.
- DeviceCommandControls: 16 frontend tests passed; TypeScript/Vite production build and targeted ESLint passed.
- Bridge: 10 Python tests passed. C++ local behavior tests compiled with warnings as errors and exited zero.
- Live app restarted from this worktree, frontend 5173, backend 8080; PostgreSQL healthy, MQTT connected.
  Docker had been stopped and was restarted. Ollama was unavailable; deterministic mode was used honestly.
  Flyway was disabled for this verification restart; no migration/reset or direct SQL was run.
- All three actual devices have fresh API-persisted telemetry. ESP2 raw gas input was 3 ADC_COUNTS at the
  sampled instant, not PPM or a safety judgement. ESP1 motion and ESP3 outputs-ready are typed booleans.

## Actual dashboard → MQTT → USB → device ACK → lifecycle

Playwright drove the real Explorer UI without intercepting/faking API responses. All five requests recorded
ACK ACCEPTED and execution SUCCESS in the existing lifecycle API:

| ESP3 request | Command ID |
|---|---|
| LED ON | 11f8c19f-8f36-4580-bbdc-afe9277afc5e |
| LED OFF | 939697c1-19a9-48d5-8691-b191565df5f4 |
| OPEN 120 | 0b442f1f-9f97-452c-a0ee-6411f5c11ca2 |
| CLOSE 60 | 65482806-8d53-4a39-b359-409198b576e8 |
| CENTER 90 | 775ce245-a4e2-4b56-8ecb-fb18c97f542a |

Evidence: .nexus/validation/usb-ui-live/report.json, servo-report.json and screenshots.
Final requested state: LED OFF, servo CENTER90. Device ACK means firmware processed GPIO/PWM;
neither ACK nor execution SUCCESS independently measures emitted light, shaft angle, or mechanical motion.
The initial rejected REAL command d8bea5be-670b-4348-851c-c5f72a06f573 remains a historical FAILURE,
not a success. The corrected run used explicit per-device bench allowlists, never SIMULATED provenance.

## Registered physical identities

| Board/port | Chip ID | REAL NEXUS UUID |
|---|---|---|
| ESP1 COM5 | c0f75b0ff0a4 | 72f63d6a-073c-4c61-a786-1a3493a8ef93 |
| ESP2 COM8 | 5c363a077000 | 0fd83245-aebc-46fe-94df-1de455001686 |
| ESP3 COM10 | 8c07e3077000 | cf922ed7-eca6-449c-ba48-498c2917b5df |

Normal provisioning/onboarding APIs were used. No remote ESP2 alarm control exists. Local alarm processing
does not depend on USB/backend. The bridge's durable uncertainty journal must not be deleted to force retries.

## Preserved original flash

Full 4 MiB backups reside in .nexus/validation/usb-original-flash-20261021, not Git:

- ESP1-chunked.bin: 99f038dd763a44d9725551c2f42faf1ae0b7a24f822a51eade3a53ad14f657b9
- ESP2-original.bin: bdf5b083db4094229a9b4994cb1c60005ae6eae88776d3f781ec2ddb48231c3d
- ESP3-original.bin: acfe94962cb0a3003e0372ea68ad2283ba6c0032bb769c1e09b80d4d09010c71

These binaries may contain old credentials; do not publish them. They are backups, not recovered source sketches.

## Scope and remaining limitations

Beyond usb-integration, changes are restricted to CommandExecutor/UsbRealCommandPolicy, telemetry admission
exception handling, MQTT initialization/reconnect/subscription ordering, their focused tests and the existing
DeviceCommandControls component/test. Preexisting model/UI changes remain present and were not overwritten.
Protected firmware/, hardware-tests/, results/, ROADMAP.md and CODEX_HANDOFF.md have no changes in this
worktree. D:\nexus was only inspected read-only; its preexisting dirty files were not edited.
The staging index remains empty. No acceptance-ledger closure or certification assertion was changed.

Still pending: independent human LED/servo/RGB/buzzer observations; physical PIR/button exercises; actual
USB cable removal/reconnection with observation of offline/online and no unsafe replay; sustained power/load
and GPIO12 reboot reliability. Serial process reopen and fresh telemetry recovery do not prove a cable test.
No real gas/smoke, fan, batteries or calibrated concentration tests were used. Memory was approximately
1.70 GiB free after the bounded UI checks; do not launch full validation or load a model under pressure.
Frontend build retains a large-chunk advisory. These integration source changes require fresh integrated
certification when separately authorized; existing Phase 6 evidence is not proof for the new revision.
The inspected live screenshot shows controls and Acknowledged, but the existing Explorer header's connectivity
projection remains UNKNOWN while its System Health card shows ONLINE. Firmware/uptime summary fields are
unavailable there. This UI summary inconsistency is not treated as proof of disconnection or full UI completion;
the API-persisted readings and correlated ACKs provide the narrower integration evidence above. Devices were
placed in the existing hierarchy named NEXUS Simulator Environment; each device's actual mode remains REAL.
