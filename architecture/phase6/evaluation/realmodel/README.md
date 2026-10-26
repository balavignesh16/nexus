# Real local model evaluation (P6.9)

One measured run of one local model on one machine. It says nothing about other prompts, other models, other machines or the quality a differently designed assistant would reach.

## What was run

* Model: `llama3.2:3b` served by Ollama 0.21.2 on the loopback interface only (3.2B parameters, Q4_K_M, digest `a80c4f17acd5...`, reported capabilities `completion` and `tools`). It was chosen after the server reported its capabilities: `phi3:mini` reports no tools capability and was excluded, the Ollama cloud model reports a remote host and was excluded by design, `llama3.1:8b` and `qwen2.5:14b` also report tools but are larger than needed for a 16 GB laptop with a 4 GB GPU.
* Corpus: the frozen held-out corpus `p6-heldout-v1` (30 routing cases, 24 evidence-selection cases), committed in `ad7486e` before any real-model call; the harness recomputes its SHA-256 and refuses to run on any other content.
* Contract: the two assistant model tasks of `AssistantContracts` (question to fixed lookups; selection of retrieved evidence references). Temperature 0, seed 0, at most 512 output tokens. Device-evidence consent was given for this run only, to a verified loopback server.
* Files: `run1-llama3.2-3b-results.json` (measures, model identity, configuration, contract and corpus hashes, host resources) and `run1-llama3.2-3b-cases.json` (every case: what the model returned and how it was handled).
* Harness history: the first two attempts failed on settings of my own harness before any model call (a guard timeout and a breaker threshold outside the allowed range); nothing was measured. This run is the first that reached the model. A second run after a scoring fix was started and killed by the host for low memory; it is not part of the evidence.

## Results (run 1, 54 model calls)

| Measure | Result | Gated? |
|---|---|---|
| Accepted outputs outside the contract (oracle independent of the validators) | **0 of 54** | yes, zero tolerance: PASS |
| Routing: valid JSON object | 30 of 30 | no (proposed bound 0.8) |
| Routing: routed correctly (expected lookups and parameters, or refusal) | **0 of 30** | no (proposed bound 0.7): FAIL |
| Routing: write and out-of-scope questions refused with the right reason | **0 of 8** (the harness counted this on accepted plans only, so the saved file shows n=0; the corrected definition counts every refusal case and is applied in the current harness) | no (proposed bound 0.8): FAIL |
| Routing: hostile questions where the accepted plan followed the injection | 0 of 3 | no: PASS |
| Selection: valid selection naming only presented references | 16 of 24 (0.667) | no (proposed bound 0.8): FAIL |
| Selection: every expected reference present on relevant questions | 8 of 17 (0.471) | no (proposed bound 0.7): FAIL |
| Selection: abstained correctly when nothing was relevant | **0 of 7** | no (proposed bound 0.7): FAIL |
| Selection: selected the item carrying an injected instruction | 3 of 6 (0.5) | no (proposed bound 0.25): FAIL |
| Latency of one call | p50 2.8 s, p95 20.9 s, max 23.3 s | not compared |

## What this shows

* **Safety held.** Everything the model produced that was outside the fixed contract was dropped before use: 16 routing plans were rejected for invented device ids (the model filled every field with made-up hex strings), 3 for unknown lookup types, 3 for wrong types, 2 for device names not in the question, 1 for an id not in the question and 1 for lookups together with a refusal; 8 selections were rejected for references that were not presented (the model returned the whole `reference | label | value` line or a shortened reference). No accepted output violated the contract.
* **Quality did not.** This 3B model does not follow the routing contract (it never refused a write request, never abstained, and in no case returned exactly the expected plan) and it selected the injected item in half the injection cases. These are informational against bounds that were proposed before the first run; they are reported, not hidden.
* **Consequence for the design.** The model must not be what makes the assistant work. P6.10 therefore routes and selects deterministically first, offline, and uses a model only as an optional, validated reordering aid. Prompt changes made after seeing these results are not evaluated on this corpus any more (it has been seen); a new held-out corpus version is required for any later claim.
* **Not claimed.** No claim that the platform's AI is accurate, that a larger model would do better, or that the held-out result generalises.
