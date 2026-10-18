"""``python -m nexus_contract`` command line. No network, no database, no broker: it reads local files only and
prints fixed codes, never payload content (ledger P5-004)."""

from __future__ import annotations

import argparse
import base64
import json
import sys
from pathlib import Path

from . import loader, rules
from .validator import KINDS, NOT_APPLICABLE, NOT_PERFORMED, Result, validate

EXIT_ACCEPT, EXIT_REJECT, EXIT_USAGE = 0, 1, 2


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m nexus_contract", description="Validate NEXUS device wire payloads locally.")
    sub = parser.add_subparsers(dest="command", required=True)

    check = sub.add_parser("validate", help="validate one payload (a file, or - for standard input)")
    check.add_argument("--kind", required=True, choices=KINDS)
    check.add_argument("file", nargs="?", default="-")
    check.add_argument("--legacy", action="store_true", help="apply the legacy-v1 policy: contractVersion and requires are unknown fields")
    check.add_argument("--contract-version", dest="contract_version", help="a version declared outside the body (the HTTP header value)")
    check.add_argument("--profile", help="a legacy device profile, for example esp32-legacy-v1")
    check.add_argument("--topic", help="the MQTT topic the message arrived on (enables the identity layer)")
    check.add_argument("--reference-time", dest="reference_time", help="the injected 'now': epoch seconds or an ISO-8601 instant ending in Z (enables the freshness layer)")
    check.add_argument("--freshness-window", dest="freshness_window", type=int, help="seconds; defaults to the selected profile's window")
    check.add_argument("--capability-profile", dest="capability_profile", help='JSON text or a file, for example {"type":"RELAY","channels":[1,2]}')
    check.add_argument("--require-all-layers", dest="require_all", action="store_true", help="fail when any layer is NOT_PERFORMED")
    check.add_argument("--json", action="store_true", help="print one JSON result")

    corpus = sub.add_parser("validate-corpus", help="validate a whole manifest in one process and write the results")
    corpus.add_argument("--manifest", required=True)
    corpus.add_argument("--output", required=True)

    sub.add_parser("versions", help="print the supported contract versions")

    hashes = sub.add_parser("schema-hashes", help="print (or with --write record in versions.json) the SHA-256 of every schema file")
    hashes.add_argument("--write", action="store_true")
    return parser


def _device_from_topic(topic: str | None) -> str | None:
    if topic is None:
        return None
    parts = topic.split("/")
    return parts[2] if len(parts) >= 3 else None


def _capability_profile(text: str | None):
    if text is None:
        return None
    candidate = Path(text)
    raw = candidate.read_text(encoding="utf-8") if candidate.is_file() else text
    return json.loads(raw)


def _run_validate(args) -> int:
    payload = sys.stdin.buffer.read() if args.file == "-" else Path(args.file).read_bytes()
    reference = rules.parse_reference_time(args.reference_time) if args.reference_time else None
    result = validate(args.kind, payload, legacy=args.legacy, declared_version=args.contract_version, profile=args.profile,
                      topic_device_id=_device_from_topic(args.topic), reference_time=reference,
                      freshness_window=args.freshness_window, capability_profile=_capability_profile(args.capability_profile))
    _print(result, args.json)
    if result.verdict == "REJECT":
        return EXIT_REJECT
    if args.require_all and result.not_performed:
        return EXIT_REJECT
    return EXIT_ACCEPT


def _print(result: Result, as_json: bool) -> None:
    if as_json:
        print(json.dumps(result.as_dict(), sort_keys=True))
        return
    for layer in result.layers:
        suffix = f" {layer.code}" if layer.code else ""
        note = f" ({layer.detail})" if layer.status in (NOT_PERFORMED, NOT_APPLICABLE) and layer.detail else ""
        print(f"{layer.name:11} {layer.status}{suffix}{note}")
    print(f"{result.verdict} kind={result.kind} version={result.version}" + (f" code={result.code}" if result.code else ""))


def _run_corpus(args) -> int:
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    out: dict[str, dict] = {}
    for item in manifest["items"]:
        payload = base64.b64decode(item["payloadBase64"]) if "payloadBase64" in item else item["payloadText"].encode("utf-8")
        topic_device = item.get("topicDeviceId")
        result = validate(item["kind"], payload, legacy=bool(item.get("legacy")), declared_version=item.get("declaredVersion"),
                          topic_device_id=topic_device, capability_profile=item.get("capabilityProfile"))
        entry = result.as_dict()
        out[item["id"]] = {"verdict": entry["verdict"], "code": entry["code"], "version": entry["version"],
                           "layers": {layer["layer"]: [layer["status"], layer["code"]] for layer in entry["layers"]}}
    Path(args.output).write_text(json.dumps({"schemaVersion": 1, "results": out}, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    return EXIT_ACCEPT


def schema_files() -> list[str]:
    table = loader.versions()
    return sorted(list(table["kinds"].values()) + [p["command"] for p in table["profiles"].values()])


def schema_hashes() -> dict[str, str]:
    import hashlib
    return {name: hashlib.sha256((loader.contracts_dir() / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest() for name in schema_files()}


def _run_hashes(args) -> int:
    hashes = schema_hashes()
    if args.write:
        path = loader.contracts_dir() / "versions.json"
        table = json.loads(path.read_text(encoding="utf-8"))
        table["schemaSha256"] = hashes
        path.write_text(json.dumps(table, indent=2) + "\n", encoding="utf-8", newline="\n")
        loader.versions.cache_clear()
    for name, digest in hashes.items():
        print(f"{digest}  {name}")
    return EXIT_ACCEPT


def main(argv: list[str] | None = None) -> int:
    try:
        args = _parser().parse_args(argv)
    except SystemExit as exit_:
        return EXIT_USAGE if exit_.code else EXIT_ACCEPT
    try:
        if args.command == "validate":
            return _run_validate(args)
        if args.command == "validate-corpus":
            return _run_corpus(args)
        if args.command == "schema-hashes":
            return _run_hashes(args)
        print(json.dumps(loader.versions(), indent=2))
        return EXIT_ACCEPT
    except (OSError, ValueError, KeyError) as error:
        print(f"usage error: {type(error).__name__}", file=sys.stderr)
        return EXIT_USAGE


if __name__ == "__main__":
    raise SystemExit(main())
