"""Schema validity, required-field mutation (P5-012), evolution and breaking-change detection (P5-024, P5-025),
the schema change process (P5-014) and the support-window listing (P5-001, P5-020), plus the known-difference registry
sanity checks (P5-157) and the corpus freshness check."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import unittest

from jsonschema import Draft202012Validator

from tests import support
from nexus_contract import corpus as corpus_module, evolution, loader
from nexus_contract.validator import KINDS, validate


def _schema_files() -> list[str]:
    table = loader.versions()
    files = list(table["kinds"].values())
    files += [profile["command"] for profile in table["profiles"].values()]
    return sorted(files)


class Schemas(unittest.TestCase):
    def test_p5_001_one_valid_draft_2020_12_schema_per_message_kind(self):
        table = loader.versions()
        self.assertEqual(set(KINDS), set(table["kinds"]))
        self.assertNotIn("connectivity", table["kinds"], "connectivity has no wire message; the plain-text status is its only wire format")
        for relative in _schema_files():
            document = loader.schema(relative)
            Draft202012Validator.check_schema(document)
            self.assertEqual("https://json-schema.org/draft/2020-12/schema", document["$schema"], relative)
            self.assertTrue((support.CONTRACTS / relative).is_file())
        self.assertFalse(list(support.CONTRACTS.rglob("connectivity*.schema.json")))

    def test_p5_001_no_other_schema_copy_exists_in_the_repository_tree_under_contracts(self):
        files = sorted(path.relative_to(support.CONTRACTS).as_posix() for path in support.CONTRACTS.rglob("*.schema.json"))
        self.assertEqual(_schema_files(), files)

    def test_p5_014_a_changed_schema_needs_a_recorded_hash_and_changelog_entry(self):
        table = loader.versions()
        changelog = (support.CONTRACTS / "CHANGELOG.md").read_text(encoding="utf-8")
        for relative in _schema_files():
            digest = hashlib.sha256((support.CONTRACTS / relative).read_bytes().replace(b"\r\n", b"\n")).hexdigest()
            self.assertEqual(table["schemaSha256"].get(relative), digest,
                             f"{relative} changed: update versions.json schemaSha256, CHANGELOG.md and the ledger in the same change")
            self.assertIn(digest, changelog, f"{relative} has no CHANGELOG.md entry for this content")
        self.assertEqual(set(_schema_files()), set(table["schemaSha256"]))

    def test_p5_010_rules_a_schema_cannot_express_are_annotated_and_implemented_once(self):
        from nexus_contract import rules, validator
        for relative in _schema_files():
            self.assertTrue(loader.schema(relative).get("x-nexus-rules"), f"{relative} must list the rules it cannot hold")
        # device equality, freshness and the status preprocessing each exist exactly once, in the shared module
        for name in ("topic_device_matches", "command_age_exceeds", "status_text", "classify_telemetry", "capability_semantics"):
            self.assertTrue(callable(getattr(rules, name)), name)
        source = (support.PACKAGE_ROOT / "nexus_contract" / "validator.py").read_text(encoding="utf-8")
        self.assertNotIn("abs(", source, "freshness arithmetic lives in rules.command_age_exceeds only")
        self.assertIn("topic_device_matches", source)
        self.assertTrue(validator.validate("telemetry", b"{}").code)

    def test_p5_012_removing_a_required_field_from_a_schema_is_detected_by_the_corpus(self):
        manifest = support.manifest()
        for kind, field, fixture_id in (("telemetry", "deviceId", "telemetry-missing-deviceId"), ("acknowledgement", "commandId", "ack-numeric-commandId")):
            relative = loader.versions()["kinds"][kind]
            mutated = copy.deepcopy(loader.schema(relative))
            mutated["required"] = [name for name in mutated["required"] if name != field]
            self.assertNotEqual(loader.schema(relative)["required"], mutated["required"])
            original = Draft202012Validator(loader.schema(relative))
            weakened = Draft202012Validator(mutated)
            payload = {"timestamp": "2026-01-01T00:00:00Z", "sensorType": "S", "value": 1, "unit": "U", "status": "ACCEPTED"}
            payload.pop("status") if kind == "telemetry" else None
            if kind == "acknowledgement":
                payload = {"status": "ACCEPTED", "timestamp": 1}
            self.assertFalse(original.is_valid(payload), kind)
            self.assertTrue(weakened.is_valid(payload), f"{kind}: the mutant must be observably weaker")
        # and the fixture corpus pins the real behaviour so such a mutant could not pass unnoticed
        entry = next(e for e in manifest["fixtures"] if e["id"] == "telemetry-missing-deviceId")
        self.assertEqual(["REJECT", "INVALID_FIELDS"], entry["expect"]["legacy-v1"])

    def test_p5_024_every_1_0_fixture_stays_valid_under_every_schema_minor_present(self):
        table = loader.versions()
        for major, minors in table["supportedMinors"].items():
            for minor in minors:
                for entry in support.manifest()["fixtures"]:
                    if entry["expect"]["legacy-v1"][0] != "ACCEPT" or entry["kind"] == "status" or entry["kind"] == "command":
                        continue
                    with self.subTest(version=f"{major}.{minor}", fixture=entry["id"]):
                        result = validate(entry["kind"], support.with_version(entry, f"{major}.{minor}"), topic_device_id=entry.get("topicDeviceId"))
                        self.assertEqual("ACCEPT", result.verdict)
        # a minor above the highest known keeps validating as the highest known
        for entry in support.manifest()["fixtures"]:
            if entry["expect"]["legacy-v1"][0] == "ACCEPT" and entry["kind"] in ("telemetry", "acknowledgement"):
                self.assertEqual("ACCEPT", validate(entry["kind"], support.with_version(entry, "1.7"), topic_device_id=entry.get("topicDeviceId")).verdict)

    def test_p5_025_breaking_changes_are_detected_and_compatible_ones_are_not(self):
        base = loader.schema(loader.versions()["kinds"]["telemetry"])
        self.assertEqual([], evolution.compare(base, base))
        breaking = {
            "rename a field": lambda s: s["properties"].__setitem__("device", s["properties"].pop("deviceId")),
            "remove a field": lambda s: s["properties"].pop("unit"),
            "change a type": lambda s: s["properties"]["timestamp"].update(type="integer"),
            "narrow a pattern": lambda s: s["$defs"]["messageId"].update(pattern="^[a-z]{1,8}(?![\\s\\S])"),
            "make an optional field required": lambda s: s["required"].append("unit"),
        }
        for name, mutate in breaking.items():
            with self.subTest(name):
                mutated = copy.deepcopy(base)
                mutate(mutated)
                self.assertTrue(evolution.compare(base, mutated), name)
        ack = loader.schema(loader.versions()["kinds"]["acknowledgement"])
        for name, mutate in {
            "remove an enum value": lambda s: s["properties"]["status"].update(enum=["ACCEPTED"]),
            "narrow a range": lambda s: s["properties"]["timestamp"].update(maximum=1),
            "narrow a length": lambda s: s["properties"]["reason"].update(maxLength=8),
        }.items():
            with self.subTest(name):
                mutated = copy.deepcopy(ack)
                mutate(mutated)
                self.assertTrue(evolution.compare(ack, mutated), name)
        compatible = {
            "add an optional field": lambda s: s["properties"].update(extra={"type": "string"}),
            "relax a maximum": lambda s: s["properties"]["reason"].update(maxLength=512),
            "allow another type": lambda s: s["properties"]["reason"].update(type=["string", "null", "number"]),
        }
        for name, mutate in compatible.items():
            with self.subTest(name):
                mutated = copy.deepcopy(ack)
                mutate(mutated)
                self.assertEqual([], evolution.compare(ack, mutated), name)


class Registry(unittest.TestCase):
    def test_p5_157_registry_is_well_formed_and_classified(self):
        registry = json.loads((support.CONTRACTS / "parity" / "known-differences.json").read_text(encoding="utf-8"))
        corpus = json.loads((support.CONTRACTS / "parity" / "corpus.json").read_text(encoding="utf-8"))
        ids = {item["id"] for item in corpus["items"]}
        seen = set()
        for entry in registry["entries"]:
            self.assertIn(entry["class"], {"B", "C", "D", "E"}, "class A (a schema mistake) is fixed, never registered")
            self.assertTrue(entry["clause"] and entry["rationale"])
            for case, verdicts in entry["cases"].items():
                self.assertIn(case, ids)
                self.assertNotIn(case, seen, "registered twice")
                seen.add(case)
                self.assertNotEqual(verdicts["backend"], verdicts["validator"], f"{case} is not a difference")
        for entry in registry.get("simulatorEntries", []):
            self.assertIn(entry["class"], {"C", "D"})
            self.assertTrue(entry["clause"] and entry["rationale"])

    def test_corpus_file_is_current(self):
        current = (support.CONTRACTS / "parity" / "corpus.json").read_text(encoding="utf-8").replace("\r\n", "\n")
        self.assertEqual(corpus_module._text(corpus_module.build()), current,
                         "contracts/parity/corpus.json is stale: python -m nexus_contract.corpus --write")

    def test_corpus_items_cover_real_origins(self):
        corpus = json.loads((support.CONTRACTS / "parity" / "corpus.json").read_text(encoding="utf-8"))
        origins = {item["origin"].split(":")[0] if item["origin"].startswith("fixture") else item["origin"] for item in corpus["items"]}
        self.assertTrue({"fixture", "mutation", "backend emitter"} <= origins)
        fixture_origins = {item["origin"] for item in corpus["items"] if item["origin"].startswith("fixture:")}
        self.assertTrue({"fixture:firmware", "fixture:simulator"} <= fixture_origins)
        for item in corpus["items"]:
            self.assertRegex(item["id"], r"^[a-z]+:[A-Za-z0-9._-]+$")


if __name__ == "__main__":
    unittest.main()
