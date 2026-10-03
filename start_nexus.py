#!/usr/bin/env python3
"""Start and supervise the local NEXUS development stack."""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import nexus_runtime as rt

ROOT, COMPOSE = rt.ROOT, rt.ROOT / "infrastructure" / "docker-compose.yml"
children: dict[str, subprocess.Popen] = {}
streams: list[object] = []
state: dict = {}


def cli() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Start and supervise NEXUS safely")
    for flag, help_text in (
        ("infra", "do not start PostgreSQL/MQTT"), ("backend", "do not start the backend"),
        ("frontend", "do not start the frontend"), ("simulator", "do not start the simulator"),
    ):
        p.add_argument(f"--no-{flag}", action="store_true", help=help_text)
    p.add_argument("--duration", type=float, help="stop owned processes after SECONDS")
    p.add_argument("--reuse", action="store_true", help="reuse independently running identity-verified services")
    p.add_argument("--clean", action="store_true", help="remove stale runtime metadata/logs only")
    p.add_argument("--infra-down-on-exit", action="store_true")
    p.add_argument("--startup-timeout", type=float, default=90, metavar="SECONDS")
    p.add_argument("--verbose", action="store_true")
    return p


def log(message: str) -> None:
    print(f"[NEXUS] {message}", flush=True)


def compose(*arguments: str, timeout: float = 60) -> subprocess.CompletedProcess[str]:
    return rt.run_capture(["docker", "compose", "-f", str(COMPOSE), *arguments], timeout=timeout)


def compose_running(service: str) -> bool:
    container = "nexus-postgres" if service == "postgres" else "nexus-mosquitto"
    result = rt.run_capture(["docker", "inspect", "-f", "{{.State.Running}}", container])
    return result.returncode == 0 and result.stdout.strip().lower() == "true"


def preflight(a: argparse.Namespace) -> dict[str, bool]:
    if a.duration is not None and a.duration < 0:
        raise RuntimeError("--duration must be zero or greater")
    if a.startup_timeout <= 0:
        raise RuntimeError("--startup-timeout must be greater than zero")
    if sys.version_info < (3, 11):
        raise RuntimeError("Python 3.11 or newer is required")
    for command, label in (("java", "Java"),):
        if not shutil.which(command):
            raise RuntimeError(f"{label} is required")
    java = rt.version_major((rt.run_capture(["java", "-version"]).stderr))
    if java is None or java < 21:
        raise RuntimeError(f"Java 21 or newer is required; detected {java or 'unknown'}")
    if java > 21:
        log("NEXUS targets Java 21 LTS; newer JDK detected.")
    wrapper = ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    if not wrapper.exists():
        raise RuntimeError("Maven wrapper is missing")
    if not a.no_frontend:
        if not shutil.which("node") or not shutil.which("npm"):
            raise RuntimeError("Node and npm are required")
        node = rt.version_major(rt.run_capture(["node", "--version"]).stdout)
        if node is None or node < 22:
            raise RuntimeError(f"Node 22 or newer is required; detected {node or 'unknown'}")
        if not (ROOT / "frontend/node_modules/.package-lock.json").exists():
            raise RuntimeError("frontend dependencies are missing; run npm ci in frontend")
    if not a.no_infra:
        if not shutil.which("docker") or rt.run_capture(["docker", "compose", "version"]).returncode:
            raise RuntimeError("Docker and the modern 'docker compose' plugin are required")
    if not a.no_simulator:
        simpy = ROOT / "simulator/.venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
        if not simpy.exists():
            raise RuntimeError(f"simulator environment missing: {simpy}")
    selected = {"postgres": not a.no_infra, "mqtt": not a.no_infra,
                "backend": not a.no_backend, "frontend": not a.no_frontend}
    reused: dict[str, bool] = {}
    for service, needed in selected.items():
        if not needed or not rt.port_open(rt.PORTS[service]):
            continue
        identity = ((service == "backend" and rt.nexus_backend())
                    or (service == "frontend" and rt.nexus_frontend())
                    or (service in {"postgres", "mqtt"} and compose_running(service)))
        if not a.reuse:
            raise RuntimeError(f"port {rt.PORTS[service]} for {service} is occupied; stop it or use --reuse")
        if not identity:
            raise RuntimeError(f"--reuse rejected port {rt.PORTS[service]}: not an identity-verified NEXUS {service}")
        reused[service] = True
    return reused


def start_child(name: str, command: list[str], cwd: Path) -> subprocess.Popen:
    rt.LOG_DIR.mkdir(parents=True, exist_ok=True)
    stream = (rt.LOG_DIR / f"{name}.log").open("wb")
    streams.append(stream)
    flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    if os.name == "nt" and command[0].lower().endswith((".cmd", ".bat")):
        command = ["cmd.exe", "/d", "/c", *command]
    process = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, creationflags=flags)
    children[name] = process
    state["services"][name] = {"pid": process.pid, "port": rt.PORTS.get(name),
                               "owned": True, "reused": False,
                               "commandMarkers": [name, str(cwd)]}
    rt.atomic_json(rt.MANIFEST, state)
    return process


