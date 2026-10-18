# NEXUS device adapters: operator guide

Status: written at the end of Phase 5 (2026-10-18). Everything below was exercised by the automated tests named next to it, on a development
machine with disposable PostgreSQL and Mosquitto. Nothing here has been run against physical hardware, and nothing here claims it.

## 1. What exists

| Adapter | Enabled by | Serves | Can carry commands to the device |
|---|---|---|---|
| `mqtt-json` | always (the legacy path) | devices assigned `MQTT_JSON` (the default for every device) | yes (the Phase 4 outbox publishes; evidence `BROKER_CONFIRMED`) |
| `http-webhook` | `nexus.adapter.http.enabled=true` | devices assigned `HTTP_WEBHOOK`; they POST telemetry, acknowledgements and status | **no: ingest-only.** Command acceptance for such a device is refused with `EGRESS_NOT_SUPPORTED` |
| `simulator` | `nexus.adapter.simulator.enabled=true` | in-process test devices assigned `SIMULATOR` | the adapter can deliver to an in-process device, but command acceptance is refused with `DELIVERY_NOT_ROUTED` because the outbox dispatcher is not routed through it |

`GET /api/v1/adapters` lists the running adapters with their declared capabilities and counters (read-only, no secrets).

## 2. Enabling the HTTP adapter

1. Set a pepper of at least 16 bytes in the environment of the backend **and** of the credential tool: `NEXUS_DEVICE_KEY_PEPPER` (or
   `NEXUS_DEVICE_KEY_PEPPER_FILE` naming a file). Without it the adapter refuses to start. The pepper is never stored in the database or in the repository.
2. Put TLS in front of NEXUS. The backend refuses to start with credentials enabled on a non-loopback bind unless
   `nexus.adapter.http.require-forwarded-https=true` (then every request must carry `X-Forwarded-Proto: https`). Bind to `127.0.0.1` behind the proxy otherwise.
3. Optional limits (defaults in brackets): `nexus.adapter.http.max-body-bytes` [8192, 1024 to 65536], `nexus.adapter.http.per-device-concurrency` [2, 1 to 16],
   `nexus.adapter.http.contact-eligibility-window` [PT60S, 15 to 300 s], `nexus.adapter.http.contact-write-interval` [PT5S, 0 to 60 s].
4. Apply `backend/src/main/resources/db/operator/credential-roles.sql` once, as a database administrator (it is not a migration and is never applied
   automatically). It creates the login role `nexus_credential_admin` (create, rotate, revoke, list, audit credentials; nothing else) and gives the
   backend role read access to credentials and update access to the last-contact column only, so a compromised backend cannot mint a key.

Evidence: `HttpAdapterIntegrationTest`, `HttpAdapterUnitTest`, `CredentialToolIntegrationTest`.

## 3. Credential administration (local command only)

There is no network endpoint for credentials, by design (decision D7); `AdapterArchitectureTest` fails the build if one appears. Use the local tool
(`com.nexus.adapter.credentials.CredentialCli`), run as the operator on the database host with the pepper and the `nexus_credential_admin` login in the environment:

```
create --device <uuid> [--output-file <path>]
rotate --device <uuid> [--retire-after PT24H] [--output-file <path>]
revoke --credential <lookupId>
list   --device <uuid>
audit  --device <uuid>
```

* A key looks like `nxk1_<24 hex>_<43 characters>`. It is shown once (or written once to a NEW owner-only file with `--output-file`), is never stored,
  and cannot be recovered; lose it and rotate. The database keeps only an HMAC-SHA-256 of the secret part, keyed with the pepper.
* `rotate` keeps the old key working until `--retire-after` (1 minute to 7 days, default 24 hours) so a device can be updated without an outage.
* `revoke` is final and takes effect on the very next request (nothing is cached).
* `list` and `audit` show lookup ids, states and an append-only event trail; they never show a secret or a hash.
* A device sends the key only in the header `Authorization: Bearer <key>`. A key in a URL or query string is never accepted and never logged.

