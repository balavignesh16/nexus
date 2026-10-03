#!/usr/bin/env python3
"""Stop only NEXUS processes whose ownership is recorded for this repository."""
from __future__ import annotations
import argparse
import os
import subprocess
import nexus_runtime as rt

def cli() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Safely stop repository-owned NEXUS services")
    p.add_argument("--list", action="store_true", help="show recorded state without changing it")
    p.add_argument("--infra", action="store_true", help="also stop infrastructure owned by this run")
    p.add_argument("--force", action="store_true", help="force stubborn owned processes")
    p.add_argument("--timeout", type=float, default=10)
    p.add_argument("--discover", action="store_true", help="discover repo-specific candidates; --force is required to stop")
    return p

def describe(state: dict | None) -> None:
    if not state:
        print("[NEXUS] no runtime manifest")
        return
    print(f"[NEXUS] orchestrator pid={state.get('orchestratorPid')} live={rt.live_orchestrator(state)}")
    for name, service in state.get("services", {}).items():
        pid = service.get("pid")
        live = rt.pid_alive(int(pid)) if pid else rt.port_open(int(service.get("port", 0) or 0))
        print(f"[NEXUS] {name}: pid={pid} live={live} owned={service.get('owned')} reused={service.get('reused')}")
    print(f"[NEXUS] infrastructure: {state.get('infrastructure', {})}")

def discover() -> list[tuple[int, str]]:
    if os.name != "nt":
        return []
    script = ("Get-CimInstance Win32_Process | Where-Object { "
              "$_.CommandLine -and ($_.CommandLine -match 'com[.]nexus[.]NexusApplication' -or "
              "($_.CommandLine -like '*D:\\nexus*' -and "
              "$_.CommandLine -match 'vite|grand_simulation|nexus_simulator')) } | "
              "ForEach-Object { Write-Output ($_.ProcessId.ToString() + '|' + $_.CommandLine) }")
    result = subprocess.run(["powershell", "-NoProfile", "-Command", script],
                            capture_output=True, text=True, check=False)
    found = []
    for line in result.stdout.splitlines():
        try:
            pid, command = line.split("|", 1)
            if int(pid) != os.getpid():
                found.append((int(pid), command))
        except ValueError:
            pass
    return found

def main() -> int:
    a = cli().parse_args()
    if a.timeout <= 0:
        print("[NEXUS] --timeout must be greater than zero")
        return 2
    state = rt.read_json(rt.MANIFEST)
    if a.list:
        describe(state)
        for pid, command in discover() if a.discover else []:
            print(f"[NEXUS] discovered candidate pid={pid}: {command}")
        return 0
    if a.discover:
        candidates = discover()
        for pid, command in candidates:
            print(f"[NEXUS] discovered candidate pid={pid}: {command}")
        if candidates and not a.force:
            print("[NEXUS] discovery is report-only unless --force is supplied")
            return 2
        for pid, _ in candidates:
            rt.terminate_tree(pid, [str(rt.ROOT)], a.timeout, True)
    if state:
        if state.get("repositoryRoot") != str(rt.ROOT) or state.get("schemaVersion") != rt.SCHEMA_VERSION:
            print("[NEXUS] refusing an unknown or foreign runtime manifest")
            return 1
        for name in ("simulator", "frontend", "backend"):
            service = state.get("services", {}).get(name, {})
            pid = service.get("pid")
            if service.get("owned") is True and service.get("reused") is False and pid:
                stopped = rt.terminate_tree(int(pid), service.get("commandMarkers", [name]), a.timeout, a.force)
                print(f"[NEXUS] {name}: {'stopped' if stopped else 'not stopped; identity mismatch or timeout'}")
        orchestrator = int(state.get("orchestratorPid", 0) or 0)
        if rt.plausible_process(orchestrator, ["start_nexus.py"]):
            rt.terminate_tree(orchestrator, ["start_nexus.py", str(rt.ROOT)], a.timeout, a.force)
        if a.infra:
            result = rt.run_capture(["docker", "compose", "-f",
                                     str(rt.ROOT / "infrastructure/docker-compose.yml"), "down"], timeout=60)
            print(f"[NEXUS] infrastructure: {'stopped' if result.returncode == 0 else 'stop failed'}")
        rt.MANIFEST.unlink(missing_ok=True)
        rt.LOCK.unlink(missing_ok=True)
    elif a.infra:
        result = rt.run_capture(["docker", "compose", "-f",
                                 str(rt.ROOT / "infrastructure/docker-compose.yml"), "down"], timeout=60)
        print(f"[NEXUS] infrastructure: {'stopped' if result.returncode == 0 else 'stop failed'}")
    else:
        print("[NEXUS] no owned NEXUS services recorded; nothing changed")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
