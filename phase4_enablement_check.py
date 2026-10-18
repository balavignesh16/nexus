#!/usr/bin/env python3
"""Phase 4 outbox enablement and rollback checks (contract R22, ledger P4-115).

Read-only. It changes no setting, writes no row and never starts or stops anything: every database statement runs inside a READ ONLY
transaction, and the HTTP calls are GETs. It is meant to be pointed at a DISPOSABLE copy or a staging environment BEFORE and AFTER enabling
`nexus.command.outbox.enabled`, and it exits non-zero when any prerequisite is missing, so it can gate an operator's checklist or a script.

    python phase4_enablement_check.py config   [--set KEY=VALUE ...]
    python phase4_enablement_check.py database --psql "docker exec -i <container> psql -U <user> -d <db>" [--max-delivery-unknown N]
    python phase4_enablement_check.py post-enable --base-url http://host:8080 [--max-utilization 0.8] [--max-dead-letters N]
    python phase4_enablement_check.py rollback --psql "..." [--set nexus.command.outbox.enabled=false]

R22 steps the checks cover: (1) V37 to V40 applied with the checksums of this repository and the tables and guards present  [database];
(3) no unreviewed DELIVERY_UNKNOWN backlog  [database]; settings valid and consistent  [config]; (5) the soak signals  [post-enable];
rollback is drain-only: the flag is false and no obligation is left unresolved  [rollback]. Steps (2) the validation gate, (4) and (6) the
staged enablement are operator actions; this script does not and cannot perform them.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import urllib.error
import urllib.request
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"
APPLICATION_YML = ROOT / "backend" / "src" / "main" / "resources" / "application.yml"
REQUIRED_MIGRATIONS = ("37", "38", "39", "40")
REQUIRED_TABLES = ("command_outbox", "command_state_history", "inbound_message_dedupe")

# Mirrors the defaults of application.yml (a unit test compares the two, so a drift is caught) and the bounds of CommandOutboxSettings,
# CommandDeliveryLimits and CommandFlowSettings, which remain the authority: the application refuses to start on an invalid combination.
DEFAULTS = {
    "nexus.command.outbox.enabled": "false",
    "nexus.command.outbox.delivery-window": "PT15S",
    "nexus.command.outbox.max-attempts": "3",
    "nexus.command.outbox.attempt-timeout": "PT3S",
    "nexus.command.outbox.initial-backoff": "PT0.5S",
    "nexus.command.outbox.backoff-multiplier": "2.0",
    "nexus.command.outbox.max-backoff": "PT4S",
    "nexus.command.outbox.jitter-fraction": "0.2",
    "nexus.command.outbox.lease-duration": "PT6S",
    "nexus.command.outbox.claim-batch-size": "10",
    "nexus.command.outbox.poll-interval": "PT0.25S",
    "nexus.command.outbox.expiry-scan-interval": "PT1S",
    "nexus.command.outbox.workers": "4",
    "nexus.command.outbox.dispatcher-enabled": "true",
    "nexus.command.lifecycle-scanners-enabled": "true",
    "nexus.command.ack-timeout": "PT30S",
    "nexus.command.flow.global-queue-cap": "500",
    "nexus.command.flow.device-queue-cap": "10",
    "nexus.command.flow.publish-rate-per-second": "20",
}
MAX_DELIVERY_WINDOW = 20.0      # firmware freshness 30 s minus the 10 s uncertainty margin
MIN_DELIVERY_WINDOW = 2.0
LEASE_MARGIN = 2.0
TIMESTAMP_PRECISION_BUDGET = 1.0


# ------------------------------------------------------------------------------------------------ helpers

def parse_duration(text: str) -> float:
    match = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", text.strip().upper())
    if not match or not any(match.groups()):
        raise ValueError(f"not an ISO-8601 time duration: {text!r}")
    hours, minutes, seconds = (float(g) if g else 0.0 for g in match.groups())
    return hours * 3600 + minutes * 60 + seconds


def flyway_checksum(path: Path) -> int:
    """Flyway's checksum of a SQL migration: CRC32 over the UTF-8 bytes of every line, line terminators excluded, BOM stripped."""
    text = path.read_bytes().decode("utf-8")
    if text.startswith("\ufeff"):
        text = text[1:]
    lines = re.split(r"\r\n|\r|\n", text)       # BufferedReader.readLine semantics: only LF, CR and CRLF end a line
    if lines and lines[-1] == "":
        lines.pop()
    crc = 0
    for line in lines:
        crc = zlib.crc32(line.encode("utf-8"), crc)
    return crc - (1 << 32) if crc >= (1 << 31) else crc


