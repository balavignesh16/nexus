"""Builds the differential corpus ``contracts/parity/corpus.json``: real payloads (the frozen firmware fixtures, the
shapes the simulator and the backend emit) plus single-fault mutations of them. The same file is read by the backend
differential test (which runs every item through the unmodified backend decoding path) and by the Python validator.

    python -m nexus_contract.corpus --write      regenerate the committed file
    python -m nexus_contract.corpus --check      fail if the committed file is stale
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
from pathlib import Path

from . import loader

D = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13"
C = "a1b2c3d4-e5f6-4789-8abc-def012345678"
T = 1767225600
TS = "2026-01-01T00:00:00Z"


def _telemetry(**over) -> str:
    body = {"deviceId": D, "timestamp": TS, "sensorType": "TEMPERATURE_SENSOR", "value": 24.5, "unit": "CELSIUS"}
    for key, value in over.items():
        if value is _DROP:
            body.pop(key, None)
        else:
            body[key] = value
    return json.dumps(body, separators=(",", ":"))


def _ack(**over) -> str:
    body = {"commandId": C, "status": "ACCEPTED", "timestamp": T}
    for key, value in over.items():
        if value is _DROP:
            body.pop(key, None)
        else:
            body[key] = value
    return json.dumps(body, separators=(",", ":"))


class _Drop:
    pass


_DROP = _Drop()


def build() -> dict:
    items: list[dict] = []

    def add(item_id, kind, text=None, *, raw: bytes | None = None, origin="mutation", topic_device=D, **extra):
        entry = {"id": item_id, "kind": kind, "origin": origin}
        if raw is not None:
            entry["payloadBase64"] = base64.b64encode(raw).decode("ascii")
        else:
            entry["payloadText"] = text
        if kind == "telemetry":
            entry["topicDeviceId"] = topic_device
        entry.update(extra)
        items.append(entry)

    # --- every frozen fixture of the structural kinds (command fixtures are judged by the profile tests)
    manifest = json.loads((loader.contracts_dir() / "fixtures" / "firmware-v1" / "manifest.json").read_text(encoding="utf-8"))
    for fixture in manifest["fixtures"]:
        if fixture["kind"] == "command":
            continue
        raw = (loader.contracts_dir() / "fixtures" / "firmware-v1" / fixture["file"]).read_bytes()
        add("fixture:" + fixture["id"], fixture["kind"], raw.decode("utf-8"), origin="fixture:" + fixture["origin"],
            topic_device=fixture.get("topicDeviceId", D))

    # --- telemetry mutations
    t = lambda name, text, **kw: add("telemetry:" + name, "telemetry", text, **kw)
    t("timestamp-offset-plus-one-hour", _telemetry(timestamp="2026-01-01T01:00:00+01:00"))
    t("timestamp-fraction-9", _telemetry(timestamp="2026-01-01T00:00:00.123456789Z"))
    t("timestamp-fraction-10", _telemetry(timestamp="2026-01-01T00:00:00.1234567891Z"))
    t("timestamp-lowercase-tz", _telemetry(timestamp="2026-01-01t00:00:00z"))
    t("timestamp-no-zone", _telemetry(timestamp="2026-01-01T00:00:00"))
    t("timestamp-date-only", _telemetry(timestamp="2026-01-01"))
    t("timestamp-impossible-date", _telemetry(timestamp="2026-02-30T00:00:00Z"))
    t("timestamp-epoch-integer", _telemetry(timestamp=T))
    t("timestamp-epoch-fraction", _telemetry(timestamp=1767225600.5))
    t("timestamp-null", _telemetry(timestamp=None))
    t("timestamp-missing", _telemetry(timestamp=_DROP))
    t("timestamp-boolean", _telemetry(timestamp=True))
    t("deviceId-uppercase", _telemetry(deviceId=D.upper()))
    t("deviceId-32-hex-no-dashes", _telemetry(deviceId=D.replace("-", "")))
    t("deviceId-java-short-form", _telemetry(deviceId="1-1-1-1-1"))
    t("deviceId-24-char-base64", _telemetry(deviceId="AAAAAAAAAAAAAAAAAAAAAAAA"))
    t("deviceId-null", _telemetry(deviceId=None))
    t("deviceId-number", _telemetry(deviceId=5))
    t("deviceId-empty", _telemetry(deviceId=""))
    t("value-integer", _telemetry(value=5))
    t("value-boolean-legacy", _telemetry(value=True))
    t("value-null", _telemetry(value=None))
    t("value-missing", _telemetry(value=_DROP))
    t("value-string-legacy", _telemetry(value="abc"))
    t("value-array-legacy", _telemetry(value=[1]))
    t("value-object-legacy", _telemetry(value={}))
    t("value-huge-exponent", '{"deviceId":"%s","timestamp":"%s","sensorType":"S","value":1e308,"unit":"U"}' % (D, TS))
    t("sensorType-missing", _telemetry(sensorType=_DROP))
    t("sensorType-null", _telemetry(sensorType=None))
    t("sensorType-blank", _telemetry(sensorType="   "))
    t("sensorType-number", _telemetry(sensorType=5))
    t("sensorType-boolean", _telemetry(sensorType=True))
    t("sensorType-object", _telemetry(sensorType={}))
    t("sensorType-array", _telemetry(sensorType=[]))
    t("unit-blank", _telemetry(unit=""))
    t("unit-number", _telemetry(unit=1))
    t("generic-value-string", _telemetry(capabilityKey="mode", value="AUTO", sensorType=_DROP, unit=_DROP))
    t("generic-value-boolean", _telemetry(capabilityKey="motion", value=False, sensorType=_DROP, unit=_DROP))
    t("generic-value-object", _telemetry(capabilityKey="pose", value={"x": 1}, sensorType=_DROP, unit=_DROP))
    t("generic-value-array", _telemetry(capabilityKey="pose", value=[1, 2], sensorType=_DROP, unit=_DROP))
    t("generic-value-null", _telemetry(capabilityKey="pose", value=None, sensorType=_DROP, unit=_DROP))
    t("generic-without-sensorType-and-unit", _telemetry(capabilityKey="temp", sensorType=_DROP, unit=_DROP))
    t("capabilityKey-empty-falls-back-to-legacy", _telemetry(capabilityKey=""))
    t("capabilityKey-blank-falls-back-to-legacy", _telemetry(capabilityKey="  "))
    t("capabilityKey-blank-and-no-legacy-fields", _telemetry(capabilityKey=" ", sensorType=_DROP, unit=_DROP))
    t("capabilityKey-null", _telemetry(capabilityKey=None))
    t("capabilityKey-number", _telemetry(capabilityKey=5, sensorType=_DROP, unit=_DROP))
    t("capabilityKey-object", _telemetry(capabilityKey={}, sensorType=_DROP, unit=_DROP))
    t("messageId-valid-64", _telemetry(messageId="a" * 64))
    t("messageId-65", _telemetry(messageId="a" * 65))
    t("messageId-empty", _telemetry(messageId=""))
    t("messageId-null", _telemetry(messageId=None))
    t("messageId-trailing-newline", _telemetry(messageId="abc\n"))
    t("deviceId-trailing-newline", _telemetry(deviceId=D + "\n"))
    t("timestamp-trailing-newline", _telemetry(timestamp=TS + "\n"))
    t("payload-surrounding-whitespace", "\n  " + _telemetry() + "\r\n")
    t("messageId-number", _telemetry(messageId=123))
    t("messageId-boolean", _telemetry(messageId=True))
    t("messageId-object", _telemetry(messageId={}))
    t("messageId-slash", _telemetry(messageId="a/b"))
    t("messageId-unicode", _telemetry(messageId="café"))
    t("messageId-allowed-punctuation", _telemetry(messageId="A-z_0.9:Z"))
    t("messageId-with-invalid-deviceId", _telemetry(messageId="a b", deviceId=None))
    t("readbackCommandId-valid", _telemetry(readbackCommandId=C))
    t("readbackCommandId-invalid", _telemetry(readbackCommandId="nope"))
    t("readbackCommandId-null", _telemetry(readbackCommandId=None))
    t("readbackCommandId-empty", _telemetry(readbackCommandId=""))
    t("timestamp-empty-string", _telemetry(timestamp=""))
    t("payload-duplicate-key-same-value", '{"deviceId":"%s","deviceId":"%s","timestamp":"%s","sensorType":"S","value":1,"unit":"U"}' % (D, D, TS))
    t("unknown-field", _telemetry(extra=1))
    t("contractVersion-1.0-field", _telemetry(contractVersion="1.0"))
    t("topic-uppercase-device", _telemetry(), topic_device=D.upper())
    t("topic-other-device", _telemetry(), topic_device="9b1d3e57-2c44-4a0f-a6d8-71e5c0b3f492")
    t("payload-trailing-garbage", _telemetry() + " junk")
    t("payload-trailing-second-object", _telemetry() + _telemetry())
    t("payload-empty", "")
    t("payload-whitespace", "  ")
    t("payload-json-null", "null")
    t("payload-json-array", "[]")
    t("payload-json-number", "123")
    t("payload-json-string", '"text"')
    t("payload-nan-literal", '{"deviceId":"%s","timestamp":"%s","sensorType":"S","value":NaN,"unit":"U"}' % (D, TS))
    t("payload-duplicate-key-last-wins", '{"deviceId":"nope","deviceId":"%s","timestamp":"%s","sensorType":"S","value":1,"unit":"U"}' % (D, TS))
    t("payload-bom", "", raw=b"\xef\xbb\xbf" + _telemetry().encode("utf-8"))
    t("payload-invalid-utf8", "", raw=b'{"deviceId":"\xff"}')
    t("payload-comment", "/* c */" + _telemetry())
    t("payload-single-quotes", _telemetry().replace('"', "'"))
    t("payload-unquoted-key", '{deviceId:"%s"}' % D)
    t("payload-trailing-comma", _telemetry()[:-1] + ",}")
    t("payload-deep-nesting-in-unknown-field", _telemetry(extra=json.loads("[" * 50 + "]" * 50)))

    # --- acknowledgement mutations
    a = lambda name, text, **kw: add("ack:" + name, "acknowledgement", text, **kw)
    a("rejected-with-reason", _ack(status="REJECTED", reason="STALE"))
    a("reason-empty-string", _ack(status="REJECTED", reason=""))
    a("reason-null", _ack(status="REJECTED", reason=None))
    a("reason-number", _ack(status="REJECTED", reason=5))
    a("reason-object", _ack(status="REJECTED", reason={"a": 1}))
    a("reason-array", _ack(status="REJECTED", reason=["x"]))
    a("reason-boolean", _ack(status="REJECTED", reason=True))
    a("reason-long-1000", _ack(status="REJECTED", reason="r" * 1000))
    a("status-lowercase", _ack(status="accepted"))
    a("status-trailing-space", _ack(status="ACCEPTED "))
    a("status-unknown", _ack(status="PENDING"))
    a("status-number", _ack(status=1))
    a("status-null", _ack(status=None))
    a("status-missing", _ack(status=_DROP))
    a("commandId-uppercase", _ack(commandId=C.upper()))
    a("commandId-java-short-form", _ack(commandId="1-1-1-1-1"))
    a("commandId-32-hex-no-dashes", _ack(commandId=C.replace("-", "")))
    a("commandId-number", _ack(commandId=5))
    a("commandId-trailing-newline", _ack(commandId=C + "\n"))
    a("payload-surrounding-whitespace", "\n " + _ack() + " \r\n")
    a("commandId-null", _ack(commandId=None))
    a("commandId-missing", _ack(commandId=_DROP))
    a("commandId-empty", _ack(commandId=""))
    a("timestamp-zero", _ack(timestamp=0))
    a("timestamp-negative", _ack(timestamp=-5))
    a("timestamp-float", _ack(timestamp=1767225600.5))
    a("timestamp-float-integral", '{"commandId":"%s","status":"ACCEPTED","timestamp":1767225600.0}' % C)
    a("timestamp-exponent", '{"commandId":"%s","status":"ACCEPTED","timestamp":1.7e9}' % C)
    a("timestamp-string", _ack(timestamp="1767225600"))
    a("timestamp-boolean", _ack(timestamp=True))
    a("timestamp-null", _ack(timestamp=None))
    a("timestamp-missing", _ack(timestamp=_DROP))
    a("timestamp-int64-max", _ack(timestamp=9223372036854775807))
    a("timestamp-beyond-int64", '{"commandId":"%s","status":"ACCEPTED","timestamp":99999999999999999999}' % C)
    a("unknown-field", _ack(extra=1))
    a("payload-duplicate-key", '{"commandId":"nope","commandId":"%s","status":"ACCEPTED","timestamp":%d}' % (C, T))
    a("timestamp-instant-max", _ack(timestamp=31556889864403199))
    a("timestamp-instant-max-plus-one", _ack(timestamp=31556889864403200))
    a("timestamp-instant-min", _ack(timestamp=-31557014167219200))
    a("timestamp-instant-min-minus-one", _ack(timestamp=-31557014167219201))
    a("contractVersion-1.0-field", _ack(contractVersion="1.0"))
    a("payload-trailing-garbage", _ack() + " junk")
    a("payload-empty", "")
    a("payload-json-null", "null")
    a("payload-json-array", "[]")
    a("payload-malformed", '{"commandId":')
    a("payload-bom", "", raw=b"\xef\xbb\xbf" + _ack().encode("utf-8"))

    # --- status
    s = lambda name, raw: add("status:" + name, "status", raw=raw)
    for name, text in (("online-padded-newline", " online\n"), ("offline-crlf", "offline\r\n"), ("tab-online", "\tonline"),
                       ("uppercase", "ONLINE"), ("titlecase", "Online"), ("offline-uppercase", "OFFLINE"), ("empty", ""),
                       ("spaces-only", "    "), ("truncated", "onlin"), ("double", "online online"),
                       ("json-quoted", '"online"'), ("json-object", '{"status":"online"}'),
                       ("nul-suffix", "online\u0000"), ("nul-prefix", "\u0000online"), ("control-prefix", "\u0001online"),
                       ("nbsp-suffix", "online "), ("bom-prefix", "﻿online"), ("zero-width-space", "online​"),
                       ("space-inside", "on line")):
        s(name, text.encode("utf-8"))
    s("invalid-utf8", b"\xff\xfeonline")
    s("online-then-invalid-byte", b"online\xff")

    # --- commands: what the backend emits (OutboxPayload) and the capability semantics (CommandValueValidator)
    def cmd(item_id, capability, value_json, *, command="SET_STATE", profile=None, emit=None, ts=T):
        text = '{"commandId":"%s","capability":"%s","command":"%s","value":%s,"timestamp":%s}' % (C, capability, command, value_json, ts)
        extra = {}
        if profile is not None:
            extra["capabilityProfile"] = profile
        if emit is not None:
            extra["emit"] = emit
        add(item_id, "command", text, origin="backend emitter" if emit is not None else "mutation", **extra)

    for capability, value_json, value in (("LED", "true", True), ("FAN", "false", False), ("BUZZER", "true", True), ("SERVO", "0", 0),
                                          ("SERVO", "90", 90), ("SERVO", "180", 180), ("RELAY", '{"channel":1,"state":true}', {"channel": 1, "state": True})):
        cmd(f"command:emit-{capability.lower()}-{value_json.replace(chr(34), '').replace(':', '-').replace(',', '-').replace('{', '').replace('}', '')}",
            capability, value_json, emit={"capability": capability, "command": "SET_STATE", "value": value},
            profile={"type": capability, "channels": [1, 2]})
    led, servo, relay = {"type": "LED"}, {"type": "SERVO"}, {"type": "RELAY", "channels": [1, 2]}
    for name, cap, value_json, profile in (
            ("led-true-string", "LED", '"true"', led), ("led-one", "LED", "1", led), ("led-zero", "LED", "0", led), ("led-object", "LED", "{}", led),
            ("fan-string", "FAN", '"on"', {"type": "FAN"}), ("buzzer-number", "BUZZER", "1", {"type": "BUZZER"}),
            ("servo-minus-one", "SERVO", "-1", servo), ("servo-181", "SERVO", "181", servo), ("servo-integral-float", "SERVO", "90.0", servo),
            ("servo-fraction", "SERVO", "90.5", servo), ("servo-string", "SERVO", '"90"', servo), ("servo-boolean", "SERVO", "true", servo),
            ("servo-exponent", "SERVO", "9e1", servo), ("servo-int32-overflow-wraps-to-valid", "SERVO", "4294967386", servo),
            ("servo-int32-overflow-negative", "SERVO", "2147483648", servo), ("servo-huge-exponent", "SERVO", "1e400", servo),
            ("relay-channel-not-configured", "RELAY", '{"channel":3,"state":true}', relay),
            ("relay-channel-integral-float", "RELAY", '{"channel":1.0,"state":false}', relay),
            ("relay-channel-fraction", "RELAY", '{"channel":1.5,"state":true}', relay),
            ("relay-channel-missing", "RELAY", '{"state":true}', relay), ("relay-state-missing", "RELAY", '{"channel":1}', relay),
            ("relay-state-string", "RELAY", '{"channel":1,"state":"true"}', relay), ("relay-channel-string", "RELAY", '{"channel":"1","state":true}', relay),
            ("relay-value-boolean", "RELAY", "true", relay), ("relay-extra-keys", "RELAY", '{"channel":2,"state":true,"extra":1}', relay)):
        cmd("command:semantic-" + name, cap, value_json, profile=profile)
    for name, command in (("lowercase-command", "set_state"), ("padded-command", "SET_STATE "), ("other-command", "TOGGLE")):
        cmd("command:semantic-" + name, "LED", "true", command=command, profile=led)
    cmd("command:structural-timestamp-float", "LED", "true", ts="1767225600.5")
    cmd("command:structural-value-null", "LED", "null")
    cmd("command:structural-value-array", "LED", "[true]")
    cmd("command:structural-value-string-generic", "MODE", '"AUTO"')

    return {"schemaVersion": 1, "device": D, "commandId": C,
            "note": "Generated by python -m nexus_contract.corpus. Do not edit by hand.", "items": items}


def _text(corpus: dict) -> str:
    return json.dumps(corpus, indent=1, ensure_ascii=True) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m nexus_contract.corpus")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    path = loader.contracts_dir() / "parity" / "corpus.json"
    text = _text(build())
    if args.write:
        path.write_text(text, encoding="utf-8", newline="\n")
        print(f"wrote {len(json.loads(text)['items'])} items")
        return 0
    if args.check:
        current = path.read_text(encoding="utf-8").replace("\r\n", "\n")
        if current != text:
            print("contracts/parity/corpus.json is stale; run python -m nexus_contract.corpus --write", file=sys.stderr)
            return 1
        return 0
    parser.error("choose --write or --check")


if __name__ == "__main__":
    raise SystemExit(main())
