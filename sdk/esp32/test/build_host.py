"""Builds the ESP32 library's host tests and sample drivers with plain g++ (C++11) and runs them.

    python test/build_host.py [--out DIR]       build, run the unit tests and both samples; exit status 0 only when all pass

Used by the repository's automated tests (sdk/python/tests/test_esp32_library.py). The compiler is taken from the CXX environment
variable or found on PATH. Warnings are errors. Nothing here needs PlatformIO or an ESP32 toolchain, and nothing here runs on hardware.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

LIBRARY = Path(__file__).resolve().parents[1]
REPO = LIBRARY.parents[1]
SOURCES = sorted(str(p) for p in (LIBRARY / "src" / "nexus").glob("*.cpp"))
FLAGS = ["-std=gnu++11", "-Wall", "-Wextra", "-Werror", "-pedantic"]
SAMPLES = (("TemperatureNode", "temperature"), ("LedCommandNode", "led"))


def compiler() -> str | None:
    return os.environ.get("CXX") or shutil.which("g++") or shutil.which("c++")


def exe(name: str, out: Path) -> Path:
    return out / (name + (".exe" if os.name == "nt" else ""))


def build_unit_tests(out: Path) -> Path:
    target = exe("test_host", out)
    subprocess.run([compiler(), *FLAGS, f"-I{LIBRARY / 'src'}", *SOURCES, str(LIBRARY / "test" / "host" / "test_main.cpp"), "-o", str(target)], check=True)
    return target


def build_sample(name: str, out: Path) -> Path:
    target = exe(name, out)
    ino = LIBRARY / "examples" / name / f"{name}.ino"
    subprocess.run([compiler(), *FLAGS, f"-I{LIBRARY / 'src'}", f"-I{LIBRARY / 'test' / 'stubs'}", "-x", "c++", str(ino),
                    str(LIBRARY / "test" / "host" / "sample_driver.cpp"), *SOURCES, "-o", str(target)], check=True)
    return target


def main() -> int:
    if compiler() is None:
        print("no C++ compiler found (set CXX or put g++ on PATH)", file=sys.stderr)
        return 2
    out = Path(sys.argv[sys.argv.index("--out") + 1]) if "--out" in sys.argv else Path(tempfile.mkdtemp(prefix="nexus-esp32-"))
    out.mkdir(parents=True, exist_ok=True)
    status = subprocess.run([str(build_unit_tests(out)), str(REPO)]).returncode
    for name, mode in SAMPLES:
        status |= subprocess.run([str(build_sample(name, out)), mode], stderr=subprocess.DEVNULL).returncode
    return 1 if status else 0


if __name__ == "__main__":
    raise SystemExit(main())
