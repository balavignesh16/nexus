# Canonical demo telemetry visibility correction

The backend had stored demo readings, but TelemetryPage filtered only fixed uppercase legacy metric names.
Canonical lowercase/custom keys were excluded. It also numerically coerced non-numeric values to zero and
shared a cache namespace with incomplete scalar websocket deltas, which cannot preserve typed values/units.

The page now discovers exact keys from selected-device history, uses an isolated typed snapshot cache and
refreshes the bounded50-record snapshot every5 seconds even when realtime transport is connected. Numeric
charts/stats accept only finite numeric values with the latest reported unit; no implicit unit conversion or
Boolean/string/null-to-zero conversion occurs. Typed state/text values remain visible in the readings table.
CSV includes units. Device mode is preserved by BackendDeviceRepository so SIMULATED source is explicit.

Verification:24 focused TelemetryPage/BackendRepository tests passed; frontend build passed. Real Playwright
selected all three demo devices, checked persisted numeric charts, simulated labels, Boolean state display
and automatic timestamp refresh. Evidence/screenshots: .nexus/validation/demo-telemetry. ESP2 screenshot was
visually inspected. No backend/firmware changes, migrations, full gate, commits, push or tags were performed.
