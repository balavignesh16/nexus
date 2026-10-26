# NEXUS — Hardware Components and Physical Integration Handoff

**Purpose:** Source-of-truth handoff for Claude Code / Claude while preparing the full NEXUS project report. Use this document to describe what hardware is available, what has actually been tested, what is proposed for physical integration, and what is still unverified. **Do not describe planned hardware behavior as already implemented or experimentally demonstrated.**

**Status:** Hardware bench bring-up underway; production-grade end-to-end ESP32 integration and physical actuation remain **planned / not verified**. NEXUS software Phase 3 acceptance tests are reported as completed, but those are **software tests**, not proof of physical hardware.

## 1. Project context and hardware objective

NEXUS is a cyber-physical, digital-twin-based monitoring, rules, decision and device-control system. The backend, frontend, simulation, MQTT command/acknowledgement flows, rule lifecycle, typed triggers, temporal reasoning, persisted ActionPlans, arbitration, and dry-run features have been developed and tested in software. The physical hardware stage aims to connect real ESP32 nodes and real low-voltage sensors/actuators to the **same backend-facing contracts used by the simulator**, rather than building a second, incompatible hardware API.

Intended high-level path:

```text
Real sensors → ESP32 firmware → Wi-Fi / MQTT → NEXUS ingestion
                                         ↓
                               Quality + Digital Twin
                                         ↓
                            Typed events / Rule Engine
                                         ↓
                       Arbitration + persisted ActionPlan
                                         ↓
                         Guarded command acceptance
                                         ↓
                     MQTT command to the correct ESP32
                                         ↓
                 ESP32 validates/executes low-voltage action
                                         ↓
                   Device ACK + reported-state telemetry
                                         ↓
                 NEXUS records command/verification states
```

The simulator should remain available and publish compatible telemetry / device semantics so the backend is not dependent on hardware being connected. The specific MQTT topic names, exact JSON schemas, QoS, version fields, authentication, command identity and ACK details must be taken from the **actual NEXUS repository**; none are asserted here.

## 2. Hardware inventory — confirmed available or stated as acquired

The hardware purchasing/build conversations identified the following inventory. Quantities are included only when previously specified.

| Component | Quantity / known details | Intended role | Evidence status |
|---|---|---|---|
| **ESP32 DevKit V1 / ESP-WROOM-32 boards** | **3 boards** stated in the hardware BOM | Wi-Fi microcontroller / physical NEXUS nodes | At least one ESP32 physically connected and detected; the other boards have not individually been demonstrated here |
| **BME280** environmental sensor | 1 listed | Temperature, humidity, atmospheric pressure | Available in BOM; no confirmed live sensor readings yet |
| **MQ-2** gas/smoke sensor module | 1 listed | Detect changes in combustible-gas/smoke sensor response | Available in BOM; not yet calibrated or verified in live NEXUS telemetry |
| **PIR motion sensor** | 1 listed | Motion/presence events | Available in BOM; hardware-to-NEXUS path not verified |
| **4-channel relay module** | 1 listed | Four independently commanded switching channels, subject to safe electrical compatibility | Available in BOM; no confirmed physical channel actuation, load switching or relay logic-level validation |
| **SG90 micro servo** | 1 listed | Low-voltage positional actuator, e.g. demonstrating a command-to-motion path | Available in BOM; movement through NEXUS unverified |
| **5 V DC fan** | 1 listed | Low-voltage on/off actuator for demonstration | Available in BOM; no confirmed driven control path |
| **LED module / LED test load** | 1 module listed | Visual output and GPIO verification | **GPIO26 LED blink test confirmed** on one ESP32 |
| **Active buzzer** | 1 listed; observed two-pin buzzer | Audible alert | **No audible success confirmed**; positive lead connected to GPIO26 in one test, buzzer stayed silent |
| **Pushbuttons** | **5** stated in BOM | Manual input / local override demonstrations | **A button was confirmed functional on GPIO33** with `INPUT_PULLUP` |
| **Laptop / Windows computer** | Windows 11 development machine | Programming, serial debugging, running NEXUS and bench tests | ESP32 connected through USB; programming interface observed |
| **USB power bank** | OnePlus power bank, USB-A 5 V output shown in previous discussion | Potential separate 5 V source for actuators | Discussed and inspected; **do not assert completed safe actuator wiring** |
| **Power connection adapters** | Micro-USB breakout discussed; soldering tools available | Exposing regulated 5 V and GND for bench distribution | Discussed, but installed wiring and load rating **not verified** |

