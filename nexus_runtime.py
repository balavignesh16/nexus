"""Shared, conservative runtime primitives for NEXUS development tooling."""
from __future__ import annotations

import json
import os
import socket
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parent
STATE_DIR = ROOT / ".nexus"
LOG_DIR = STATE_DIR / "logs"
VALIDATION_DIR = STATE_DIR / "validation"
MANIFEST = STATE_DIR / "runtime.json"
LOCK = STATE_DIR / "runtime.lock"
SCHEMA_VERSION = 1
PORTS = {"backend": 8080, "frontend": 5173, "mqtt": 1883, "postgres": 5432}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def atomic_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name, suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_json(path: Path) -> dict[str, Any] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else None
    except (OSError, json.JSONDecodeError):
        return None


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if os.name == "nt":
        result = subprocess.run(
            ["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"],
            capture_output=True, text=True, check=False,
        )
        return result.returncode == 0 and str(pid) in result.stdout
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def command_line(pid: int) -> str:
    if os.name == "nt":
        escaped = str(pid)
        script = f"(Get-CimInstance Win32_Process -Filter 'ProcessId={escaped}').CommandLine"
        result = subprocess.run(
            ["powershell", "-NoProfile", "-Command", script],
            capture_output=True, text=True, check=False,
        )
        return result.stdout.strip()
    result = subprocess.run(["ps", "-p", str(pid), "-o", "args="], capture_output=True, text=True, check=False)
    return result.stdout.strip()


def plausible_process(pid: int, markers: list[str]) -> bool:
    if not pid_alive(pid):
        return False
    line = command_line(pid).lower()
    return bool(line) and any(marker.lower() in line for marker in markers)


def live_orchestrator(manifest: dict[str, Any] | None = None) -> bool:
    manifest = manifest or read_json(MANIFEST)
    if not manifest or Path(str(manifest.get("repositoryRoot", ""))).resolve() != ROOT:
        return False
    pid = int(manifest.get("orchestratorPid", 0) or 0)
    return plausible_process(pid, ["start_nexus.py"])


def acquire_lock() -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    existing = read_json(MANIFEST)
    if live_orchestrator(existing):
        raise RuntimeError(f"NEXUS orchestrator PID {existing['orchestratorPid']} is already active")
    if LOCK.exists():
        stale = read_json(LOCK)
        stale_pid = int((stale or {}).get("pid", 0) or 0)
        if plausible_process(stale_pid, ["start_nexus.py"]):
            raise RuntimeError(f"NEXUS orchestrator PID {stale_pid} owns the repository lock")
        LOCK.unlink(missing_ok=True)
    fd = os.open(LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump({"schemaVersion": SCHEMA_VERSION, "pid": os.getpid(), "repositoryRoot": str(ROOT)}, stream)


def release_lock() -> None:
    lock = read_json(LOCK)
    if lock and int(lock.get("pid", 0) or 0) == os.getpid():
        LOCK.unlink(missing_ok=True)


def port_open(port: int, host: str = "127.0.0.1") -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.5):
            return True
    except OSError:
        return False


def port_pid(port: int) -> int | None:
    if os.name != "nt":
        return None
    script = (f"(Get-NetTCPConnection -LocalPort {port} -State Listen "
              "-ErrorAction SilentlyContinue | Select-Object -First 1 "
              "-ExpandProperty OwningProcess)")
    result = subprocess.run(["powershell", "-NoProfile", "-Command", script],
                            capture_output=True, text=True, check=False)
    try:
        return int(result.stdout.strip())
    except ValueError:
        return None


def http_text(url: str, timeout: float = 1.0) -> str | None:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.read(65536).decode("utf-8", errors="replace")
    except (OSError, urllib.error.URLError):
        return None


def nexus_backend() -> bool:
    body = http_text("http://127.0.0.1:8080/api/v1/platform-health")
    return bool(body and '"database"' in body and '"mqtt"' in body)


def nexus_frontend() -> bool:
    body = http_text("http://localhost:5173/")
    return bool(body and "NEXUS" in body.upper() and "<html" in body.lower())


def wait_until(name: str, ready: Callable[[], bool], timeout: float, process: subprocess.Popen[Any] | None = None) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"{name} exited before becoming ready (exit {process.returncode})")
        if ready():
            return
        time.sleep(0.25)
    raise RuntimeError(f"{name} did not become ready within {timeout:g}s")


def terminate_tree(pid: int, markers: list[str], timeout: float, force: bool = False) -> bool:
    if not plausible_process(pid, markers):
        return not pid_alive(pid)
    if os.name == "nt":
        # taskkill /T must see the still-live wrapper to enumerate descendants.
        # Windows has no reliable cross-process graceful tree signal; explicit
        # ownership validation above is the safety boundary.
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                       capture_output=True, check=False)
    else:
        try:
            os.kill(pid, 15)
        except OSError:
            return True
    deadline = time.monotonic() + timeout
    while pid_alive(pid) and time.monotonic() < deadline:
        time.sleep(0.2)
    if pid_alive(pid) and not force:
        return terminate_tree(pid, markers, timeout, True)
    return not pid_alive(pid)


def run_capture(command: list[str], cwd: Path = ROOT, timeout: float = 20) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=timeout, check=False)


def version_major(text: str) -> int | None:
    import re
    match = re.search(r"(?:version\s+\"?|v)(\d+)|^(\d+)", text.strip(), re.IGNORECASE)
    return int(next(group for group in match.groups() if group)) if match else None


def python_executable() -> str:
    return sys.executable
