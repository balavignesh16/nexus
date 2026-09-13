# NEXUS Tier 5A — ESP32 Temperature Sensor Firmware

This firmware turns a real ESP32 + DHT22 (or DHT11) into a physical
implementation of the exact same telemetry contract the Python simulator
(`simulator/`) already uses. **The NEXUS backend telemetry pipeline is
completely unmodified** — it already accepts this contract from any
transport, so no backend, frontend, or simulator changes were needed or
made for this phase.

## Scope (Tier 5A only)

Included: Wi-Fi connection, MQTT connection (with QoS-1 publish), NTP time
sync, DHT22/DHT11 temperature reading, telemetry publishing, Wi-Fi/MQTT
reconnect handling, serial debug logging, safe behavior on sensor-read
failure (skip the cycle rather than publish garbage).

Explicitly NOT included (by design — see the Tier 5 hardware audit):
automatic device discovery, a device-registration MQTT topic, a device
capability model, any command topic/execution/acknowledgement, any
actuator, MQTT Last-Will-and-Testament, OTA updates.

## Simulator/hardware parity

Both the Python simulator and this firmware produce **identical** wire
traffic — the NEXUS backend cannot tell them apart, by design:

| | Python simulator (`simulator/src/nexus_simulator/mqtt_publisher.py`) | ESP32 firmware (this directory) |
|---|---|---|
| Topic | `nexus/devices/{deviceId}/telemetry` | `nexus/devices/{deviceId}/telemetry` |
| QoS | 1 | 1 |
| `deviceId` | UUID string | UUID string (same field, same type) |
| `timestamp` | ISO-8601 UTC (`datetime.now(timezone.utc).isoformat()`) | ISO-8601 UTC (`strftime("%Y-%m-%dT%H:%M:%SZ")` after NTP sync) |
| `sensorType` | `"TEMPERATURE_SENSOR"` (for a `TemperatureGenerator`) | `"TEMPERATURE_SENSOR"` (exact match, deliberately) |
| `value` | `Double`/float | `Double`/float |
| `unit` | `"CELSIUS"` | `"CELSIUS"` |

## Firmware architecture

- **Toolchain**: PlatformIO, `espressif32` platform, `arduino` framework, board `esp32dev`.
- **Libraries** (pinned exact versions in `platformio.ini`):
  - `256dpi/MQTT @ 2.5.2` — chosen specifically because it supports **QoS-1 publish**; the more commonly used `PubSubClient` library does not (its `publish()` has no QoS parameter — QoS 0 only), which would have violated the NEXUS telemetry contract's QoS-1 requirement.
  - `bblanchon/ArduinoJson @ 7.2.1` — safe JSON construction.
  - `adafruit/DHT sensor library @ 1.4.6` + `adafruit/Adafruit Unified Sensor @ 1.1.14` — DHT22/DHT11 driver.