**Do not automatically add** BH1750, MQ-135, leak sensor or OLED to the *actual BOM*. These appeared in older conceptual roadmap examples but are **not confirmed present** in the later concrete inventory.

## 3. Physical tests actually demonstrated

1. **ESP32 USB connection:** A board enumerated on the Windows laptop as **Silicon Labs CP210x USB-to-UART, COM5**. This establishes a serial/programming interface on that board, not MQTT connectivity.
2. **Button input:** A button connected on **GPIO33** was reported working with the ESP32 internal pull-up configuration (`INPUT_PULLUP`); a press is interpreted as active LOW. That pin is confirmed for the button bench test, not necessarily locked for the final firmware mapping.
3. **LED output:** **GPIO26** was used successfully to blink an LED. This proves basic firmware execution and GPIO switching on that board.
4. **Buzzer troubleshooting:** A two-pin buzzer was connected with its positive side to GPIO26 in an attempted test. It did **not** sound. The prior diagnosis considered whether the unit needed 5 V and/or a transistor driver. **Do not claim the buzzer works**, and do not treat an ESP32 GPIO as a 5 V power source.
5. **Power plan:** Laptop USB was used for the initial ESP32 setup. An external USB power bank was discussed for supplying actuator current, but no fully validated common-ground, current-capacity and protected-wiring assembly is documented.

**Not yet physically proven:** BME280 measurements; PIR detections; MQ-2 readings; servo motion; fan switching; any relay channel actuation; ESP32 Wi-Fi/MQTT telemetry into NEXUS; device command reception; physical ACK; reported-state convergence; full rule-to-actuator round trip.

## 4. Component-specific integration plan (proposed, not verified)

### 4.1 ESP32 controllers

- Flash NEXUS-compatible firmware onto each physical ESP32 with a unique **device identity** and configured Wi-Fi/MQTT connectivity.
- Attach individual devices and capabilities using existing NEXUS device registry, installed bindings and unit contracts; do not fabricate device registrations or declare three operational nodes without testing them.
- Use backend-approved telemetry types, timestamps / receipt semantics, quality flags and capability keys.
- Subscribe only to device-authorized MQTT command channels, validate target and action shape, and return command outcomes in the repo's established ACK/verification format.
- Handle loss of Wi-Fi, MQTT reconnect, device restart and safe actuator startup states.

### 4.2 BME280 — environmental sensing

- Planned readings: temperature, relative humidity and atmospheric pressure.
- Typical physical interface: **I²C** (SDA/SCL); common ESP32 defaults are GPIO21 (SDA) and GPIO22 (SCL), **but these are suggested, not verified project wiring**.
- Power and pull-up requirements depend on the *specific BME280 breakout board*. Check labels and whether the breakout has a regulator/level shifting before connecting. ESP32 GPIO signals are 3.3 V.
- Convert measurements to NEXUS capability values with explicit, accurate units and source timestamps; preserve invalid/no-reading quality rather than fabricating zero.

### 4.3 MQ-2 — gas/smoke sensor

