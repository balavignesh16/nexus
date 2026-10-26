# NEXUS USB hardware integration v1

Isolated from Phase 6 source changes. ESP1=COM5, ESP2=COM8, ESP3=COM10; no board Wi-Fi.
Original `.ino` source was unavailable. These are **reconstructions**, not recovered source.
Build/diagnostic/hardware evidence is separate from Phase 6 certification. No full gate.

## Files and hardware

* `firmware/ESP1_USB_v1/ESP1_USB_v1.ino`: PIR14, LED26, button33 active-low; 40 ms debounce;
  AUTO/FORCE ON/FORCE OFF cycle; AUTO retains light 3 s after last detected motion.
* `firmware/ESP2_USB_v1/ESP2_USB_v1.ino`: ADC34 through user-confirmed divider; RGB26/25/27 common
  cathode; relay driver13 HIGH=ON. Local 120 s initialization + 30 samples 100 ms apart; ADC deltas
  300/600, hysteresis100. NORMAL/WARNING/ALARM overrides last10 s; AUTO returns to sensor control.
  These are uncalibrated demonstration thresholds, never PPM or certified protection.
  USB/backend commands CANNOT mute/force this alarm. Serial loops are bounded so local logic continues.
* `firmware/ESP3_USB_v1/ESP3_USB_v1.ino`: servo13, blue LED12; startup90; only60/90/120 accepted.
  Local OPEN/CLOSE/CENTER/LIGHT commands and four combined scenes are retained. PWM uses a conservative
  1000–2000 microsecond full-scale mapping; requested angle is not independent position feedback.
  Check actual travel before attaching a load. GPIO12 strapping/restart behavior and USB power under load
  are NOT certified. Only brief, unloaded tests are authorized. No fan, battery or BME280.

`platformio.ini` explicitly applies user-confirmed ESP2 polarity and ESP3 power flags. Without these
flags the sketches leave their unknown circuits unarmed; an input/high-impedance relay pin is NOT
guaranteed to mean buzzer OFF. Do not flash an unconfigured safety-board build as an alarm replacement.

## Build and upload (one board at a time)

PowerShell from D:\nexus-p6. Close Serial Monitor/Plotter. Opening USB can reset a board even with
DTR/RTS deasserted; an ESP3 reset can move the servo to90. Keep travel clear and unloaded.

```powershell
$pio = 'C:\Users\balav\.platformio\penv\Scripts\platformio.exe'
& $pio run -d usb-integration/firmware -j 2
& $pio run -d usb-integration/firmware -e esp1 -t upload
# Verify ESP1 before continuing:
& $pio run -d usb-integration/firmware -e esp2 -t upload
# Verify local alarm and buzzer-OFF before continuing:
& $pio run -d usb-integration/firmware -e esp3 -t upload
```

Platform/toolchain and ArduinoJson are pinned. Three Arduino sketches share `common/` and have PlatformIO
wrapper sources; keep this directory layout rather than copying a lone .ino into another folder.

### Original flash preservation

`backup_flash.py` is read-only but enters the bootloader and interrupts the old local firmware temporarily.
It reads 64 KiB chunks with 1 KiB packets and ONE unacknowledged packet, validates each returned MD5,
and prints a final SHA256. This reduced-window mode follows Espressif's READ_FLASH protocol:
https://docs.espressif.com/projects/esptool/en/latest/esp32/advanced-topics/serial-protocol.html#reading-flash
The stock 64-packet reader failed during this session; that is not proof of a particular cable defect.
Partial files are NOT complete backups. Only a 4,194,304-byte file with a completion digest is a full backup.

```powershell
$pioPython = 'C:\Users\balav\.platformio\penv\Scripts\python.exe'
& $pioPython -u usb-integration/backup_flash.py --port COM5 --output .nexus/validation/ESP1-original.bin --baud 460800
```

Repeat with the other board/port before overwrite. A binary backup may contain credentials: keep it in ignored
validation storage, not Git or the frozen review pack. Do not use `--resume` across boards/images. Partial-prefix
checking is not a full identity proof; compare the recorded chip MAC and original capture before resuming.

