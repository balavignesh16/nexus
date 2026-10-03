#!/usr/bin/env python3
"""Compatibility entry point; use stop_nexus.py for new workflows."""
from stop_nexus import main

if __name__ == "__main__":
    raise SystemExit(main())