- Intended as a **qualitative or calibrated gas/smoke response** source; a bare MQ-2 analog number is **not automatically ppm** and does not identify a specific gas reliably.
- The module often requires a 5 V heater and significant warm-up; verify the actual board datasheet and current demand.
- The ESP32 ADC is **3.3 V maximum**. A 5 V analog output **must not connect directly** to an ESP32 input. Use a properly designed voltage divider/level-conditioning circuit (values and maximum ADC voltage verified with a multimeter), or use a verified 3.3 V-compatible digital path.
- A proposed ADC pin was GPIO34, but **no actual MQ-2 connection has been verified**.
- Start with baseline observation and safe bench conditions, not intentional hazardous gas releases or safety-critical alarm claims.

### 4.4 PIR — motion detection

- Intended to generate motion-active / motion-inactive readings or events.
- A proposed ESP32 input was GPIO32, **not confirmed as final wiring**.
- Check whether the exact PIR module output voltage stays within 3.3 V before connecting it to ESP32 GPIO.
- Distinguish motion signal events from guaranteed human occupancy; allow settling / warm-up as required.

### 4.5 Pushbuttons and LED

- Confirmed button bench GPIO: **GPIO33, `INPUT_PULLUP`, active LOW**.
- Confirmed LED bench GPIO: **GPIO26**.
- A later multi-component layout must avoid assigning GPIO26 simultaneously to LED and buzzer. Any alternative pin map is **provisional** until wiring is physically verified.
- Button functionality may be mapped to a local/manual input or a NEXUS manual-control signal only when the firmware-to-backend integration is implemented and tested.

### 4.6 Active buzzer

- Intended for an audible alert on a permitted rule outcome.
- Attempted direct GPIO26 test failed; voltage, current draw, active/passive type and polarity still require verification.
- Use a 3.3 V logic-compatible transistor/MOSFET switching circuit and a correctly rated regulated buzzer supply if needed. Do not drive a 5 V or high-current buzzer directly from the GPIO.
- Do not report this actuator as operational yet.

### 4.7 SG90 micro servo

- Intended demonstration of position commands (e.g. a setpoint or angle) if accepted by the installed capability contract.
- Requires appropriate separate low-voltage power with adequate transient current; **do not feed servo current from the ESP32 3V3 pin**.
- Servo control is usually a 3.3 V-compatible PWM signal, but actual pin and circuit are not confirmed. GPIO27 was **proposed only**.
- The ESP32 and servo supply must share a safe common reference ground when powered separately; never tie unrelated 5 V positive rails together.
- Software should constrain motion range, avoid mechanical stalls and record actual feedback truthfully. A command acknowledgement alone is not proof the shaft moved.

### 4.8 Five-volt fan

- Demonstrates a controlled low-voltage output. Supply the fan from a regulated rated 5 V source via a correctly selected transistor/MOSFET driver with appropriate protection for an inductive load.
- Do not power or switch the fan directly from ESP32 GPIO.
- Feedback is not inherently available without an appropriate sensor; report the commanded state separately from proven fan rotation.

### 4.9 Four-channel relay

- Targeted use: demonstrate **distinct channel identities** (1–4) and independent command/ownership behavior in NEXUS. Phase 3 software tests already exercised separate RELAY channel identities using a configured software binding; **that is not evidence of physical relay switching**.
- Determine the module's coil supply, active-HIGH/LOW input polarity, 3.3 V input compatibility, isolation behavior and per-channel ratings from its label/datasheet.
- Use only **safe isolated low-voltage loads for student bench demonstrations**. Do **not** wire household mains AC or exposed line-voltage equipment based on this document.
- Do not assume the module can be powered from ESP32 3V3 or that 3.3 V GPIO will reliably drive an unspecified 5 V relay board.
- Verify off-state at boot/restart and distinguish command accepted, relay clicked, switched output measured and downstream appliance function.

## 5. Power and electrical design — safety boundaries

**Initial setup:** ESP32 via laptop USB. **Proposed expanded setup:** suitable regulated 5 V external supply/power bank for appropriate modules, fan/servo/buzzer/relay where specifications require it; sensor voltages handled per actual breakout specifications.

Important rules:

- ESP32 GPIO and ADC inputs are **3.3 V domain**. Never place 5 V directly on these pins.
- Use regulated power supplies with sufficient current headroom for startup and servo/fan transients.
- Maintain common GND for separately powered control signals where required, while avoiding positive-rail backfeed or accidental short circuits.
- Avoid feeding the same ESP32 board from two uncontrolled 5 V positive sources at once.
- Do not connect loose lithium-ion cells directly to modules without appropriate charging/protection/regulation.
- Use a multimeter before energizing new wiring; verify voltage, polarity and shared ground.
- A small USB breakout board is **not automatically rated for total actuator current**; check traces/connectors and add appropriate wiring, strain relief and protection.
- Keep relay tests to **low-voltage isolated loads**; mains switching is excluded from this integration plan.

## 6. NEXUS software ↔ hardware integration workflow

### 6.1 Telemetry and digital twin

1. Register real ESP32 device(s) and installed capabilities in NEXUS using actual backend-supported workflows.
2. Match firmware's device ID and capability keys to registry bindings; use strict types and unit provenance.
3. Publish sensor observations over the **repository-defined MQTT interface**. Do not make up topic names or JSON keys for a formal report.
4. Backend ingests and validates observations, associates receipt/quality and updates the authoritative Digital Twin only when eligible.
5. Rules evaluate on immutable versions and frozen contexts; typed trigger routing and temporal evidence remain provenance-preserving.
6. Dashboard reflects observed state, quality and connection/verification status, not imaginary real-world convergence.

### 6.2 Commands and actuator control

1. An eligible trigger causes an attributed RuleMatch.
2. Candidates go through unified arbitration and a persisted ActionPlan.
3. Guarded authorization checks the selected candidate, pinned version/epoch, device target, parameters and applicable source currentness.
4. Accepted commands are published by the existing NEXUS software to the device's authorized MQTT command path.
5. ESP32 firmware independently validates incoming command target/action, applies a safe low-voltage actuator state and emits the repository-defined acknowledgement / reported state.
6. NEXUS maintains separate records for: **plan approved → command accepted → published → ACK received → reported readback/verification → physical actuation actually observed**.

No one stage should be presented as proof of a later stage. In particular, a published MQTT message or ACK does not verify fan spin, servo movement, buzzer sound or relay contact closure.

### 6.3 Simulator/real-hardware compatibility

The simulator and ESP32 firmware should use compatible device identity, capability, telemetry, command and acknowledgement contracts. Validate this against the actual repo implementation. A hardware node should be substitutable for a simulated node at the integration boundary without changing backend rule logic.

## 7. Proposed bench integration sequence (NOT a completed test record)

| Phase | Planned task | Evidence required to mark complete |
|---|---|---|
| H0 | Record model, breakout pin labels, module ratings and final power budget | Photographs/labels, measurements, reviewed wiring diagram |
| H1 | Flash and confirm ESP32 firmware/serial diagnostic | Successful upload, serial log, stable boot |
| H2 | Button and LED local tests | **Already demonstrated** GPIO33 button and GPIO26 LED; repeat on finalized pin map |
| H3 | BME280, PIR and MQ-2 separately | Timestamped actual readings, correct units, measured input-voltage safety |
| H4 | Power and driver circuits for buzzer, servo, fan and relay | Voltage/current measurements and safe boot state |
| H5 | Each actuator locally from ESP32 firmware | Video/observation, serial logs, actuator-specific measurements |
| H6 | Real ESP32 publishes accepted MQTT telemetry | Broker capture and NEXUS ingest/quality/Twin log |
| H7 | NEXUS command to ESP32 and device ACK | Correlated accepted command, MQTT command, device receipt and ACK |
| H8 | Physical actuator verification | Actual LED/buzzer/servo/fan/relay observable outcome and qualified feedback |
| H9 | Offline/reconnect and negative safety cases | Timeout, disconnected device, invalid command, reboot and no-unsafe-actuation proof |
| H10 | End-to-end rule-driven demonstration | Sensor→Twin→rule→plan→acceptance→MQTT→actuation→reported readback evidence |