## Passive capture and local checks

```powershell
& $pioPython usb-integration/diagnose.py --ports COM5 --seconds 8 --reset-risk-confirmed
```

Capture emits no serial command and never flashes. Logs are bounded to32 KiB and saved under ignored
`usb-integration/diagnostics/`. Verify each hello's board and chipId against its actual COM connection.
Keep human observations (PIR movement, button press, RGB color, buzzer sound, servo motion) separate from GPIO
request/state reports. Do not test with real gas/smoke. Do not interpret GPIO output state as physical verification.

## Versioned serial contract

115200 baud, UTF-8 JSON, one LF-terminated frame, max1024 bytes. No binary/legacy log parsing as telemetry.
Each device emits `serialVersion:1`, `board`, chipId, boot nonce and kind:

* `hello`: firmware version, supported capabilities, outputsReady, bound session/deviceId. Repeated every5 s.
* `telemetry`: monotonically increasing sequence, uptimeMs and typed readings. Before bind it has no UUID and
  the bridge drops it. MQTT timestamps are gateway receipt time, NOT independently synchronized sensor time.
* `ack`: exact commandId, deviceId, session, ACCEPTED/REJECTED and closed reason. ACCEPTED means software
  processed GPIO/PWM, **not** measured physical actuation.

Bridge replies with `bind` scoped to observed boot/board, configured UUID, session nonce and host epoch.
That host-derived clock enforces an absolute30 s command freshness window; it is not authenticated time.
Bind cannot change an existing session/UUID in the same boot; the same session may refresh time.
Backend MQTT commands are forwarded as an unchanged nested `command` object in a serial `command` envelope.
Only SET_STATE LED boolean (ESP1/3), and SERVO integer60/90/120 (ESP3) are supported. ESP2 accepts none remotely.

MQTT uses the existing Phase5 schemas and SDK: `nexus/devices/{UUID}/telemetry`, `/status`, `/commands`,
`/commands/ack`. No competing MQTT schema or backend command-creation service exists in the bridge.
No readbackCommandId is invented, and requested servo angle/LED GPIO state does not establish convergence.

Firmware remembers64 command results per boot and refuses new IDs when full instead of evicting old results.
The bridge's SQLite journal records an uncertainty barrier BEFORE each serial write. Across process/board resets,
an uncertain command is not resent; only an ACK actually received from the device can be replayed to MQTT.
The journal fails closed at100,000 rows; it is not silently purged. Never delete it just to retry an uncertain action.
Loss of USB can still leave an already accepted physical action unknown. There is no exactly-once guarantee.

## Registration and bridge startup

Use the REAL chipId values from the three actual hello frames in an ignored inventory JSON:
`{"ESP1":{"chipId":"..."},"ESP2":{"chipId":"..."},"ESP3":{"chipId":"..."}}`.
Do not invent chip IDs or reuse simulated registrations. The provisioner creates new REAL devices, typed
capability definitions and MQTT templates using existing APIs. Gas uses ADC_COUNTS, not the seeded PPM gas
definition. It does not alter existing devices, rules or templates. READ keys are prefixed usb_ to avoid silently
reinterpreting old rules. Only LED/SERVO WRITE capabilities are exposed where supported; no remote buzzer control.

```powershell
python -m pip install --target .nexus/usb-deps -r usb-integration/requirements.txt
python usb-integration/register_devices.py --space EXISTING_SPACE_UUID --inventory usb-integration/diagnostics/inventory.json
python usb-integration/bridge.py --config usb-integration/config.local.json
```

`config.example.json` deliberately fails closed until real UUIDs/chip IDs and physicalChecksConfirmed are set.
Provision just before starting the bridge; if onboarding times out, use its normal retry API/UI, never force database state.
Set per-device usernameEnv/passwordEnv entries to environment variable NAMES when the existing broker requires
credentials. No secrets are included in configuration or logs. Each device has its own connection, QoS1 offline
Last Will and command subscription. Retained actuator commands are dropped. Queues are bounded (16 serial,64 MQTT).
Serial read/write timeouts are0.2/0.5 s; heartbeat timeout10 s; reconnect delay2 s. Windows exclusive open fails on
occupied ports rather than stealing them. There is no serial retry of uncertain command effects.

