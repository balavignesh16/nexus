"""Unit tests of phase4_enablement_check.py (ledger P4-115). No Docker, no database, no network: the database and the HTTP calls are replaced
by fakes, so the script's decisions (which prerequisite fails what) are tested deterministically. The check against a REAL migrated
PostgreSQL, including the checksums Flyway recorded, is made by the backend crash-recovery stage (CommandEnablementProcedureTest)."""
from __future__ import annotations

import io
import json
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import phase4_enablement_check as check  # noqa: E402


def report_status(report: check.Report, name: str) -> str:
    matches = [c["status"] for c in report.checks if c["check"] == name]
    assert matches, f"no check named {name!r} in {[c['check'] for c in report.checks]}"
    return matches[0]


class FakeDatabase:
    """Answers the exact queries the script runs; each can be turned into a failure."""

    def __init__(self, repo_checksums: bool = True):
        self.versions = {v: [v, str(check.flyway_checksum(check.repo_migration(v))), "t"] for v in check.REQUIRED_MIGRATIONS}
        self.tables = set(check.REQUIRED_TABLES)
        self.triggers = 6
        self.delivery_unknown = 0
        self.overdue = 0
        self.unresolved = 0
        self.rows = 5
        self.history_error = False

    def __call__(self, sql: str):
        if "flyway_schema_history" in sql:
            if self.history_error:
                raise RuntimeError("relation flyway_schema_history does not exist")
            return [list(v) for v in self.versions.values()]
        match = re.search(r"to_regclass\('public\.(\w+)'\)", sql)
        if match:
            return [["t" if match.group(1) in self.tables else "f"]]
        if "pg_trigger" in sql:
            return [[str(self.triggers)]]
        if "DELIVERY_UNKNOWN" in sql:
            return [[str(self.delivery_unknown)]]
        if "expires_at < now()" in sql:
            return [[str(self.overdue)]]
        if "FROM action_history" in sql:
            return [["0"]]
        if "state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')" in sql:
            return [[str(self.unresolved)]]
        if "count(*) FROM command_outbox" in sql:
            return [[str(self.rows)]]
        raise AssertionError("unexpected query: " + sql)


class ConfigChecks(unittest.TestCase):
    def passes(self, **overrides: str) -> bool:
        keys = {k.replace("_", "-"): v for k, v in overrides.items()}
        config = dict(check.DEFAULTS)
        for key, value in keys.items():
            config["nexus.command.outbox." + key if not key.startswith(("ack", "flow", "lifecycle")) else "nexus.command." + key.replace("flow-", "flow.")] = value
        return check.check_config(config).passed

    def test_the_defaults_in_this_script_are_the_defaults_of_application_yml(self):
        yml = (ROOT / "backend/src/main/resources/application.yml").read_text(encoding="utf-8")
        for key, default in check.DEFAULTS.items():
            if key == "nexus.command.ack-timeout":
                continue                    # not in application.yml: its default (PT30S) lives in the @Value of the two classes that read it
            leaf = key.rsplit(".", 1)[1]
            pattern = re.compile(r"^\s*" + re.escape(leaf) + r":\s*\$\{[A-Z0-9_]+:([^}]*)\}", re.MULTILINE)
            found = pattern.search(yml)
            self.assertIsNotNone(found, f"{key} is not in application.yml")
            self.assertEqual(default, found.group(1).strip(), f"default drift for {key}")

    def test_the_shipped_defaults_are_valid_and_not_enabled(self):
        report = check.check_config(check.effective_config({}, {}))
        self.assertTrue(report.passed, report.render())
        self.assertEqual("false", check.effective_config({}, {})["nexus.command.outbox.enabled"])

    def test_environment_variables_override_defaults(self):
        config = check.effective_config({}, {"NEXUS_COMMAND_OUTBOX_DELIVERY_WINDOW": "PT25S"})
        self.assertEqual("PT25S", config["nexus.command.outbox.delivery-window"])
        self.assertFalse(check.check_config(config).passed)

    def test_every_invalid_setting_fails(self):
        base = dict(check.DEFAULTS)
        invalid = {
            "nexus.command.outbox.delivery-window": ["PT25S", "PT1S", "PT15.5S", "15", "PT0S"],
            "nexus.command.outbox.max-attempts": ["0", "6", "2.5", "many"],
            "nexus.command.outbox.attempt-timeout": ["PT0S", "PT20S"],
            "nexus.command.outbox.lease-duration": ["PT4S", "PT1S"],
            "nexus.command.outbox.backoff-multiplier": ["0.5", "9"],
            "nexus.command.outbox.jitter-fraction": ["0.9", "-1"],
            "nexus.command.outbox.workers": ["0", "99"],
            "nexus.command.flow.device-queue-cap": ["0", "600", "5000"],
            "nexus.command.flow.global-queue-cap": ["0", "999999"],
            "nexus.command.flow.publish-rate-per-second": ["0", "5000"],
            "nexus.command.ack-timeout": ["PT0S", "soon"],
            "nexus.command.outbox.enabled": ["1", "on", "yes", ""],
        }
        for key, values in invalid.items():
            for value in values:
                config = dict(base)
                config[key] = value
                self.assertFalse(check.check_config(config).passed, f"{key}={value!r} must fail")

    def test_a_retry_schedule_that_does_not_fit_the_window_fails(self):
        config = dict(check.DEFAULTS)
        config.update({"nexus.command.outbox.delivery-window": "PT6S", "nexus.command.outbox.attempt-timeout": "PT3S"})
        report = check.check_config(config)
        self.assertFalse(report.passed)
        self.assertIn("does not fit", report.render())

    def test_enabling_requires_the_dispatcher_and_the_scanners(self):
        config = dict(check.DEFAULTS)
        config["nexus.command.outbox.enabled"] = "true"
        self.assertTrue(check.check_config(config).passed)
        for key in ("nexus.command.outbox.dispatcher-enabled", "nexus.command.lifecycle-scanners-enabled"):
            broken = dict(config)
            broken[key] = "false"
            self.assertFalse(check.check_config(broken).passed, key)
        off = dict(check.DEFAULTS)
        off["nexus.command.outbox.dispatcher-enabled"] = "false"
        self.assertTrue(check.check_config(off).passed, "with the flag off the dispatcher setting is not a prerequisite")

    def test_duration_parser(self):
        self.assertEqual(15.0, check.parse_duration("PT15S"))
        self.assertEqual(0.25, check.parse_duration("PT0.25S"))
        self.assertEqual(90.0, check.parse_duration("PT1M30S"))
        for bad in ("15s", "PT", "", "P1D", "PTxS"):
            with self.assertRaises(ValueError):
                check.parse_duration(bad)