def repo_migration(version: str) -> Path | None:
    found = sorted(MIGRATIONS.glob(f"V{version}__*.sql"))
    return found[0] if found else None


class Report:
    def __init__(self, name: str):
        self.name = name
        self.checks: list[dict] = []

    def add(self, check: str, ok: bool, detail: str) -> bool:
        self.checks.append({"check": check, "status": "PASS" if ok else "FAIL", "detail": detail})
        return ok

    def info(self, check: str, detail: str) -> None:
        self.checks.append({"check": check, "status": "INFO", "detail": detail})

    @property
    def passed(self) -> bool:
        return all(c["status"] != "FAIL" for c in self.checks)

    def render(self) -> str:
        return json.dumps({"command": self.name, "overall": "PASS" if self.passed else "FAIL", "checks": self.checks}, indent=2)


# ------------------------------------------------------------------------------------------------ config

def effective_config(overrides: dict[str, str], environ: dict[str, str] | None = None) -> dict[str, str]:
    """Defaults, then the NEXUS_* environment variables of application.yml, then explicit --set values."""
    env = os.environ if environ is None else environ
    config = dict(DEFAULTS)
    for key in DEFAULTS:
        variable = "NEXUS_" + key[len("nexus."):].upper().replace(".", "_").replace("-", "_")
        if variable in env:
            config[key] = env[variable]
    config.update(overrides)
    return config


