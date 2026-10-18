# Wire contract change log

Every change to a file under `contracts/v1/` needs an entry here, the new hash in `versions.json` (`python -m nexus_contract schema-hashes --write`) and a ledger note. `sdk/python/tests/test_schemas_and_evolution.py` fails otherwise (ledger P5-014). Within a major only compatible changes are allowed (`nexus_contract.evolution`); anything else is a new major.

## 1.0 (P5.1): baseline

First versioned contract, derived from the committed firmware and backend at `e29e215`. Legacy unversioned messages resolve to `legacy-v1` (same schemas, `contractVersion` and `requires` ignored).

| File | SHA-256 (LF-normalised) |
|---|---|
| `v1/acknowledgement.schema.json` | `18d4a850f8d109090256d1dad7977063005f23b83408ec58b5175592f450548c` |
| `v1/command.esp32-legacy.schema.json` | `46b5b16463f811899d259a57acd27470ad363a817eec331bf23cdaf8990a416a` |
| `v1/command.schema.json` | `3610829201d542ed71520444c5199bee9528958ca4f472e7a5a1e84afc786687` |
| `v1/status.schema.json` | `856f85c30e464b3308a62d6f4f5e39e55e156fa62465d9f926483c4145efb8e6` |
| `v1/telemetry.schema.json` | `c92fa37e9d8ee806535b0540e83e620c7e1f3ad955853769813d9019b351f052` |

Decisions recorded with this baseline:

- No `connectivity` schema: the MQTT status payload is plain text and is modelled as it is (trimmed, case-sensitive `online` / `offline`).
- Telemetry timestamps accept the firmware form (`...Z`, seconds) and the simulator form (microseconds, `+00:00`).
- Acknowledgement timestamps are bounded to the `java.time.Instant` range because the backend handler throws outside it (registered class B difference).
- Patterns end with `(?![\s\S])` instead of `$` so that a trailing newline is rejected identically by ECMA-262 and Python engines.