## 4. Assigning a device to a transport

`PUT /api/v1/devices/{id}/transport` with `{"transport":"HTTP_WEBHOOK"}` (also `MQTT_JSON`, `SIMULATOR`). The server-side assignment is the only thing
that selects the adapter; nothing in a payload can. Refusals: `400` unknown transport, `404` unknown device, `409` no adapter is registered for that
transport, `409` the device still has commands being delivered. A message arriving over the wrong transport for a device is refused and counted
(`TRANSPORT_MISMATCH`) and changes nothing.

An HTTP device that has made a valid, authenticated contact in the last minute is *eligible* in the connectivity sense. That is a statement about
contact, not about the device being online, and while HTTP is ingest-only it does not make commands deliverable.

## 5. Rolling back

Remove the two enable properties and restart. The V41 schema stays (it is additive; its columns have defaults), the HTTP routes and the simulator
disappear, no device can be assigned to a transport nothing serves, and every device that was never assigned behaves exactly as before V41.
Evidence: `AdapterRollbackIntegrationTest` (with an HTTP-assigned device, a credential row and the new columns present). Returning an assigned device to
`MQTT_JSON` is always allowed.

## 6. Adding a transport

The procedure is contract section 16.4 and is demonstrated by the test-only serial adapter:

1. Implement `AdapterDescriptor` and exactly the capability interfaces the transport really supports; declare exactly those (the registry stops the
   application at startup on a mismatch). Reach the core only through the intake ports; never import the Digital Twin, Rule or core service packages.
2. Add a `TransportKind` value and widen the V41 `CHECK` constraint in a new migration (never edit V1 to V41; their checksums are pinned).
3. Register the adapter as a bean behind an enable property; add its `PerTransportEligibility` rule, fail-closed until command delivery is routed and proven.
4. Write a conformance `Subject` and run the kit at UNIT and LIVE level (`ConformanceKit`, `AdapterConformanceTest`, `AdapterConformanceIntegrationTest`).
   A capability the adapter does not implement is reported NOT_SUPPORTED; that is correct, not a defect. Disagreements with the shared contract table are
   classified in `contracts/parity/known-differences.json` with a class and a reason; nothing is weakened to make a transport pass.
5. `AdapterExtensibilityTest` must stay green: it proves with `git diff` against the Phase 4 baseline that the Twin, Rule, telemetry and outbox packages
   were not touched and that the only core files changed outside `adapter/` are the six approved ones.

## 7. Validation commands

```
python validate_nexus.py --fast                 # tooling, contract and SDK tests, claims audit, backend compile, frontend, simulator
python validate_nexus.py --full                 # + the complete backend suite in sequential bounded batches, the Phase 5 smokes, the Phase 4 smokes
python -m unittest discover -s sdk/python/tests -t sdk/python     # contract validator, device SDK, ESP32 library host tests (needs g++)
python sdk/esp32/tools/gen_golden.py --check    # the golden command file is current
```

The full gate needs the normal application stopped and Docker available; it holds one Maven process at a time (about 1,400 MB heap) and waits when
free memory is below 1,200 MB. Never start the normal application (`start.bat`, `start_nexus.py`) during isolated validation.

## 8. Limits to remember

* HTTP command delivery (pull) is deferred: 25 ledger scenarios stay DEFERRED with their reasons; no workaround routes an HTTP-assigned command through MQTT.
* An acknowledgement is a device claim. A delivery fact names its evidence kind (`BROKER_CONFIRMED`, `HTTP_SERVED`, `SIMULATOR_DELIVERED`) and is never
  exactly-once delivery, device receipt or physical actuation.
* Authentication is device-adapter authentication only; there is no user or role model, and none of this is a claim that NEXUS is secure.
* The MQTT path still ignores `contractVersion` and `requires` (a registered difference): a future producer must not rely on them over MQTT.
* The ESP32 library and its samples were tested on the host only; they were not compiled with the ESP32 toolchain and never flashed.
