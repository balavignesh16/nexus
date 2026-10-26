# Focused demo verification — 2026-10-21

- Five Python model tests passed: PIR hold/button edges, bounded sensor response/input rejection, gas hysteresis
  and non-mutable local alarm, finite servo slew/type validation, buzzer override expiry.
- 26 focused frontend tests passed (DeviceCommandControls and BackendRepository), including typed false/string/zero
  regression. TypeScript/Vite build passed; targeted new-page ESLint passed. Existing large-chunk advisory remains.
- Actual Playwright UI requests exercised nine actuator commands: ESP3 LED on/off and servo120/60/90, ESP1 LED
  on/off, ESP2 buzzer on/off. All nine recorded ACCEPTED simulated ACKs in the backend lifecycle API.
- Actual sensor inputs triggered PIR LED behavior and MQ-2 red/ALARM/buzzer state. All three devices were ACTIVE
  and SIMULATED, with persisted backend telemetry. Three existing USB UUIDs remained REAL.
- Actual UI created Review Demo Building and Review Room under NEXUS Live Demo. Onboarding selected that site,
  building and room from backend-loaded lists. No additional test-only device was provisioned into that room.
- Evidence: .nexus/validation/three-esp-demo/report.json plus infrastructure.png, onboarding-location.png,
  alarm-and-servo.png and current-ui.png. The final current-ui screenshot was visually inspected after correcting
  page contrast. The report combines completed actuator/sensor checks and a focused location-only rerun after
  correcting onboarding select accessible labels; it is not one uninterrupted passing full-suite run.

Historical failed attempts are not hidden: the first ACK was late, one command during simulator reconnect was
refused offline, and a model-state assertion was interrupted by an additional CLOSE request. Those action records
remain. Input traffic was reduced to five-second telemetry and the physical USB bridge was gracefully paused;
later command processing caught up. No offline/expiry/ACK guards were bypassed and no backend runtime code was
changed for this demo. Full load/performance certification has not been performed.

The app and simulator were left running. The three physical registrations and firmware remain intact; only the
USB bridge process is paused. No changes were made to D:\nexus, protected firmware/hardware/results, ROADMAP,
handoff or acceptance ledgers. The staging index is empty. No migrations/full gate/commit/merge/push/tag ran.

Limitations: behavioural simulation, not circuit-level or calibrated gas simulation; MQ-2 warm-up/baseline acquisition
is omitted and buzzer state is visual only. There is no physical actuation or independent physical evidence. New
model command deduplication is bounded to this process lifetime. Integrated certification must be refreshed for
the modified source revision; these focused checks do not certify the existing Phase 6 extensions.