## Dashboard and command truth

Start the app from this worktree with the explicit, default-deny REAL bench allowlists:

```powershell
cd D:\nexus-p6
.\usb-integration\start_app.ps1
# In another terminal, after the backend is ready:
python usb-integration/bridge.py --config usb-integration/config.local.json
```

Use `start_app.ps1 -Ollama` only when the local Ollama server is running and llama3.2:3b is installed.
The deterministic app and USB controls do not require a model. Close Arduino Serial Monitor/Plotter first.
Do not launch two bridge instances. Stop the bridge with Ctrl+C before restarting the app or uploading firmware.
The launcher scopes REAL LED dispatch to the configured ESP1/ESP3 UUIDs and servo dispatch to ESP3 only.
Without these allowlists REAL dispatch remains disabled. No remote ESP2 alarm/buzzer control is enabled.

Open http://localhost:5173/explorer, expand the configured site/building/space and select `USB-ESP3 Automation Room`.
The existing LED control appears from its authoritative WRITE capability. It uses the existing device-command API,
action history, MQTT publication and ACK handling; the bridge never accepts an independent HTTP command.
Telemetry and typed Twin views show actual backend persistence, including source mode REAL.
The ESP3 door controls request OPEN120/CLOSE60/CENTER90 through that same lifecycle. The bench policy and
firmware reject other angles. These are requested positions, not independently measured servo angles.

Check lifecycle at `/api/v1/actions/{commandId}/lifecycle`. Broker publication, device ACK, desired-state convergence
and physical effect are independent. A human observation can establish a bench LED test, not continuous independent
verification. No hardware sensor for servo angle, LED current or buzzer sound is installed.

## Focused checks and physical acceptance

```powershell
python -m unittest discover -s usb-integration/tests -v
& 'C:\Flex Windows\gcc\bin\g++.exe' -std=c++11 -Wall -Wextra -Werror usb-integration/tests/logic_test.cpp -o .nexus/validation/usb-logic-test.exe
& .\.nexus\validation\usb-logic-test.exe
```

Physical checklist (do not mark from a software assertion alone):

- [ ] ESP1 PIR and40 ms button cycle, AUTO3 s hold, LED force modes.
- [ ] ESP2 clean-air warmup/baseline, raw ADC, green/yellow/red indicators, buzzer off/on/off via local demo;
      return to AUTO after10 s; USB removal does not disable local logic.
- [ ] ESP3 unloaded startup90, LED on/off, bounded requested60/90/120 and combined scenes; no observed brownout.
- [ ] Three distinct REAL registrations, physical samples persisted and visible.
- [ ] Actual dashboard LIGHT ON command has correlated device ACK; human observes blue LED.
- [ ] USB cable removal/reconnect: truthful offline/online, no unsafe replay, backend remains running.

Power reliability, calibrated gas safety, independent physical verification and general device authentication remain
outside this bench integration. A software/mock disconnect test is not a physical cable-removal test.

## Narrow backend integration corrections

The initial real dashboard request was rejected by the previous blanket REAL dispatch guard; HTTP201 alone
was not success. The new opt-in UUID policy preserves capability, ownership, connectivity and lifecycle checks.
An onboarding admission rejection now uses the existing permanent rejection exception, avoiding an endless
MQTT retry without admitting the device or persisting the rejected reading.
MQTT connection is deferred until Spring context refresh installs the intake callback, and subscription work
runs off Paho's callback thread. This prevents queued persistent-session messages from blocking startup/ACKs.
Bridge telemetry uses the same canonical capability identity in both wire identity fields, sends changes
immediately and coalesces unchanged readings/status to five-second heartbeats.
These source changes require renewed integrated certification; focused tests are not a replacement for the full gate.
