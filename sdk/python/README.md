# nexus_contract: local validator for the NEXUS device wire contract

Validates telemetry, command, acknowledgement and status payloads against `contracts/v1/` without Spring, PostgreSQL, Mosquitto or any network access. It is the schema authority for the contract and the oracle of the backend differential test (`WireContractParityTest`).

```
python -m nexus_contract validate --kind telemetry payload.json --topic nexus/devices/<id>/telemetry
python -m nexus_contract validate --kind command --profile esp32-legacy-v1 --reference-time 1767225600 command.json
python -m nexus_contract validate --kind status -            # standard input
python -m nexus_contract validate --kind telemetry --legacy payload.json
python -m nexus_contract versions
python -m nexus_contract schema-hashes [--write]
```

Run it from `sdk/python` (or put that directory on `PYTHONPATH`). Exit status: `0` accepted, `1` rejected (or `--require-all-layers` with a layer that could not run), `2` usage error. The output lists one line per layer and only fixed codes; payload content is never echoed.

## Layers

| Layer | Needs | Without the input |
|---|---|---|
| `version` | nothing | |
| `structural` | nothing | |
| `profile` | `--profile` | `NOT_APPLICABLE` (not requested) |
| `identity` | `--topic` (telemetry) | `NOT_PERFORMED` |
| `freshness` | `--reference-time` and a window (`--freshness-window` or the profile's) | `NOT_PERFORMED` |
| `capability` | `--capability-profile '{"type":"RELAY","channels":[1,2]}'` | `NOT_PERFORMED` |

`NOT_PERFORMED` is never a pass. The validator never reads the clock.

## Rules worth knowing

- Status is bare text: UTF-8, trimmed like `String.trim()`, then compared case-sensitively with `online` / `offline`. No JSON envelope, no version.
- A message without `contractVersion` is `legacy-v1`. `1.m` above the highest known minor validates as that minor, strictly on known fields; a non-empty `requires` is `UNSUPPORTED_REQUIRED_FEATURE`.
- The generic command contract is not narrowed to the LED-only ESP32 firmware; `--profile esp32-legacy-v1` adds exactly what the firmware accepts (boolean value, 32-bit integer timestamp, capability `LED`, absolute 30-second freshness).
- A document is one UTF-8 JSON object with unique member names; numbers such as `1.0` or `1e2` are not integers.

## Tests

```
python -m unittest discover -s tests -t .
python -m nexus_contract.corpus --check      # the differential corpus is current
```

Dependencies: `jsonschema` (see `requirements.txt` for the tested pins).

## nexus_device_sdk (P5.5)

A small client library for devices and simulators, built on the validator:

```python
from nexus_device_sdk import DeviceClient, CommandProcessor, HttpTransport, MqttTransport, InMemoryTransport

client = DeviceClient(device_id, HttpTransport("http://127.0.0.1:8080", device_id, key))   # or MqttTransport(paho_client)
client.publish_status(True)
sample = client.publish_telemetry("TEMPERATURE_SENSOR", 24.5, "CELSIUS")   # contractVersion 1.0 and a UUID messageId
client.redeliver(sample)                                                    # an identical redelivery, like QoS 1

processor = CommandProcessor({"LED": set_led})                              # firmware rules: SET_STATE, 30 s absolute freshness
processor.respond(command_bytes, client)                                    # applies the command and sends the acknowledgement
```

- Every message is validated locally with the same validator and fixed codes as the CLI before anything is sent (`ContractViolation`).
- The clock and the id source are injected; nothing reads the wall clock implicitly in the freshness rule.
- `HttpTransport` keeps the device key in memory and in the `Authorization` header only; it is never in a URL, a repr or an exception.
- `MqttTransport` wraps any client with `publish(topic, payload, qos=, retain=)`; the paho package is not imported by the SDK.
