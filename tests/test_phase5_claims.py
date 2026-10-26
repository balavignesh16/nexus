"""Ledger P5-066, P5-102, P5-121 and P5-151: the claims audit and the spike register.

The audit reads every Phase 5 document, README, SDK and adapter source and every frontend source (UI strings), and fails on a statement that
NEXUS (or an adapter) is secure or authenticated platform-wide, that a message is delivered exactly once, that the ESP32 library was validated
on hardware, that a command's physical actuation or receipt is confirmed or guaranteed, or that any transport is supported. A sentence that
DENIES the claim is allowed: a statement is accepted when the line or its neighbours contain a negation or a disclaimer. The rules are exercised
on synthetic sentences first, so an audit that matches nothing cannot pass silently."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

RULES = {
    "exactly-once": r"exactly[- ]once",
    "hardware validation": r"hardware[- ]validated|validated (on|with|against) (the )?(real |physical )?(hardware|device)|physically validated|flashed and (tested|verified)|(tested|verified) on (the )?(real|physical)",
    "security": r"\b(is|are|be|being)\s+(fully |completely |highly )?(secure|authenticated|tamper[- ]proof)\b|\bsecure by (design|default)\b|\bfully (secure|authenticated)\b|\bplatform[- ]wide authentication\b",
    "actuation or receipt": r"actuat(ed|ion) (is |was |has been )?(confirmed|verified|proven|guaranteed)|(confirm|verif|prov|guarantee)(s|es|ed)?\s+(the\s+)?(physical )?actuation|physically (confirmed|verified|actuated)|device (received|has received) the command|receipt (is |was )?(confirmed|proven|guaranteed)|guarantees? delivery|delivery is guaranteed|guaranteed delivery",
    "any transport": r"supports? any transport|works? with any transport",
}
NEGATION = re.compile(r"\b(not|no|never|nothing|none|without|neither|cannot|can't|isn't|doesn't|don't|won't|n't|forbidden|refus\w*|reject\w*|unless|until|deferred|disclaim\w*|claim\w*|prove\w* nothing|proves nothing|rather than|instead of|avoid\w*|nor)\b", re.I)

SCOPE = ["architecture/phase5/*.md", "sdk/python/README.md", "sdk/esp32/README.md", "contracts/CHANGELOG.md", "backend/src/main/java/com/nexus/adapter/**/*.java",
         "sdk/python/nexus_contract/*.py", "sdk/python/nexus_device_sdk/*.py", "sdk/esp32/src/**/*", "sdk/esp32/examples/**/*.ino", "simulator/src/**/*.py",
         "frontend/src/**/*.ts", "frontend/src/**/*.tsx"]


def audit_lines(lines: list[str]) -> list[tuple[str, int, str]]:
    """(rule, 1-based line, text) for every line that makes a claim without a negation in the line or its two neighbours."""
    hits = []
    for index, line in enumerate(lines):
        for rule, pattern in RULES.items():
            if re.search(pattern, line, re.I):
                context = " ".join(lines[max(0, index - 1): index + 2])
                if not NEGATION.search(context):
                    hits.append((rule, index + 1, line.strip()[:160]))
    return hits


def scope_files() -> list[Path]:
    files = []
    for pattern in SCOPE:
        files += [p for p in ROOT.glob(pattern) if p.is_file() and ".test." not in p.name and "node_modules" not in p.parts]
    return sorted(set(files))


class ClaimsAudit(unittest.TestCase):
    def test_the_audit_flags_the_claims_it_exists_to_catch_and_accepts_their_denials(self):
        claims = ["NEXUS is secure.", "The platform is fully authenticated.", "Commands are delivered exactly once.", "The ESP32 library was validated on hardware.",
                  "Actuation is confirmed by the acknowledgement.", "The device received the command, so the LED is on.", "NEXUS supports any transport.",
                  "Delivery is guaranteed.", "The library is hardware-validated."]
        for claim in claims:
            self.assertEqual(1, len(audit_lines([claim])), claim)
        denials = ["None of this is a claim that NEXUS is secure.", "Nothing here claims exactly-once delivery.", "No hardware validation is claimed.",
                   "An acknowledgement never proves physical actuation.", "It does not guarantee delivery.", "The platform is not authenticated platform-wide."]
        for denial in denials:
            self.assertEqual([], audit_lines([denial]), denial)

    def test_p5_066_p5_102_p5_121_no_phase5_document_readme_source_or_ui_string_makes_a_forbidden_claim(self):
        files = scope_files()
        self.assertGreater(len(files), 150, "the audit reads the documents, the SDKs, the adapters and the frontend")
        names = {p.relative_to(ROOT).as_posix() for p in files}
        for required in ("architecture/phase5/adapter-contract.md", "architecture/phase5/p5-acceptance-ledger.md", "sdk/esp32/README.md", "sdk/python/README.md",
                         "backend/src/main/java/com/nexus/adapter/http/HttpIngestController.java"):
            self.assertIn(required, names)
        self.assertTrue(any(n.startswith("frontend/src/") for n in names), "UI strings are in scope")
        problems = []
        for path in files:
            for rule, line, text in audit_lines(path.read_text(encoding="utf-8", errors="replace").splitlines()):
                problems.append(f"{path.relative_to(ROOT).as_posix()}:{line} [{rule}] {text}")
        self.assertEqual([], problems)

    def test_p5_066_the_security_statement_is_limited_and_says_so(self):
        contract = (ROOT / "architecture/phase5/adapter-contract.md").read_text(encoding="utf-8")
        section = contract[contract.index("## 18. Security statements and limits"): contract.index("## 19. Not claimed")]
        self.assertIn("None of this is a claim that NEXUS is secure", section)
        self.assertIn("Device-adapter authentication only", section)
        self.assertIn("no user or role model", section)

    def test_p5_102_the_esp32_documents_state_host_side_validation_only(self):
        readme = (ROOT / "sdk/esp32/README.md").read_text(encoding="utf-8")
        self.assertRegex(readme, r"(?i)tested on the host only")
        self.assertRegex(readme, r"(?i)nothing in this directory is a hardware-validation claim")
        self.assertRegex(readme, r"(?i)never flashed")
        self.assertRegex(readme, r"(?i)not verified")
        for sketch in ("TemperatureNode", "LedCommandNode"):
            text = (ROOT / f"sdk/esp32/examples/{sketch}/{sketch}.ino").read_text(encoding="utf-8")
            self.assertRegex(text, r"(?i)not flashed or run on hardware")
        contract = (ROOT / "architecture/phase5/adapter-contract.md").read_text(encoding="utf-8")
        self.assertIn("hardware validation of the ESP32 library", contract[contract.index("## 19. Not claimed"):contract.index("## 20.")])

    def test_p5_121_delivery_statements_name_evidence_kinds_and_the_spi_has_exactly_three(self):
        evidence = (ROOT / "backend/src/main/java/com/nexus/adapter/spi/EvidenceKind.java").read_text(encoding="utf-8")
        kinds = set(re.findall(r"\b([A-Z]+_[A-Z]+)\b", evidence[evidence.index("enum EvidenceKind"):]))
        self.assertEqual({"BROKER_CONFIRMED", "HTTP_SERVED", "SIMULATOR_DELIVERED"}, kinds)
        contract = (ROOT / "architecture/phase5/adapter-contract.md").read_text(encoding="utf-8")
        for kind in kinds:
            self.assertIn(kind, contract)
        self.assertRegex(contract, r"(?i)nothing in phase 5 may claim exactly-once delivery, device receipt or physical actuation")
        # the Phase 4 frontend guard for the same claims still exists and still runs in the frontend unit tests
        guard = (ROOT / "frontend/src/utils/commandLifecycle.test.ts").read_text(encoding="utf-8")
        self.assertIn("no label and no standing sentence claims physical actuation or exactly-once delivery", guard)


class SpikeRegister(unittest.TestCase):
    """P5-151: every design spike S1 to S8 has a recorded result in the ledger evidence, and no work depends on a spike that was not run."""

    def register(self) -> dict[str, str]:
        ledger = (ROOT / "architecture/phase5/p5-acceptance-ledger.md").read_text(encoding="utf-8")
        self.assertIn("### Design spike register", ledger)
        section = ledger[ledger.index("### Design spike register"):]
        section = section[: section.index("\n### ", 10)] if "\n### " in section[10:] else section
        rows = {}
        for match in re.finditer(r"^\| (S[1-8]) \| (PASSED|NOT RUN|PARTIAL)[^|]*\|", section, re.M):
            rows[match.group(1)] = match.group(2)
        return rows

    def test_p5_151_all_eight_spikes_have_a_recorded_result(self):
        self.assertEqual({f"S{i}" for i in range(1, 9)}, set(self.register()))

    def test_p5_151_a_spike_that_was_not_run_has_no_dependent_code(self):
        rows = self.register()
        not_run = {name for name, status in rows.items() if status == "NOT RUN"}
        self.assertEqual({"S3", "S4", "S5"}, not_run, "the three outbound-pull spikes were not run because the pull is deferred")
        # the dependent code does not exist: no pull handler in production code and every pull scenario is DEFERRED
        main = (ROOT / "backend/src/main/java")
        offenders = [p.as_posix() for p in main.rglob("*.java") if re.search(r"(?i)\bpull(Controller|Handler|Endpoint|Service)\b|/pull\b", p.read_text(encoding="utf-8", errors="replace"))]
        self.assertEqual([], offenders)
        ledger = (ROOT / "architecture/phase5/p5-acceptance-ledger.md").read_text(encoding="utf-8")
        for number in list(range(67, 82)) + list(range(129, 139)):
            row = [l for l in ledger.splitlines() if l.startswith(f"| P5-{number:03d} |")][0]
            self.assertIn("| DEFERRED |", row, row[:60])


if __name__ == "__main__":
    unittest.main()