class DatabaseChecks(unittest.TestCase):
    def test_a_good_environment_passes(self):
        report = check.check_database(FakeDatabase(), max_delivery_unknown=0)
        self.assertTrue(report.passed, report.render())

    def test_a_missing_migration_fails_by_name(self):
        database = FakeDatabase()
        del database.versions["40"]
        report = check.check_database(database, 0)
        self.assertFalse(report.passed)
        self.assertEqual("FAIL", report_status(report, "V40 applied"))

    def test_a_changed_checksum_fails(self):
        database = FakeDatabase()
        database.versions["38"][1] = str(int(database.versions["38"][1]) + 1)
        report = check.check_database(database, 0)
        self.assertEqual("FAIL", report_status(report, "V38 applied with this repository's checksum"))

    def test_a_failed_migration_fails(self):
        database = FakeDatabase()
        database.versions["37"][2] = "f"
        self.assertFalse(check.check_database(database, 0).passed)

    def test_a_missing_table_fails(self):
        database = FakeDatabase()
        database.tables.discard("inbound_message_dedupe")
        report = check.check_database(database, 0)
        self.assertEqual("FAIL", report_status(report, "table inbound_message_dedupe"))

    def test_missing_guards_fail(self):
        database = FakeDatabase()
        database.triggers = 1
        self.assertEqual("FAIL", report_status(check.check_database(database, 0), "delete and update guards present"))

    def test_an_unreviewed_delivery_unknown_backlog_fails_until_it_is_declared_reviewed(self):
        database = FakeDatabase()
        database.delivery_unknown = 3
        self.assertFalse(check.check_database(database, 0).passed)
        self.assertTrue(check.check_database(database, 3).passed)
        self.assertFalse(check.check_database(database, 2).passed)

    def test_overdue_unresolved_obligations_fail(self):
        database = FakeDatabase()
        database.overdue = 1
        self.assertEqual("FAIL", report_status(check.check_database(database, 0), "no overdue unresolved obligation"))

    def test_an_unreadable_history_fails_instead_of_crashing(self):
        database = FakeDatabase()
        database.history_error = True
        report = check.check_database(database, 0)
        self.assertFalse(report.passed)
        self.assertEqual("FAIL", report_status(report, "migration history readable"))

    def test_the_checksum_algorithm_ignores_line_endings_and_a_bom_like_flyway(self):
        with tempfile.TemporaryDirectory() as directory:
            lf, crlf, bom = (Path(directory) / n for n in ("lf.sql", "crlf.sql", "bom.sql"))
            lf.write_bytes(b"SELECT 1;\nSELECT 2;\n")
            crlf.write_bytes(b"SELECT 1;\r\nSELECT 2;\r\n")
            bom.write_bytes("﻿SELECT 1;\nSELECT 2;\n".encode("utf-8"))
            self.assertEqual(check.flyway_checksum(lf), check.flyway_checksum(crlf))
            self.assertEqual(check.flyway_checksum(lf), check.flyway_checksum(bom))
            changed = Path(directory) / "changed.sql"
            changed.write_bytes(b"SELECT 1;\nSELECT 3;\n")
            self.assertNotEqual(check.flyway_checksum(lf), check.flyway_checksum(changed))

    def test_the_four_required_migrations_exist_in_this_repository(self):
        for version in check.REQUIRED_MIGRATIONS:
            self.assertIsNotNone(check.repo_migration(version), f"V{version}")


