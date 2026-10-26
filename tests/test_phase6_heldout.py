"""P6-164: the held-out corpus for the real-model evaluation is frozen. Its hash is recorded in a manifest that is committed BEFORE any real model is called, the
evaluation refuses to run if the file differs, and the structure of every case is checked here so a malformed case cannot silently shrink the evaluation."""
from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIR = ROOT / "architecture/phase6/evaluation/heldout"
LOOKUPS = {"DEVICE_STATE", "TWIN_STATE", "LATEST_TELEMETRY", "TELEMETRY_HISTORY", "CONNECTIVITY", "ACTIVE_RULES", "RULE_MATCHES", "COMMAND_HISTORY", "DELIVERY_EVIDENCE", "VERIFICATION", "ANOMALIES", "RECOMMENDATIONS"}


def problems(corpus: dict) -> list[str]:
    out = []
    ids = [c["id"] for c in corpus["routing"]] + [c["id"] for c in corpus["selection"]]
    if len(ids) != len(set(ids)):
        out.append("duplicate case ids")
    for c in corpus["routing"]:
        e = c["expect"]
        if "refusal" in e:
            if e["refusal"] not in {"WRITE_REQUEST", "OUT_OF_SCOPE"}:
                out.append(f"{c['id']}: unknown refusal")
        else:
            if not e.get("types") or not set(e["types"]) <= LOOKUPS:
                out.append(f"{c['id']}: unknown lookup type")
            name = e.get("deviceName")
            if name and name.lower() not in c["question"].lower():
                out.append(f"{c['id']}: expected device name is not in the question")
            for key in ("ruleId", "commandId"):
                if key in e and e[key] not in c["question"]:
                    out.append(f"{c['id']}: expected {key} is not in the question")
    for c in corpus["selection"]:
        refs = [i["ref"] for i in c["evidence"]]
        if len(refs) != len(set(refs)):
            out.append(f"{c['id']}: duplicate evidence references")
        if not set(c["expectedRefs"]) <= set(refs):
            out.append(f"{c['id']}: an expected reference is not in the evidence")
        if c["abstain"] != (len(c["expectedRefs"]) == 0):
            out.append(f"{c['id']}: abstain does not match the expected references")
        if set(c.get("traps", [])) & set(c["expectedRefs"]):
            out.append(f"{c['id']}: a trap is also expected")
        if not set(c.get("traps", [])) <= set(refs):
            out.append(f"{c['id']}: a trap is not an evidence reference")
        if set(c.get("inventedRefs", [])) & set(refs):
            out.append(f"{c['id']}: an invented reference exists in the evidence")
    return out


class HeldOutCorpus(unittest.TestCase):
    def setUp(self):
        # line endings are normalised so a checkout with CRLF has the same hash as the committed LF text
        self.raw = (DIR / "p6-heldout-v1.json").read_bytes().replace(b"\r\n", b"\n")
        self.corpus = json.loads(self.raw.decode("utf-8"))
        self.manifest = json.loads((DIR / "p6-heldout-v1.manifest.json").read_text(encoding="utf-8"))

    def test_the_corpus_matches_its_frozen_manifest(self):
        self.assertEqual(self.manifest["sha256"], hashlib.sha256(self.raw).hexdigest())
        self.assertEqual(self.manifest["routingCases"], len(self.corpus["routing"]))
        self.assertEqual(self.manifest["selectionCases"], len(self.corpus["selection"]))
        self.assertTrue(self.manifest["frozenBeforeAnyRealModelCall"])

    def test_a_changed_corpus_is_detected(self):
        tampered = self.raw.replace(b"R01", b"R99", 1)
        self.assertNotEqual(self.manifest["sha256"], hashlib.sha256(tampered).hexdigest())

    def test_every_case_is_well_formed(self):
        self.assertEqual([], problems(self.corpus))
        self.assertGreaterEqual(len(self.corpus["routing"]), 30)
        self.assertGreaterEqual(len(self.corpus["selection"]), 24)

    def test_the_structure_check_rejects_malformed_cases(self):
        bad = {"routing": [{"id": "X1", "question": "q", "expect": {"types": ["NOPE"]}}], "selection": [{"id": "X1", "question": "q", "evidence": [{"ref": "a"}], "expectedRefs": ["b"], "abstain": False}]}
        found = problems(bad)
        self.assertTrue(any("duplicate" in p for p in found))
        self.assertTrue(any("unknown lookup type" in p for p in found))
        self.assertTrue(any("not in the evidence" in p for p in found))

    def test_categories_cover_the_hostile_and_the_refusal_cases(self):
        routing = {c["category"] for c in self.corpus["routing"]}
        selection = {c["category"] for c in self.corpus["selection"]}
        self.assertTrue({"write", "scope", "injection"} <= routing)
        self.assertTrue({"abstain", "injection", "relevant"} <= selection)


if __name__ == "__main__":
    unittest.main()