## 8. Suggested report structure and claims discipline

In a full project report, organize hardware material as:

1. **Hardware architecture and system-level integration objective** — the proposed bridge from physical readings to NEXUS software.
2. **Bill of materials** — distinguish acquired components from any unpurchased future additions.
3. **Component functions and electrical interfaces** — ESP32, sensors, actuators and relay, including power/signal constraints.
4. **Actual prototype bring-up** — COM5, GPIO33 input, GPIO26 LED; unsuccessful buzzer experiment and troubleshooting.
5. **Planned integration protocol** — repository-verified MQTT schemas and bindings, command acknowledgements and verification states.
6. **Electrical and operational safety** — 3.3 V GPIO, conditioned analog inputs, regulated separate actuator power and low-voltage relay loads.
7. **Bench-test matrix** — identify which tests have actual evidence and which are future work.
8. **Limitations/future work** — no physical end-to-end integration demonstrated yet.

**Approved example of accurate wording:**

> “A prototype ESP32 board was recognized over its CP210x USB-to-UART interface on COM5. Local bench tests verified a GPIO33 pull-up button input and GPIO26 LED blinking. BME280, MQ-2, PIR, SG90, 5 V fan, active buzzer and four-channel relay hardware were identified for the next integration stage. End-to-end MQTT-to-physical-actuation verification remains planned.”

**Do not write:** “All sensors successfully transmit real-time data to NEXUS,” “The gas monitor is calibrated in ppm,” “The relay controls real appliances,” “The servo and fan are fully operational,” or “All commands were physically verified,” unless new actual measurements/logs demonstrate those statements.

## 9. Source verification tasks for Claude Code

Before finalizing the report from this handoff, inspect the local NEXUS repository to fill in project-specific protocol fields **without guessing**:

- Device-registration and installed-capability API / DB model.
- Firmware source files (read only), if they exist, and current compile/flash status.
- Actual MQTT topic patterns and message schemas for telemetry, command and ACK.
- DomainEvent, typed trigger, RuleMatch, persisted ActionPlan and RuleExecutionAuthorization relationship.
- Quality, time/receipt, units and installed-binding validation.
- Device connectivity and timeout behavior.
- RELAY `channel` targeting and action payload schemas.
- MQTT tests / simulation evidence versus physical hardware evidence.
- Whether any official GPIO/pin mapping or circuit diagram has been committed; otherwise leave it as TBD.
- Current software validation counts if included in the report; cite the latest committed validation ledger instead of treating them as physical tests.

## 10. Unknowns requiring physical confirmation

- Final board models and revision of each of the three ESP32 modules.
- Exact MQ-2, PIR, BME280 and 4-channel relay breakout revision and their signal/power specifications.
- Final pin allocation for multiple sensors and actuators on each ESP32.
- Final topology: which of the three ESP32 boards owns which components.
- Regulated power distribution, current budget and verified wiring diagram.
- Buzzer voltage/current and confirmed switching circuit.
- Real Wi-Fi credentials, broker endpoint and device certificates/secrets — **never include secrets in the project report**.
- Actual firmware flashed and real telemetry/ACK logs.
- Captured evidence of servo, fan, buzzer and relay function.
- A safe physical feedback mechanism suitable for qualifying convergence/actuation.

---

### Instruction to the report-writing assistant

Use this file as the **hardware inventory + integration intent + existing bench-evidence** source. Supplement technical integration claims by reading the repository and any real photographs/test logs the user supplies. Label unconfirmed physical results as **planned**, **proposed** or **not yet validated**. Avoid copying generic NEXUS software validation claims into the hardware experiment-results section. Preserve the safety restrictions concerning 3.3 V GPIO, separate actuator power, relay voltage compatibility and no mains switching.
