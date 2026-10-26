"""Generates (or checks) sdk/esp32/test/golden/commands.tsv, the expected verdicts of the ESP32 library's command pipeline.

Every row is: id <TAB> device clock (epoch seconds) <TAB> payload as hexadecimal bytes ("-" for an empty payload) <TAB> expected disposition.

The expectation comes from `firmware_model`, a transcription of the command handler in firmware/src/main.cpp (read, never executed or
changed) in the library's documented policy (commandId must be canonical UUID text; see README). It is not trusted on its own: for every
row that reaches a verdict the contract validator (nexus_contract, profile esp32-legacy-v1, the same clock) must agree, so the golden file,
the firmware transcription and the schema authority are three witnesses of the same rule.

    python tools/gen_golden.py --write     regenerate the file
    python tools/gen_golden.py --check     fail when the committed file is stale (the default)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "sdk" / "python"))

from decimal import Decimal  # noqa: E402

from nexus_contract import rules  # noqa: E402
from nexus_contract.validator import validate  # noqa: E402

FIXTURES = ROOT / "contracts" / "fixtures" / "firmware-v1"
OUT = Path(__file__).resolve().parents[1] / "test" / "golden" / "commands.tsv"
MANIFEST = json.loads((FIXTURES / "manifest.json").read_text(encoding="utf-8"))
T = MANIFEST["constants"]["referenceEpoch"]
CID = MANIFEST["constants"]["commandId"]
MIN_EPOCH = 1704067200
MAX_PAYLOAD = 512


def firmware_model(payload: bytes, now: int) -> str:
    if now < MIN_EPOCH:
        return "NO_ACK:CLOCK_NOT_SYNCED"
    if len(payload) == 0 or len(payload) > MAX_PAYLOAD:
        return "NO_ACK:NOT_JSON"
    try:
        data = rules.parse_json(payload)
    except rules.NotJson:
        return "NO_ACK:NOT_JSON"
    if not isinstance(data, dict):
        return "NO_ACK:NOT_JSON"
    command_id = data.get("commandId")
    if not isinstance(command_id, str) or not rules.is_uuid(command_id):
        return "NO_ACK:NO_COMMAND_ID"
    capability, command, value, timestamp = (data.get(k) for k in ("capability", "command", "value", "timestamp"))
    if (not isinstance(capability, str) or not isinstance(command, str) or not isinstance(value, bool) or isinstance(timestamp, bool)
            or not isinstance(timestamp, int) or isinstance(timestamp, Decimal) or not -2147483648 <= timestamp <= 2147483647):
        return "REJECTED:MALFORMED"
    if capability != "LED" or command != "SET_STATE":
        return "REJECTED:UNSUPPORTED"
    if abs(now - timestamp) > 30:
        return "REJECTED:STALE"
    return "ACCEPTED"


def command(**changes) -> bytes:
    body = {"commandId": CID, "capability": "LED", "command": "SET_STATE", "value": True, "timestamp": T}
    body.update(changes)
    return json.dumps({k: v for k, v in body.items() if v is not ...}, separators=(",", ":")).encode()


def extras() -> list[tuple[str, int, bytes]]:
    led = command()
    rows: list[tuple[str, int, bytes]] = [
        ("x:clock-zero", 0, led),
        ("x:clock-just-below-plausible", MIN_EPOCH - 1, command(timestamp=MIN_EPOCH - 1)),
        ("x:clock-at-plausible", MIN_EPOCH, command(timestamp=MIN_EPOCH)),
        ("x:empty-payload", T, b""),
        ("x:empty-object", T, b"{}"),
        ("x:array-top-level", T, b"[]"),
        ("x:string-top-level", T, b'"LED"'),
        ("x:trailing-garbage", T, led + b"x"),
        ("x:trailing-whitespace", T, led + b" \t\r\n"),
        ("x:leading-whitespace", T, b" \n" + led),
        ("x:form-feed-is-not-whitespace", T, b"\x0c" + led),
        ("x:utf8-bom", T, b"\xef\xbb\xbf" + led),
        ("x:invalid-utf8-in-string", T, led.replace(b"LED", b"L\xffD")),
        ("x:overlong-utf8", T, led.replace(b"LED", b"L\xc0\x80D")),
        ("x:truncated-utf8-at-end", T, b'{"a":"\xe2\x82'),
        ("x:encoded-surrogate-utf8", T, led.replace(b"LED", b"L\xed\xa0\x80D")),
        ("x:capability-escaped-led", T, led.replace(b'"LED"', b'"L\\u0045D"')),
        ("x:capability-escaped-led-lowercase-hex", T, led.replace(b'"LED"', b'"\\u004cED"')),
        ("x:command-escaped", T, led.replace(b'"SET_STATE"', b'"SET\\u005fSTATE"')),
        ("x:key-escaped", T, led.replace(b'"capability"', b'"capabilit\\u0079"')),
        ("x:duplicate-key", T, led[:-1] + b',"value":false}'),
        ("x:duplicate-key-escaped-form", T, led[:-1] + b',"\\u0076alue":false}'),
        ("x:duplicate-key-nested", T, led[:-1] + b',"extra":{"a":1,"a":2}}'),
        ("x:same-key-in-different-objects-is-fine", T, led[:-1] + b',"extra":{"value":1},"more":{"value":2}}'),
        ("x:unknown-members-are-ignored", T, led[:-1] + b',"extra":{"deep":[1,2,{"k":null}]},"flag":false}'),
        ("x:member-order-differs", T, b'{"timestamp":%d,"value":false,"command":"SET_STATE","capability":"LED","commandId":"%s"}' % (T, CID.encode())),
        ("x:lone-high-surrogate-in-ignored-string", T, led[:-1] + b',"s":"\\ud800"}'),
        ("x:surrogate-pair-in-ignored-string", T, led[:-1] + b',"s":"\\ud83d\\ude00"}'),
        ("x:nul-escape-in-ignored-string", T, led[:-1] + b',"s":"a\\u0000b"}'),
        ("x:raw-control-character-in-string", T, led[:-1] + b',"s":"a\tb"}'),
        ("x:bad-escape", T, led[:-1] + b',"s":"\\q"}'),
        ("x:short-unicode-escape", T, led[:-1] + b',"s":"\\u12"}'),
        ("x:single-quotes", T, b"{'commandId':'x'}"),
        ("x:trailing-comma-object", T, led[:-1] + b",}"),
        ("x:trailing-comma-array", T, led[:-1] + b',"a":[1,]}'),
        ("x:leading-zero-number", T, led[:-1] + b',"n":01}'),
        ("x:nan", T, led[:-1] + b',"n":NaN}'),
        ("x:infinity", T, led[:-1] + b',"n":Infinity}'),
        ("x:number-minus-only", T, led[:-1] + b',"n":-}'),
        ("x:number-dot-only", T, led[:-1] + b',"n":1.}'),
        ("x:number-exponent-only", T, led[:-1] + b',"n":1e}'),
        ("x:commandId-missing", T, command(commandId=...)),
        ("x:commandId-number", T, command(commandId=7)),
        ("x:commandId-null", T, command(commandId=None)),
        ("x:commandId-not-a-uuid", T, command(commandId="cmd-1")),
        ("x:commandId-java-short-form", T, command(commandId="1-1-1-1-1")),
        ("x:commandId-uppercase-uuid", T, command(commandId=CID.upper())),
        ("x:commandId-with-padding", T, command(commandId=" " + CID)),
        ("x:commandId-escaped-uuid", T, led.replace(b'"a1b2c3d4-', b'"\\u0061\\u0031b2c3d4-')),
        ("x:capability-missing", T, command(capability=...)),
        ("x:capability-number", T, command(capability=1)),
        ("x:command-missing", T, command(command=...)),
        ("x:value-missing", T, command(value=...)),
        ("x:value-null", T, command(value=None)),
        ("x:value-string", T, command(value="true")),
        ("x:value-number-one", T, command(value=1)),
        ("x:value-array", T, command(value=[True])),
        ("x:value-object", T, command(value={})),
        ("x:timestamp-missing", T, command(timestamp=...)),
        ("x:timestamp-string", T, command(timestamp=str(T))),
        ("x:timestamp-null", T, command(timestamp=None)),
        ("x:timestamp-bool", T, command(timestamp=True)),
        ("x:timestamp-float-integral", T, led.replace(b'"timestamp":%d' % T, b'"timestamp":%d.0' % T)),
        ("x:timestamp-exponent", T, led.replace(b'"timestamp":%d' % T, b'"timestamp":1.7672256e9')),
        ("x:timestamp-int32-max-is-stale-not-malformed", T, command(timestamp=2147483647)),
        ("x:timestamp-above-int32", T, command(timestamp=2147483648)),
        ("x:timestamp-int32-min-is-stale-not-malformed", T, command(timestamp=-2147483648)),
        ("x:timestamp-below-int32", T, command(timestamp=-2147483649)),
        ("x:timestamp-huge-digits", T, led.replace(b'"timestamp":%d' % T, b'"timestamp":' + b"9" * 30)),
        ("x:timestamp-minus-zero", T, led.replace(b'"timestamp":%d' % T, b'"timestamp":-0')),
        ("x:timestamp-at-clock-int32-edge", 2147483647, command(timestamp=2147483647)),
        ("x:capability-lowercase", T, command(capability="led")),
        ("x:capability-padded", T, command(capability="LED ")),
        ("x:command-lowercase", T, command(command="set_state")),
        ("x:command-toggle", T, command(command="TOGGLE")),
        ("x:capability-prefix-of-led", T, command(capability="LE")),
        ("x:capability-longer-than-led", T, command(capability="LEDD")),
        ("x:unsupported-wins-over-stale", T, command(capability="FAN", timestamp=T - 1000)),
        ("x:malformed-wins-over-unsupported", T, command(capability="FAN", value="on")),
        ("x:malformed-wins-over-stale", T, command(value="on", timestamp=T - 1000)),
        ("x:stale-future", T, command(timestamp=T + 31)),
        ("x:fresh-future-edge", T, command(timestamp=T + 30)),
        ("x:fresh-past-edge", T, command(timestamp=T - 30)),
        ("x:stale-past-edge", T, command(timestamp=T - 31)),
        ("x:payload-at-limit", T, led[:-1] + b',"pad":"' + b"a" * (MAX_PAYLOAD - len(led) - 9) + b'"}'),
        ("x:payload-over-limit", T, led[:-1] + b',"pad":"' + b"a" * (MAX_PAYLOAD - len(led) - 8) + b'"}'),
        ("x:nested-depth-30", T, led[:-1] + b',"d":' + b"[" * 29 + b"]" * 29 + b"}"),
        ("x:large-valid-key-count", T, led[:-1] + b"," + b",".join(b'"k%d":%d' % (i, i) for i in range(40)) + b"}"),
    ]
    return rows


def rows() -> list[tuple[str, int, bytes]]:
    out: list[tuple[str, int, bytes]] = []
    for path in sorted(FIXTURES.glob("command-*.json")):
        out.append((f"fixture:{path.stem}", T, path.read_bytes()))
    corpus = json.loads((ROOT / "contracts" / "parity" / "corpus.json").read_text(encoding="utf-8"))
    for item in corpus["items"]:
        if item["kind"] == "command":
            out.append((item["id"], T, item["payloadText"].encode("utf-8")))
    led = (FIXTURES / "command-led-on.json").read_bytes()
    for offset in MANIFEST["freshnessCases"]["offsetsSeconds"]:
        out.append((f"freshness:{offset:+d}", T + offset, led))  # manifest: referenceTime = command timestamp + offset
    out.extend(extras())
    return out


def witness(payload: bytes, now: int, expected: str) -> None:
    """The contract validator, with the profile and the same clock, must agree with the firmware transcription."""
    if expected.startswith("NO_ACK"):
        return
    result = validate("command", payload, profile="esp32-legacy-v1", reference_time=now)
    if expected == "ACCEPTED":
        assert result.verdict == "ACCEPT", (expected, result)
    else:
        assert result.verdict == "REJECT", (expected, result)
        reason = expected.split(":")[1]
        if reason == "STALE":
            assert result.code == "STALE", result
        elif reason == "UNSUPPORTED":
            assert result.code in ("UNSUPPORTED",), result
        else:
            assert result.code not in ("STALE", "UNSUPPORTED"), result


def render() -> str:
    lines = ["# generated by tools/gen_golden.py; do not edit. id<TAB>clock<TAB>payload hex<TAB>expected disposition"]
    seen = set()
    for case_id, now, payload in rows():
        assert case_id not in seen, case_id
        seen.add(case_id)
        expected = firmware_model(payload, now)
        witness(payload, now, expected)
        lines.append(f"{case_id}\t{now}\t{payload.hex() or '-'}\t{expected}")
    return "\n".join(lines) + "\n"


def main() -> int:
    text = render()
    if "--write" in sys.argv:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(text, encoding="utf-8", newline="\n")
        print(f"wrote {OUT} ({text.count(chr(10)) - 1} cases)")
        return 0
    if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
        print("commands.tsv is stale: run python tools/gen_golden.py --write", file=sys.stderr)
        return 1
    print(f"commands.tsv is current ({text.count(chr(10)) - 1} cases)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
