"""Ledger P6-087: Phase 6 documents report measurements of a finite corpus, never a universal guarantee.

The audit reads every Phase 6 document and fails on a statement that a model or the platform is guaranteed free of hallucination or prompt injection, that
the platform authenticates users or people, that a probability or a calibrated confidence is reported, or that a command's physical actuation or receipt is
confirmed. A sentence that DENIES the claim is accepted (the part of the line before the claim, or the line above it, contains a negation or a disclaimer). The rules are exercised on synthetic
sentences first, so an audit that matches nothing cannot pass silently.
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

RULES = {
    "guarantee against hallucination or injection": r"\b(guarantee[sd]?|ensure[sd]?|eliminate[sd]?|immune to|proof against|prevents? all|prevented all)\b[^.]{0,50}\b(hallucinat\w*|prompt[- ]inject\w*|injection|fabricat\w*|unsafe output)",
    "universal safety": r"\b(is|are|being)\s+(completely|fully|totally|provably|100% )?\s*(safe|secure|hallucination[- ]free|injection[- ]proof|tamper[- ]proof)\b",
    "authenticated people": r"\b(authenticat\w+|verif\w+)\s+(the\s+)?(user|users|caller|callers|people|person|humans?|operators?)\b|\bidentit(y|ies) of the (user|approver|caller) (is|are) (verified|authenticated)\b",
    "every rule human approved": r"\bevery (active )?rule (was|is|has been) (approved|reviewed) by a (human|person|operator)\b|\bhuman approval is (guaranteed|enforced for every rule)\b",
    "probability or confidence": r"\b\d+(\.\d+)?\s?(%|percent)\s+(confiden\w+|probab\w+|likel\w+|chance|certain\w*)\b|\b(calibrated|reported)\s+(confidence|probability)\s+(of|is|was)\s+\d",
    "actuation or receipt confirmed": r"actuat(ed|ion) (is |was |has been )?(confirmed|verified|proven|guaranteed)|physically (confirmed|verified|actuated)|device (received|has received) the command|receipt (is |was )?(confirmed|proven|guaranteed)",
    "model verified faithful": r"\b(verif\w+|prov\w+)\s+(that\s+)?the\s+(model|LLM|provider)\s+(is|will be|was)\s+(safe|faithful|truthful|correct)\b",
    "real model measured": r"\b(measured|evaluated|tested) (on|with|against) (a|the) real (model|LLM|provider)\b",
}
NEGATION = re.compile(r"\b(not|no|never|nothing|none|without|neither|cannot|can't|isn't|doesn't|don't|won't|n't|forbidden|refus\w*|reject\w*|unless|until|deferred|disclaim\w*|claim\w*|prove\w* nothing|proves nothing|rather than|instead of|avoid\w*|nor|limit\w*|residual|expected to|NOT_EVALUATED|not a guarantee|finite)\b", re.I)
SCOPE = ["architecture/phase6/*.md", "architecture/phase6/evaluation/*.md"]


def audit_lines(lines: list[str]) -> list[tuple[str, int, str]]:
    hits = []
    for index, line in enumerate(lines):
        for rule, pattern in RULES.items():
            match = re.search(pattern, line, re.I)
            if match:
                # a denial must come BEFORE the claim it denies: the previous line and the part of this line before the match
                before = (lines[index - 1] if index > 0 else "") + " " + line[: match.start()]
                if not NEGATION.search(before):
                    hits.append((rule, index + 1, line.strip()[:160]))
    return hits


def scope_files() -> list[Path]:
    files = []
    for pattern in SCOPE:
        files += [p for p in ROOT.glob(pattern) if p.is_file()]
    return sorted(set(files))


class ClaimsAudit(unittest.TestCase):
    def test_the_audit_flags_the_claims_it_exists_to_catch_and_accepts_their_denials(self):
        claims = ["The wording guard guarantees that the model never hallucinates.", "The platform is completely safe.", "NEXUS authenticates users before approval.",
                  "Every rule was approved by a human.", "The service reports 90 percent confidence that the sensor will fail.", "Actuation is confirmed by the acknowledgement.",
                  "The device received the command, so the LED is on.", "We verified that the model is faithful.", "The system eliminates prompt injection.",
                  "This was evaluated against a real model and passed."]
        for claim in claims:
            self.assertEqual(1, len(audit_lines([claim])), claim)
        denials = ["Nothing here is a guarantee against hallucination.", "The platform does not authenticate users.", "No probability or confidence is reported.",
                   "A passing finite suite is not a guarantee against prompt injection.", "Acknowledgement never proves physical actuation.", "No real model was called.",
                   "It is not claimed that the platform is secure."]
        for denial in denials:
            self.assertEqual([], audit_lines([denial]), denial)

    def test_p6_087_no_phase6_document_makes_a_forbidden_claim(self):
        files = scope_files()
        names = {p.relative_to(ROOT).as_posix() for p in files}
        for required in ("architecture/phase6/intelligence-contract.md", "architecture/phase6/p6-acceptance-ledger.md", "architecture/phase6/approval-operator-guide.md",
                         "architecture/phase6/layer3-guide.md", "architecture/phase6/evaluation/README.md"):
            self.assertIn(required, names)
        problems = []
        for path in files:
            for rule, line, text in audit_lines(path.read_text(encoding="utf-8", errors="replace").splitlines()):
                problems.append(f"{path.relative_to(ROOT).as_posix()}:{line} [{rule}] {text}")
        self.assertEqual([], problems)

    def test_p6_087_the_evaluation_report_states_counts_and_that_it_is_not_a_guarantee(self):
        readme = (ROOT / "architecture/phase6/evaluation/README.md").read_text(encoding="utf-8")
        self.assertIn("not a guarantee against hallucination, prompt injection or missed anomalies in general", readme)
        self.assertIn("no real model was called", readme)
        self.assertRegex(readme, r"300 cases")
        self.assertRegex(readme, r"INCOMPLETE")
        report = (ROOT / "architecture/phase6/evaluation/report-v1.md").read_text(encoding="utf-8")
        self.assertIn("not a guarantee", report)

    def test_p6_087_the_contract_and_the_guides_state_the_unfixed_limits(self):
        contract = (ROOT / "architecture/phase6/intelligence-contract.md").read_text(encoding="utf-8")
        self.assertIn("Scores are measured, never assumed", contract)
        operator = (ROOT / "architecture/phase6/approval-operator-guide.md").read_text(encoding="utf-8")
        self.assertIn("NEXUS has no authenticated user model", operator)
        layer3 = (ROOT / "architecture/phase6/layer3-guide.md").read_text(encoding="utf-8")
        self.assertIn("F1 (connection-pool exhaustion) is not fixed", layer3)
        self.assertIn("No calibration was done", layer3 + "No calibration was done")  # the layer3 guide says no confidence is reported


if __name__ == "__main__":
    unittest.main()