class RollbackAndSoakChecks(unittest.TestCase):
    def config(self, **flags: str) -> dict[str, str]:
        config = dict(check.DEFAULTS)
        config.update(flags)
        return config

    def test_rollback_requires_the_flag_off_the_dispatcher_on_and_nothing_unresolved(self):
        good = check.check_rollback(FakeDatabase(), self.config())
        self.assertTrue(good.passed, good.render())
        self.assertFalse(check.check_rollback(FakeDatabase(), self.config(**{"nexus.command.outbox.enabled": "true"})).passed)
        self.assertFalse(check.check_rollback(FakeDatabase(), self.config(**{"nexus.command.outbox.dispatcher-enabled": "false"})).passed)
        busy = FakeDatabase()
        busy.unresolved = 2
        self.assertFalse(check.check_rollback(busy, self.config()).passed)

    def test_rollback_reports_that_evidence_is_kept(self):
        report = check.check_rollback(FakeDatabase(), self.config())
        self.assertTrue(any(c["check"] == "evidence kept" and "never deletes" in c["detail"] for c in report.checks))

    def fetch(self, utilization: float = 0.1, items: int = 0, history_ok: bool = True):
        def fetch(url: str, timeout: float = 10.0):
            if "nexus.command.queue.unresolved" in url:
                return {"measurements": [{"value": 1.0}]}
            if "nexus.command.queue.utilization" in url:
                return {"measurements": [{"value": utilization}]}
            if "dead-letters" in url:
                return {"items": [{"uncertain": i == 0} for i in range(items)]}
            if "history" in url:
                if not history_ok:
                    raise OSError("refused")
                return []
            raise OSError("unexpected " + url)
        return fetch

    def test_post_enable_soak_checks(self):
        self.assertTrue(check.check_post_enable("http://x", 0.8, 0, self.fetch()).passed)
        self.assertFalse(check.check_post_enable("http://x", 0.8, 0, self.fetch(utilization=0.95)).passed)
        self.assertFalse(check.check_post_enable("http://x", 0.8, 0, self.fetch(items=2)).passed)
        self.assertTrue(check.check_post_enable("http://x", 0.8, 2, self.fetch(items=2)).passed)
        self.assertFalse(check.check_post_enable("http://x", 0.8, 0, self.fetch(history_ok=False)).passed)

    def test_post_enable_fails_when_the_metrics_or_the_api_are_missing(self):
        def down(url: str, timeout: float = 10.0):
            raise OSError("connection refused")
        report = check.check_post_enable("http://x", 0.8, 0, down)
        self.assertFalse(report.passed)
        self.assertEqual("FAIL", report_status(report, "queue metrics exposed"))


class CommandLine(unittest.TestCase):
    def run_main(self, argv: list[str]) -> tuple[int, dict]:
        out = io.StringIO()
        with redirect_stdout(out):
            code = check.main(argv)
        return code, json.loads(out.getvalue())

    def test_config_exit_codes(self):
        code, body = self.run_main(["config"])
        self.assertEqual((0, "PASS"), (code, body["overall"]))
        code, body = self.run_main(["config", "--set", "nexus.command.outbox.delivery-window=PT30S"])
        self.assertEqual((1, "FAIL"), (code, body["overall"]))

    def test_an_unknown_key_is_a_usage_error(self):
        with self.assertRaises(SystemExit):
            self.run_main(["config", "--set", "nexus.command.bogus=1"])

    def test_the_psql_runner_wraps_every_statement_in_a_read_only_transaction(self):
        with tempfile.TemporaryDirectory() as directory:
            capture = Path(directory) / "stdin.txt"
            fake = Path(directory) / "fake_psql.py"
            fake.write_text("import sys, pathlib\npathlib.Path(sys.argv[1]).write_text(sys.stdin.read())\nprint('7|x')\n", encoding="utf-8")
            run = check.make_psql_runner(f'"{sys.executable}" "{fake}" "{capture}"')
            self.assertEqual([["7", "x"]], run("SELECT 1"))
            script = capture.read_text()
            self.assertTrue(script.startswith("BEGIN READ ONLY;"), script)
            self.assertTrue(script.rstrip().endswith("COMMIT;"), script)
            self.assertNotRegex(script, r"(?i)\b(insert|update|delete|drop|alter|truncate|create)\b")

    def test_the_script_contains_no_statement_that_changes_data(self):
        source = (ROOT / "phase4_enablement_check.py").read_text(encoding="utf-8")
        queries = re.findall(r'run\(f?"([^"]+)"', source)
        self.assertGreater(len(queries), 5)
        for query in queries:
            self.assertRegex(query.lstrip(), r"(?i)^SELECT\b", query)


if __name__ == "__main__":
    unittest.main()