def check_config(config: dict[str, str]) -> Report:
    report = Report("config")
    problems: list[str] = []

    def number(key: str) -> float:
        try:
            return float(config[key])
        except ValueError:
            problems.append(f"{key} is not a number: {config[key]!r}")
            return float("nan")

    def duration(key: str) -> float:
        try:
            return parse_duration(config[key])
        except ValueError as e:
            problems.append(f"{key}: {e}")
            return float("nan")

    for flag in ("nexus.command.outbox.enabled", "nexus.command.outbox.dispatcher-enabled", "nexus.command.lifecycle-scanners-enabled"):
        if config[flag].lower() not in ("true", "false"):
            problems.append(f"{flag} must be exactly true or false, not {config[flag]!r}")
    window = duration("nexus.command.outbox.delivery-window")
    attempt_timeout = duration("nexus.command.outbox.attempt-timeout")
    initial = duration("nexus.command.outbox.initial-backoff")
    maximum = duration("nexus.command.outbox.max-backoff")
    lease = duration("nexus.command.outbox.lease-duration")
    poll = duration("nexus.command.outbox.poll-interval")
    expiry_scan = duration("nexus.command.outbox.expiry-scan-interval")
    ack_timeout = duration("nexus.command.ack-timeout")
    attempts = number("nexus.command.outbox.max-attempts")
    multiplier = number("nexus.command.outbox.backoff-multiplier")
    jitter = number("nexus.command.outbox.jitter-fraction")
    workers = number("nexus.command.outbox.workers")
    batch = number("nexus.command.outbox.claim-batch-size")
    global_cap = number("nexus.command.flow.global-queue-cap")
    device_cap = number("nexus.command.flow.device-queue-cap")
    rate = number("nexus.command.flow.publish-rate-per-second")
    if not problems:
        if window < MIN_DELIVERY_WINDOW or window > MAX_DELIVERY_WINDOW:
            problems.append(f"delivery-window must be between {MIN_DELIVERY_WINDOW:g}s and {MAX_DELIVERY_WINDOW:g}s (firmware freshness), is {window:g}s")
        if window != int(window):
            problems.append("delivery-window must be a whole number of seconds")
        if attempts < 1 or attempts > 5 or attempts != int(attempts):
            problems.append("max-attempts must be a whole number from 1 to 5")
        if attempt_timeout <= 0 or attempt_timeout > window:
            problems.append("attempt-timeout must be positive and not exceed delivery-window")
        if initial <= 0 or maximum < initial:
            problems.append("initial-backoff must be positive and max-backoff at least initial-backoff")
        if not 1.0 <= multiplier <= 4.0:
            problems.append("backoff-multiplier must be between 1.0 and 4.0")
        if not 0.0 <= jitter <= 0.5:
            problems.append("jitter-fraction must be between 0.0 and 0.5")
        if lease < attempt_timeout + LEASE_MARGIN:
            problems.append(f"lease-duration must be at least attempt-timeout + {LEASE_MARGIN:g}s")
        if not 1 <= batch <= 100 or not 1 <= workers <= 16:
            problems.append("claim-batch-size must be 1..100 and workers 1..16")
        if not 0.05 <= poll <= 5.0 or not 0.1 <= expiry_scan <= 10.0:
            problems.append("poll-interval must be 50 ms..5 s and expiry-scan-interval 100 ms..10 s")
        if ack_timeout <= 0:
            problems.append("ack-timeout must be positive")
        if not 1 <= global_cap <= 100000 or not 1 <= device_cap <= 1000 or device_cap > global_cap or not 1 <= rate <= 1000:
            problems.append("flow limits: global-queue-cap 1..100000, device-queue-cap 1..1000 and not above the global cap, publish-rate-per-second 1..1000")
        if not problems:
            schedule = attempt_timeout * attempts
            for attempt in range(2, int(attempts) + 1):
                base = min(initial * (multiplier ** (attempt - 2)), maximum)
                schedule += base * (1.0 + jitter)
            if schedule > window:
                problems.append(f"worst-case retry schedule {schedule:.2f}s does not fit delivery-window {window:g}s")
            elif schedule + poll * attempts + TIMESTAMP_PRECISION_BUDGET > window:
                problems.append(f"worst-case end-to-end schedule {schedule + poll * attempts + TIMESTAMP_PRECISION_BUDGET:.2f}s does not fit delivery-window {window:g}s")
    report.add("settings valid", not problems, "; ".join(problems) if problems else "all outbox, flow and timeout settings are within their bounds")
    enabled = config["nexus.command.outbox.enabled"].lower() == "true"
    if enabled:
        report.add("dispatcher present while enabled", config["nexus.command.outbox.dispatcher-enabled"].lower() == "true",
                   "acceptance creates obligations only a running dispatcher can resolve")
        report.add("lifecycle scanners on while enabled", config["nexus.command.lifecycle-scanners-enabled"].lower() == "true",
                   "the ACK timeout and legacy reconciliation scanners must run in production")
    report.info("flag", f"nexus.command.outbox.enabled = {config['nexus.command.outbox.enabled']}")
    return report


# ------------------------------------------------------------------------------------------------ database

def make_psql_runner(psql: str):
    """Returns run(sql) -> list of rows (lists of text). Every statement runs in a READ ONLY transaction."""
    base = [t[1:-1] if len(t) >= 2 and t[0] == t[-1] == '"' else t for t in shlex.split(psql, posix=(os.name != "nt"))]

    def run(sql: str) -> list[list[str]]:
        # The statement goes in on STDIN as a script, so every result is printed (psql -c prints only the last of several statements).
        script = f"BEGIN READ ONLY;\n{sql.strip().rstrip(';')};\nCOMMIT;\n"
        result = subprocess.run(base + ["-v", "ON_ERROR_STOP=1", "-X", "-q", "-A", "-t", "-F", "|"],
                                input=script, capture_output=True, text=True, timeout=60, check=False)
        if result.returncode != 0:
            raise RuntimeError((result.stderr or result.stdout).strip()[:500])
        return [line.split("|") for line in result.stdout.splitlines() if line.strip() and line.strip() not in ("BEGIN", "COMMIT")]

    return run


