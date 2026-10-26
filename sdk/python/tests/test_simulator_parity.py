"""Simulator parity (ledger P5-157, class C). The simulator's own CommandHandler is run, unmodified, against the same
command payloads and clock offsets as the ESP32 profile of the validator. Every disagreement must be a registered class C
difference in contracts/parity/known-differences.json (simulatorEntries). Since P5.5 only one is left, kept on purpose (commandId leniency)."""

from __future__ import annotations

import json
import sys
import unittest
from types import SimpleNamespace

from tests import support
from nexus_contract.validator import validate

SIMULATOR_SRC = support.REPO / "simulator" / "src"
T = 1767225600
C = "a1b2c3d4-e5f6-4789-8abc-def012345678"


def _handler(now: int):
    if str(SIMULATOR_SRC) not in sys.path:
        sys.path.insert(0, str(SIMULATOR_SRC))
    from nexus_simulator.actuators import LedActuator
    from nexus_simulator.command_handler import CommandHandler

    device = SimpleNamespace(write_capabilities={"LED": SimpleNamespace(actuator=LedActuator())})
    return CommandHandler(device, clock=lambda: now)


def _command(**over) -> bytes:
    body = {"commandId": C, "capability": "LED", "command": "SET_STATE", "value": True, "timestamp": T}
    body.update(over)
    return json.dumps(body).encode()


def _simulator(payload: bytes, now: int) -> str:
    _, status, reason = _handler(now).handle(payload)
    return "ACCEPT" if status == "ACCEPTED" else f"REJECT:{reason}"


def _validator(payload: bytes, now: int) -> str:
    result = validate("command", payload, profile="esp32-legacy-v1", reference_time=now)
    if result.verdict == "ACCEPT":
        return "ACCEPT"
    code = result.code
    return "REJECT:" + ("MALFORMED" if code in ("INVALID_FIELDS", "MALFORMED_JSON") else code)


CASES = {
    "fresh": (_command(), T),
    "age-29": (_command(), T + 29),
    "age-30": (_command(), T + 30),
    "age-31": (_command(), T + 31),
    "age-300": (_command(), T + 300),
    "age-301": (_command(), T + 301),
    "future-29": (_command(), T - 29),
    "future-31": (_command(), T - 31),
    "future-1000": (_command(), T - 1000),
    "timestamp-float": (_command(timestamp=T + 0.5), T),
    "timestamp-numeric-string": (_command(timestamp=str(T)), T),
    "commandId-not-a-uuid": (_command(commandId="abc"), T),
    "value-string": (_command(value="true"), T),
    "capability-fan": (_command(capability="FAN"), T),
    "command-other": (_command(command="TOGGLE"), T),
    "value-missing": (json.dumps({"commandId": C, "capability": "LED", "command": "SET_STATE", "timestamp": T}).encode(), T),
    "malformed-json": (b'{"commandId":', T),
}


class SimulatorParity(unittest.TestCase):
    def test_every_simulator_difference_is_a_registered_class_c_entry(self):
        registry = json.loads((support.CONTRACTS / "parity" / "known-differences.json").read_text(encoding="utf-8"))
        registered = {}
        for entry in registry["simulatorEntries"]:
            self.assertIn(entry["class"], ("C", "D"))
            self.assertTrue(entry["class"] == "D" and entry.get("retained") or entry.get("scheduled"), "a simulator gap is either retained on purpose or scheduled")
            for case, verdicts in entry["cases"].items():
                registered[case] = verdicts
        observed = {}
        for name, (payload, now) in CASES.items():
            simulator, validator = _simulator(payload, now), _validator(payload, now)
            if simulator != validator:
                observed[name] = {"simulator": simulator, "validator": validator}
        problems = []
        for name, verdicts in observed.items():
            if name not in registered:
                problems.append(f"UNREGISTERED {name}: {verdicts}")
            elif registered[name] != verdicts:
                problems.append(f"CHANGED {name}: registered {registered[name]} now {verdicts}")
        problems += [f"STALE {name}" for name in registered if name not in observed]
        self.assertEqual([], problems)

    def test_the_agreeing_cases_really_agree(self):
        for name in ("fresh", "age-29", "age-30", "age-301", "future-29", "value-string", "capability-fan", "command-other", "value-missing", "malformed-json"):
            payload, now = CASES[name]
            with self.subTest(name):
                self.assertEqual(_simulator(payload, now), _validator(payload, now))

    def test_freshness_rules_are_what_the_sources_say(self):
        payload, _ = CASES["fresh"]
        self.assertEqual("ACCEPT", _simulator(payload, T + 30), "the simulator window is the firmware's 30 s since P5.5")
        self.assertEqual("REJECT:STALE", _simulator(payload, T + 31))
        self.assertEqual("REJECT:STALE", _simulator(payload, T + 300), "the former 300-second window is gone")
        self.assertEqual("REJECT:STALE", _simulator(payload, T - 100000), "the simulator is absolute now: a future command is stale too")
        self.assertEqual("ACCEPT", _simulator(payload, T - 30))
        self.assertEqual("REJECT:STALE", _validator(payload, T + 31), "firmware window is 30 s")
        self.assertEqual("REJECT:STALE", _validator(payload, T - 31), "firmware rule is absolute")


if __name__ == "__main__":
    unittest.main()
