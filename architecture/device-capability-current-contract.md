# Current device and capability contract

This is the P1.1 compatibility snapshot. It describes the runtime immediately
before generic capability cutover. P1.2 adds persistent definitions beside this
contract; it does not replace it.

## A. Device identity

A device is a UUID-backed row in devices, belongs to one Space, has a unique
serial number, lifecycle status, DeviceType, and SIMULATED/PHYSICAL mode. One
ESP32 is one Device and may own many DeviceCapability rows. Simulator serials
are stable: SIM-{device-name}.

## B–D. DeviceCapability identity, type, and direction

DeviceCapability is a UUID-backed row unique by
(device_id, capability_type, direction). CapabilityType is the closed Java enum
TEMPERATURE, HUMIDITY, MOTION, LED, PRESSURE, GAS, BUTTON, RELAY, SERVO, FAN,
and BUZZER. Direction is READ or WRITE. Optional unit and JSONB metadata belong
to the device-specific row. Capabilities are provisioned explicitly and are
never inferred from telemetry.

## E. Current value representations

| Capability | Direction in simulator fixture | Wire/runtime value | Unit/constraint proven by code |
|---|---|---|---|
| TEMPERATURE | READ | number | CELSIUS |
| HUMIDITY | READ | number | PERCENT |
| PRESSURE | READ | number | HPA |
| GAS | READ | number | PPM |
| MOTION | READ | boolean generator, numeric telemetry DTO boundary | BOOLEAN |
| BUTTON | READ | boolean generator, numeric telemetry DTO boundary | BOOLEAN |
| LED | WRITE | boolean | BOOLEAN, SET_STATE |
| FAN | WRITE | boolean | BOOLEAN, SET_STATE |
| BUZZER | WRITE | boolean | BOOLEAN, SET_STATE |
| SERVO | WRITE | integer/number | DEGREES, 0–180, SET_STATE |
| RELAY | WRITE | object {channel,state} | channel must be provisioned; boolean state; SET_STATE |

The current telemetry DTO stores value as double. Simulator boolean generators
therefore pass through the existing publisher/runtime conversion rather than a
generic typed telemetry contract. This limitation is intentionally not changed
in P1.2.

## F–I. MQTT topics and payloads

Exact topics:

- telemetry: nexus/devices/{deviceId}/telemetry
- retained birth/LWT status: nexus/devices/{deviceId}/status
- commands: nexus/devices/{deviceId}/commands
- acknowledgements: nexus/devices/{deviceId}/commands/ack

Telemetry:

    {"deviceId":"uuid","timestamp":"ISO-8601","sensorType":"TEMPERATURE","value":24.5,"unit":"CELSIUS"}

Status payload is the literal online or offline.

Command:

    {"commandId":"uuid","deviceId":"uuid","capability":"LED",
     "command":"SET_STATE","value":true,"timestamp":1735689600}

Relay command value:

    {"channel":1,"state":true}

Acknowledgement:

    {"commandId":"uuid","status":"ACCEPTED","timestamp":1735689600}

Rejected ACKs use status REJECTED and may include reason MALFORMED, UNSUPPORTED,
or STALE. The topic device ID is authoritative and commandId correlates to
persisted ActionHistory.

## J–K. Rules and Digital Twin identity

RuleCondition optionally references CapabilityType. Multi-capability evaluation
reads the current Twin state for the rule device and enum capability. Existing
rules without capabilityType retain legacy event-payload behavior.

Telemetry sensorType is a separate free-form string vocabulary. Digital Twin
rows are keyed by (device_id, latest_sensor_type); no code currently translates
CapabilityType to sensorType. This separation is a critical compatibility
invariant.

## L. REST contracts

- GET/POST /api/v1/devices/{deviceId}/capabilities
- DELETE /api/v1/devices/{deviceId}/capabilities/{capabilityId}
- GET /api/v1/devices and /devices/active embed capabilities
- POST /api/v1/telemetry accepts deviceId, timestamp, sensorType, double value, unit
- POST /api/v1/devices/{deviceId}/commands accepts capability, command, value
- Twin endpoints return one device-level response with reportedState keyed by
  telemetry sensorType and compatibility latest-value fields.

Device capability JSON uses id, deviceId, capabilityType, direction, unit, and
metadata. Simulator provisioning and frontend models consume that exact shape.

## M. Database contract

Relevant tables before P1.2 are devices, device_capabilities,
telemetry_history, digital_twins, device_connectivity, rules, rule_matches,
events, action_history, and pipeline_traces. device_capabilities has a foreign
key to devices with cascade delete and a uniqueness constraint on
(device_id, capability_type, direction). V13 changed Twin primary identity to
(device_id, latest_sensor_type). P1.2's V15 migration is additive.

## N. Capability-coupled source locations

Backend coupling:

- device/capability: CapabilityType, DeviceCapability, repository, service,
  request/response DTOs, duplicate exception
- actions: CommandExecutor and CommandValueValidator
- rules: RuleCondition, RuleEvaluator, CapabilityValueKind, RuleRegistry, AI
  explanation projection
- twins: DigitalTwinId and DigitalTwinService
- device type and API response aggregation

Frontend coupling:

- api/deviceApi.ts, api/ruleApi.ts, types/models.ts
- DeviceCommandControls and DeviceCapabilitiesList
- Explorer CreateRuleModal, RuleDetailModal, Actions/Rules/Twin/Event tabs
- Rules, Telemetry, Twins, Actions, Events, Analytics pages
- realtime normalization/synchronization and DemoRepository
- associated tests and fixtures

Simulator coupling:

- config/devices.yaml and provision_devices.py
- devices.py, generators.py, actuators.py, command_handler.py, scheduler.py,
  mqtt_publisher.py
- grand_simulation.py and simulator helper/evidence scripts
- simulator device, integration, and command-handler tests

The global audit command used was a repository-wide search for CapabilityType
and all eleven enum constants; the paths above group every resulting production
location. Test locations mirror these same contracts.

## O. Compatibility invariants

1. CapabilityType and current DeviceCapability storage remain operational.
2. Existing enum names, directions, REST shapes, topics, and payload fields do
   not change in P1.2.
3. The three configured simulator devices keep their exact capability split.
4. sensorType remains distinct from CapabilityType/key.
5. Existing rule JSONB and capability references remain readable.
6. Command validation and relay channel metadata remain device-specific.
7. Existing device/capability IDs and all historical records remain unchanged.
8. The new CapabilityDefinition API does not imply arbitrary definitions can
   yet traverse telemetry, Twin, Rule, command, or simulator paths.