def check_database(run, max_delivery_unknown: int, migrations_dir: Path | None = None) -> Report:
    report = Report("database")
    try:
        applied = {row[0]: row for row in run("SELECT version, checksum, success FROM flyway_schema_history WHERE version IS NOT NULL")}
    except Exception as e:                                                          # no history table at all, or no connection
        report.add("migration history readable", False, f"flyway_schema_history could not be read: {e}")
        return report
    for version in REQUIRED_MIGRATIONS:
        row = applied.get(version)
        if row is None:
            report.add(f"V{version} applied", False, f"V{version} is not in flyway_schema_history")
            continue
        path = repo_migration(version)
        if migrations_dir is not None:
            found = sorted(migrations_dir.glob(f"V{version}__*.sql"))
            path = found[0] if found else None
        success = row[2].strip().lower() in ("t", "true")
        if path is None:
            report.add(f"V{version} checksum", False, f"no V{version} migration file in this repository to compare with")
        else:
            expected = flyway_checksum(path)
            report.add(f"V{version} applied with this repository's checksum", success and int(row[1]) == expected,
                       f"success={success}, database checksum {row[1]}, repository checksum {expected}")
    for table in REQUIRED_TABLES:
        exists = run(f"SELECT to_regclass('public.{table}') IS NOT NULL")
        report.add(f"table {table}", bool(exists) and exists[0][0] in ("t", "true"), "present" if exists and exists[0][0] in ("t", "true") else "missing")
    try:
        guards = run("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                     "WHERE NOT t.tgisinternal AND c.relname IN ('command_outbox', 'command_state_history', 'devices')")
        report.add("delete and update guards present", int(guards[0][0]) >= 4, f"{guards[0][0]} non-internal triggers on command_outbox, command_state_history and devices")
        unknown = int(run("SELECT count(*) FROM command_state_history WHERE fact_type = 'DELIVERY_UNKNOWN'")[0][0])
        report.add("no unreviewed DELIVERY_UNKNOWN backlog", unknown <= max_delivery_unknown,
                   f"{unknown} command(s) with delivery unknown; at most {max_delivery_unknown} accepted as reviewed (pass --max-delivery-unknown after reviewing them)")
        overdue = int(run("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT') AND expires_at < now() - interval '1 minute'")[0][0])
        report.add("no overdue unresolved obligation", overdue == 0, f"{overdue} obligation(s) unresolved more than a minute past their window")
        stranded = int(run("SELECT count(*) FROM action_history a WHERE a.action_type = 'SEND_COMMAND' AND a.status = 'ACCEPTED_FOR_DISPATCH'"
                           " AND a.published_at IS NULL AND a.\"timestamp\" < now() - interval '5 minutes'"
                           " AND NOT EXISTS (SELECT 1 FROM command_outbox o WHERE o.command_id = a.request_id)"
                           " AND NOT EXISTS (SELECT 1 FROM command_state_history h WHERE h.command_id = a.request_id)")[0][0])
        report.info("legacy commands not yet reconciled", f"{stranded} older accepted command(s) without a delivery record (the reconciler marks them DELIVERY_UNKNOWN)")
    except Exception as e:
        report.add("database checks completed", False, f"a database check could not run: {e}")
    return report


def check_rollback(run, config: dict[str, str]) -> Report:
    report = Report("rollback")
    report.add("flag is false", config["nexus.command.outbox.enabled"].lower() == "false", f"nexus.command.outbox.enabled = {config['nexus.command.outbox.enabled']}")
    report.add("dispatcher still present to drain", config["nexus.command.outbox.dispatcher-enabled"].lower() == "true",
               "rollback is drain-only: the dispatcher must keep running until every obligation is resolved")
    try:
        unresolved = int(run("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')")[0][0])
        report.add("every obligation drained", unresolved == 0, f"{unresolved} obligation(s) still unresolved (they end within one delivery window)")
        rows = int(run("SELECT count(*) FROM command_outbox")[0][0])
        report.info("evidence kept", f"{rows} outbox row(s) remain: rollback never deletes delivery evidence")
    except Exception as e:
        report.add("rollback checks completed", False, f"a database check could not run: {e}")
    return report


# ------------------------------------------------------------------------------------------------ post-enable

def http_json(url: str, timeout: float = 10.0):
    with urllib.request.urlopen(urllib.request.Request(url, method="GET"), timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def check_post_enable(base_url: str, max_utilization: float, max_dead_letters: int, fetch=http_json) -> Report:
    report = Report("post-enable")
    base = base_url.rstrip("/")

    def metric(name: str) -> float | None:
        try:
            data = fetch(f"{base}/actuator/metrics/{name}")
            return float(data["measurements"][0]["value"])
        except Exception:
            return None

    unresolved = metric("nexus.command.queue.unresolved")
    utilization = metric("nexus.command.queue.utilization")
    report.add("queue metrics exposed", unresolved is not None and utilization is not None, f"unresolved={unresolved}, utilization={utilization}")
    if utilization is not None and utilization == utilization:         # not NaN
        report.add("queue utilization within the soak limit", utilization <= max_utilization, f"{utilization:.3f} (limit {max_utilization})")
    try:
        letters = fetch(f"{base}/api/v1/actions/dead-letters?limit=100")
        items = letters.get("items", [])
        report.add("dead letters within the soak limit", len(items) <= max_dead_letters, f"{len(items)} listed (limit {max_dead_letters})")
        uncertain = sum(1 for i in items if i.get("uncertain"))
        report.info("uncertain dead letters", f"{uncertain} of {len(items)} are UNCERTAIN_EXPIRED (they may have reached a device)")
    except Exception as e:
        report.add("lifecycle API reachable", False, f"GET /api/v1/actions/dead-letters failed: {e}")
    try:
        fetch(f"{base}/api/v1/actions/history?limit=1")
        report.add("existing action history still answers", True, "GET /api/v1/actions/history")
    except Exception as e:
        report.add("existing action history still answers", False, f"GET /api/v1/actions/history failed: {e}")
    return report


# ------------------------------------------------------------------------------------------------ cli

def parse_sets(values: list[str]) -> dict[str, str]:
    out: dict[str, str] = {}
    for value in values:
        key, separator, rest = value.partition("=")
        if not separator or key not in DEFAULTS:
            raise SystemExit(f"--set expects KEY=VALUE with a known key, got {value!r}")
        out[key] = rest
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read-only Phase 4 outbox enablement and rollback checks")
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("config")
    c.add_argument("--set", action="append", default=[])
    d = sub.add_parser("database")
    d.add_argument("--psql", required=True, help='command prefix that reaches the database, e.g. "docker exec -i NAME psql -U USER -d DB"')
    d.add_argument("--max-delivery-unknown", type=int, default=0)
    p = sub.add_parser("post-enable")
    p.add_argument("--base-url", required=True)
    p.add_argument("--max-utilization", type=float, default=0.8)
    p.add_argument("--max-dead-letters", type=int, default=0)
    r = sub.add_parser("rollback")
    r.add_argument("--psql", required=True)
    r.add_argument("--set", action="append", default=[])
    args = parser.parse_args(argv)

    if args.command == "config":
        report = check_config(effective_config(parse_sets(args.set)))
    elif args.command == "database":
        report = check_database(make_psql_runner(args.psql), args.max_delivery_unknown)
    elif args.command == "post-enable":
        report = check_post_enable(args.base_url, args.max_utilization, args.max_dead_letters)
    else:
        report = check_rollback(make_psql_runner(args.psql), effective_config(parse_sets(args.set)))
    print(report.render())
    return 0 if report.passed else 1


if __name__ == "__main__":
    sys.exit(main())
