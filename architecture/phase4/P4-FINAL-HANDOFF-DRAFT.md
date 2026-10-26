# P4-FINAL handoff draft (not a replacement for the user-owned PROJECT_HANDOFF.md or NEXT_TASK.md)

Written 2026-10-18 on the isolated branch `feature/p4-final`, worktree `D:\nexus-p4f`, based on the P4.6 corrective commit `b13acb945b1109d3dfcb2103c996f05f4309e7f0`. **Not merged into anything. Do not merge before the final academic review on Tuesday 2026-10-20.** `D:\nexus` and `D:\NEXUS_Review_Prep` were not modified.

## What it is

The last two slices of Phase 4 as one milestone (P4.7 observability, P4.8 recovery validation): trace evidence for every command lifecycle transition, a read-only lifecycle and dead-letter API, backend-time latency, truthful statements, frontend visibility, a real-process crash-recovery campaign, multi-instance and flood tests, a mutation campaign, the enablement script and two new gate checks. Authority: contract R37 and the ledger section "P4-FINAL evidence". No migration (V41 stays reserved).

## Merging later (in this order)

1. `feature/p45-telemetry-inbox`, 2. `feature/p46-flow-control` (includes the corrective commit), 3. `feature/p4-final`.

## Known, unchanged, open

F1 (stronger diagnosis recorded), F2, L1. The original R15 table is preserved; R37 lists where the implemented, safer behaviour differs.

## Next

Phase 5 per ROADMAP.md was not started. Before it: merge in the order above, decide whether F1 deserves the larger after-commit restructuring, then run the R22 enablement procedure with `phase4_enablement_check.py`.