- **`src/main.cpp`** (single file, intentionally — no firmware framework at this scope): connects Wi-Fi, syncs UTC time via NTP, connects MQTT, then every `PUBLISH_INTERVAL_SECONDS` (default 5, matching the simulator's default) reads the DHT sensor and either publishes one telemetry message or skips the cycle if the sensor read failed or the clock isn't synced yet. Every state transition is logged to `Serial` at 115200 baud.

## Configuration (required before building/flashing)

1. Copy the template:
   ```
   cp firmware/include/config.h.example firmware/include/config.h
   ```
2. Edit `firmware/include/config.h` and fill in:
   - `WIFI_SSID` / `WIFI_PASSWORD` — your local Wi-Fi network credentials.
   - `MQTT_BROKER_HOST` — the **LAN IP address** of the laptop running the NEXUS backend and Mosquitto (not `localhost` — the ESP32 is a separate device on the network). Find it with `ipconfig` (Windows) and look for the Wi-Fi adapter's IPv4 address.
   - `MQTT_BROKER_PORT` — normally `1883` (matches `application.yml`'s default).
   - `NEXUS_DEVICE_UUID` — an **existing** NEXUS `Device` UUID, already created via `POST /api/v1/devices` with `deviceType: TEMPERATURE_SENSOR`, and with status `ACTIVE` (`TelemetryService` rejects telemetry for any device not `ACTIVE`).
   - `DHT_PIN` / `DHT_TYPE` — match your wiring below.

`firmware/include/config.h` is listed in `firmware/.gitignore` and must
**never** be committed — it is the only place real credentials live.

## Wiring (DHT22/DHT11 to ESP32)

| DHT22/DHT11 pin | ESP32 pin |
|---|---|
| VCC | 3.3V |
| GND | GND |
| DATA | GPIO 4 (or your chosen `DHT_PIN`) |

A 10kΩ pull-up resistor between VCC and DATA is recommended (many DHT22
breakout modules already include one — check your specific module).

No actuator, relay, or mains-voltage wiring is used in Tier 5A.

## Building and flashing

With [PlatformIO](https://platformio.org/) installed (`pip install platformio`, or the VSCode PlatformIO extension):

```
cd firmware
pio run                 # compile only
pio run -t upload       # compile and flash over USB
pio device monitor      # view serial log output (115200 baud)
```

## Validation performed as part of this implementation (no physical hardware available)

1. **Firmware build validation**: `pio run` was executed against the real ESP32 toolchain and the exact pinned library versions above, in an isolated PlatformIO environment. Result is recorded in the implementation report.
2. **Static contract verification**: the JSON payload constructed in `readAndPublishTelemetry()` and the topic constructed in `setup()` were manually traced field-by-field against `TelemetryRequest` (`backend/src/main/java/com/nexus/telemetry/api/dto/TelemetryRequest.java`) and `application.yml`'s `mqtt.topic.telemetry` pattern, confirming an exact match.
3. **No physical hardware validation was performed or is claimed** — no ESP32 or DHT22 was available at implementation time. The checklist below is the procedure to follow once hardware arrives; it has not yet been executed.

## Manual hardware validation checklist (to run once hardware is available)

1. **Provision a Device in NEXUS** (if not already done): `POST /api/v1/devices` with `deviceType: TEMPERATURE_SENSOR`, then confirm/set its status to `ACTIVE`. Note its `id` (UUID).
2. **Configure the firmware**: follow the "Configuration" section above, using that UUID.
3. **Wire the DHT22/DHT11** per the wiring table above.
4. **Flash**: `pio run -t upload` (ESP32 connected via USB), then `pio device monitor` to watch boot logs.
5. **Confirm Wi-Fi connects**: serial log shows `[WiFi] Connected. IP address: ...`.
6. **Confirm MQTT connects**: serial log shows `[MQTT] Connected.`.
7. **Confirm telemetry reaches Mosquitto directly** (bypassing NEXUS, to isolate the transport layer): on the laptop running Mosquitto, run
   ```
   mosquitto_sub -h localhost -t "nexus/devices/+/telemetry" -v
   ```
   and confirm a JSON message appears every `PUBLISH_INTERVAL_SECONDS`, matching the contract shown above.
8. **Confirm NEXUS accepts it**:
   - Check the backend log for `Successfully processed MQTT telemetry for device <uuid>` (from `TelemetryMQTTListener`).
   - `GET /api/v1/telemetry/latest?limit=5` and confirm a row with the device's UUID and a plausible temperature value.
9. **Confirm the full pipeline** end to end:
   - **Persistence**: the telemetry row from step 8 exists in `telemetry_history`.
   - **Digital Twin**: `GET /api/v1/devices/{deviceId}/twin` (or the equivalent digital-twin endpoint) reflects the new `latestValue`/`latestTimestamp`.
   - **Realtime UI**: open the NEXUS frontend's realtime/live view and confirm the device's temperature updates without a page refresh.
10. **Confirm reconnect behavior** (optional but recommended): briefly disable Wi-Fi on the ESP32's access point or power-cycle the ESP32, then confirm it reconnects and resumes publishing without a manual reset.

Do not report step 5 onward as complete until each has actually been observed against real hardware.
