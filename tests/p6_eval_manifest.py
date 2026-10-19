"""Regenerates (or checks) the manifest of the Phase 6 evaluation corpus.

    python tests/p6_eval_manifest.py          # rewrite manifest.json
    python tests/p6_eval_manifest.py --check  # exit 1 if manifest.json does not match the files

The manifest records the corpus version, where each file came from, the SHA-256 of every file (line endings normalised, so a CRLF checkout hashes like an
LF one) and a corpus hash over all of them. The Java harness refuses a corpus file whose hash differs from the manifest, so editing a case without
regenerating the manifest fails every suite, and a changed corpus cannot masquerade as the versioned one.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "backend/src/test/resources/eval/p6/corpus-v1"
VERSION = "p6-eval-corpus-v1"
GENERATOR_VERSION = "1"

PROVENANCE = {
    "narrator.json": "Hand-written for Phase 6.5 from the fabrication classes in the approved contract (faults, reasons for silence, actuation, calibration, rule outcomes, approval, receipt, verification, causes) plus structural attacks and near-miss alterations; expected results are deterministic.",
    "prose.json": "Hand-written for Phase 6.5: wordings in the four safety-critical assertion classes, obfuscated variants, benign factual claims and meaning-equivalent paraphrase probes.",
    "injection.json": "Hand-written for Phase 6.5: attacker-controlled text in each class of the approved injection list, placed into every free-text vector by the suites.",
    "anomaly.json": "Specification of seeded synthetic series families (generator version " + GENERATOR_VERSION + " in the Java harness). The series are generated, not stored; the specification, seeds, amplitudes and truth labels are fixed here BEFORE results were viewed.",
    "drafts.json": "Hand-written for Phase 6.5: scripted provider replies for the draft path, including replies from an adversary that obeys injected instructions.",
    "lifecycle.json": "Hand-written for Phase 6.5: command lifecycle views (queued, published, uncertain, expired, acknowledged, acknowledged without verification, contradictory, missing) with required and forbidden statements.",
    "nonmatch.json": "Hand-written for Phase 6.5: rule non-match scenarios with the reason each must name and the statements each must not make.",
}

COUNT_KEYS = ["cases", "payloads", "unsafe", "families", "scenarios", "views"]


def normalised(path: Path) -> bytes:
    return path.read_bytes().replace(b"\r\n", b"\n")


def build() -> dict:
    files = {}
    total = 0
    for path in sorted(CORPUS.glob("*.json")):
        if path.name == "manifest.json":
            continue
        data = json.loads(normalised(path))
        count = 0
        for key in COUNT_KEYS:
            if isinstance(data.get(key), list):
                count += len(data[key])
        for key in ("obfuscated", "benign", "paraphraseProbes"):
            if isinstance(data.get(key), list):
                count += len(data[key])
        files[path.name] = {"sha256": hashlib.sha256(normalised(path)).hexdigest(), "cases": count, "provenance": PROVENANCE.get(path.name, "unspecified")}
        total += count
    corpus_hash = hashlib.sha256("".join(f"{n}:{v['sha256']};" for n, v in sorted(files.items())).encode()).hexdigest()
    return {"version": VERSION, "generatorVersion": GENERATOR_VERSION, "totalCases": total, "corpusHash": corpus_hash, "files": files}


def main(argv: list[str]) -> int:
    manifest = build()
    target = CORPUS / "manifest.json"
    text = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    if "--check" in argv:
        if not target.exists() or json.loads(target.read_text(encoding="utf-8")) != manifest:
            print("manifest.json does not match the corpus files", file=sys.stderr)
            return 1
        print(f"manifest ok: {manifest['totalCases']} cases, corpus hash {manifest['corpusHash'][:16]}")
        return 0
    target.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote manifest: {manifest['totalCases']} cases, corpus hash {manifest['corpusHash'][:16]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
