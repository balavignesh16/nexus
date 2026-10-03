# P1.7 live browser acceptance

`p17-live.mjs` drives the real Vite UI in headless Microsoft Edge with
Playwright. It does not mock or intercept HTTP, MQTT, or backend state. The
companion `../../../tests/e2e/p17_mqtt_device.py` is a simulated MQTT device
that fetches its topics and capability metadata from the backend contract.

Run only against a disposable NEXUS stack: PostgreSQL and Mosquitto with
separate, temporary data stores; a real backend configured to use that DB and
broker; and Vite on port 5173. The browser uses the backend on port 8080. Seed
one Site → Building → Space prerequisite in that isolated database, then set:

```powershell
$env:E2E_SUFFIX = '<unique numeric suffix>'
$env:E2E_SITE_ID = '<site UUID>'
$env:E2E_BUILDING_ID = '<building UUID>'
$env:E2E_SPACE_ID = '<space UUID>'
npm run test:e2e:p17
```

The MQTT device expects the isolated Mosquitto listener on port 11883 and the
simulator Python environment at `simulator/.venv`. Playwright launches the
pre-installed Edge browser (`msedge` channel); no browser download is needed
on the validated Windows machine. Use `npm ci` to install the locked browser
automation dependency.

Screenshots and JSON evidence are written to the ignored
`.nexus/e2e/<suffix>/` directory. The script asserts browser-created
CapabilityDefinition, template, provisioned Device, contract, onboarding
states, typed Twin, persisted numeric `GT` Rule, MQTT command, and accepted
ACK. It also checks the Rules and Actions UI. Database verification is a
separate read-only step, not a substitute for the browser actions.

Never run this against the normal developer database or MQTT broker. Do not
attach the normal `nexus_postgres_data` volume to the acceptance container.