def log_has(name: str, marker: str) -> bool:
    try:
        return marker in (rt.LOG_DIR / f"{name}.log").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return False


def cleanup(a: argparse.Namespace) -> None:
    for name in ("simulator", "frontend", "backend"):
        process = children.get(name)
        entry = state.get("services", {}).get(name, {})
        owned_pid = entry.get("pid")
        if owned_pid:
            rt.terminate_tree(int(owned_pid), entry.get("commandMarkers", [name]), 8)
    if state.get("infrastructure", {}).get("startedByThisRun") and a.infra_down_on_exit:
        compose("down")
    for stream in streams:
        stream.close()
    rt.MANIFEST.unlink(missing_ok=True)
    rt.release_lock()


def main() -> int:
    global state
    a = cli().parse_args()  # argparse handles --help before any state change
    try:
        reused = preflight(a)
        if a.clean and not rt.live_orchestrator():
            rt.MANIFEST.unlink(missing_ok=True)
            rt.LOCK.unlink(missing_ok=True)
            for path in rt.LOG_DIR.glob("*.log") if rt.LOG_DIR.exists() else []:
                path.unlink(missing_ok=True)
        rt.acquire_lock()
        state = {"schemaVersion": rt.SCHEMA_VERSION, "orchestratorPid": os.getpid(),
                 "startedAt": rt.utc_now(), "repositoryRoot": str(ROOT), "services": {},
                 "infrastructure": {"startedByThisRun": False, "composeFile": str(COMPOSE)}}
        for name in ("backend", "frontend"):
            if reused.get(name):
                state["services"][name] = {"pid": None, "port": rt.PORTS[name], "owned": False, "reused": True}
        rt.atomic_json(rt.MANIFEST, state)
        if not a.no_infra and not (reused.get("postgres") and reused.get("mqtt")):
            result = compose("up", "-d", timeout=a.startup_timeout)
            if result.returncode:
                raise RuntimeError(f"infrastructure startup failed: {result.stderr[-1200:]}")
            state["infrastructure"]["startedByThisRun"] = True
            rt.atomic_json(rt.MANIFEST, state)
        if not a.no_infra:
            rt.wait_until("PostgreSQL", lambda: compose_running("postgres") and rt.port_open(5432), a.startup_timeout)
            rt.wait_until("Mosquitto TCP readiness", lambda: compose_running("mqtt") and rt.port_open(1883), a.startup_timeout)
        if not a.no_backend and not reused.get("backend"):
            wrapper = ROOT / "backend" / ("mvnw.cmd" if os.name == "nt" else "mvnw")
            backend = start_child("backend", [str(wrapper), "spring-boot:run"], ROOT / "backend")
            rt.wait_until("NEXUS backend", rt.nexus_backend, a.startup_timeout, backend)
            state["services"]["backend"]["pid"] = rt.port_pid(8080) or backend.pid
            state["services"]["backend"]["commandMarkers"] = ["NexusApplication", str(ROOT)]
            rt.atomic_json(rt.MANIFEST, state)
        if not a.no_frontend and not reused.get("frontend"):
            frontend = start_child("frontend", ["npm.cmd" if os.name == "nt" else "npm",
                                   "run", "dev", "--", "--strictPort"], ROOT / "frontend")
            rt.wait_until("NEXUS frontend", rt.nexus_frontend, a.startup_timeout, frontend)
            state["services"]["frontend"]["pid"] = rt.port_pid(5173) or frontend.pid
            state["services"]["frontend"]["commandMarkers"] = ["vite", str(ROOT)]
            rt.atomic_json(rt.MANIFEST, state)
        if not a.no_simulator:
            simpy = ROOT / "simulator/.venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
            simulator = start_child("simulator", [str(simpy), "-u", str(ROOT / "grand_simulation.py")], ROOT)
            rt.wait_until("simulator provisioning",
                          lambda: log_has("simulator", "All 3 ESP32 devices connected"),
                          a.startup_timeout, simulator)
        log("NEXUS is ready; ownership is recorded in .nexus/runtime.json")
        deadline = time.monotonic() + a.duration if a.duration is not None else None
        while deadline is None or time.monotonic() < deadline:
            for name, process in children.items():
                if process.poll() is not None:
                    raise RuntimeError(f"owned {name} exited unexpectedly (exit {process.returncode})")
            time.sleep(0.25)
        return 0
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        log(f"ERROR: {error}")
        return 1
    except KeyboardInterrupt:
        log("interrupted")
        return 130
    finally:
        if state:
            cleanup(a)


if __name__ == "__main__":
    raise SystemExit(main())
