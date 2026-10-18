# Phase 5 handoff draft (device adapters and SDK layer)

Draft for the project owner. It does not replace `PROJECT_HANDOFF.md` or `NEXT_TASK.md`, which are user-owned and untouched.

## State

Branch `feature/p5-adapters` (worktree `D:\nexus-p5`), 12 local commits (planning documents and P5.1 to P5.7) after the validated Phase 4 commit `e29e215`, plus the P5.8 commit, none pushed, merged or tagged. `main` is still `e2c77ec`. Phase 6 has not been started.

| Slice | Commit | What |
|---|---|---|
| P5.1 | 3900d7f, 9aab2c0 | wire contract 1.0 and legacy-v1, JSON Schemas, frozen firmware fixtures, standalone validator, parity registry |
| P5.V41 | f0e9af2 | additive migration: `devices.transport`, default transport per template, credential tables |
| P5.2 | 2636f4d | adapter SPI (capability split), registry, intake ports, transport eligibility |
| P5.3 | c3000b3 | MQTT JSON adapter extracted behind the SPI; Phase 3 suite 776 and 776 |
| P5.4 | 299cb93 | HTTP/webhook ingest adapter, per-device credentials, local credential tool; command pull deferred |
| P5.5 | 676508f | 30 s absolute freshness in the simulator, Python device SDK, in-process simulator adapter |
| P5.6 | 4311689 | ESP32 helper library, reference device, samples (host-tested only) |
| P5.7 | b6d6b8d | conformance kit, fourth test-only adapter, extensibility proof, mixed fleet |
| P5.8 | the commit that carries this file | gate checks, claims and protected-path audits, rollback test, mutation campaign, operator guide |

## What a reader must know before trusting it

* HTTP and the simulator **do not accept commands**: acceptance is refused with `EGRESS_NOT_SUPPORTED` and `DELIVERY_NOT_ROUTED`. The outbound HTTP pull (25 scenarios) and the dispatcher routing through the adapter egress are deferred, and nothing routes an HTTP command over MQTT.
* The MQTT path ignores `contractVersion` and `requires` (registered class E difference). Decide whether to reject them (a first stricter behaviour for MQTT) before any producer relies on `requires`.
* Three Phase 4 tests are wall-clock sensitive and failed intermittently in the full gate on this machine (`UncertainDeliveryBarrierTest`, `CommandDispatchMqttOutboxModeIntegrationTest.concurrentSameTargetUsesDurableAcceptanceOrder`, `CommandOutboxFlowDispatcherTest.twoDispatchersOfTwoInstancesShareOneLimit`); the first also failed on the unmodified Phase 4 baseline. Consider making them deterministic (an injected clock) in a separate change; they were not weakened here.
* The full gate is certified: one uninterrupted `validate_nexus.py --full` passed 16 of 16 checks (backend 11 batches, 243 classes, 1,896 tests, 2,041 s inside the owner-approved 2,400 s budget; measured earlier runs took 1,618 s and 1,922 s). Details are at the end of the ledger.
* Phase 4 limitations F1, F2 and L1 still apply. Recorded backend defect candidates (ACK timestamp outside the Instant range, SERVO 32-bit narrowing) are not fixed.
* Nothing is hardware validated; the ESP32 library was never built with the ESP32 toolchain or flashed.

## Suggested order for the review week

1. Merge in the order P4.5, P4.6, P4-FINAL, then Phase 5, and run `python validate_nexus.py --full` on the merged result.
2. Decide the MQTT version-resolution question and the HTTP pull (needs the design spikes S3 to S5 first).
3. Run the Phase 4 enablement procedure, then enable the HTTP adapter only behind TLS with the credential roles script (`operator-guide.md`).
