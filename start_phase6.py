#!/usr/bin/env python3
"""Local Phase 6 entry point; refuse to reuse an old worktree's application."""
from __future__ import annotations

import os
import argparse
import json
import re
import subprocess
import sys
from urllib.request import build_opener, HTTPRedirectHandler, Request
from pathlib import Path

import nexus_runtime as rt

ROOT = Path(__file__).resolve().parent


def serves_this_worktree(line: str, root: Path = ROOT) -> bool:
    def normalized(text: str) -> str:
        return re.sub('/+', '/', text.replace('\\', '/')).lower()

    expected = normalized(str(root)) + '/'
    if expected in normalized(line):
        return True
    # Spring Boot puts its classpath in an @argfile on Windows; the process line alone omits cwd.
    for match in re.finditer(r'@(?:"([^"]+)"|(\S+))', line):
        path = Path(match.group(1) or match.group(2))
        if path.name.startswith('spring-boot-') and path.suffix == '.argfile':
            try:
                if expected in normalized(path.read_text(encoding='utf-8')):
                    return True
            except OSError:
                pass
    return False


def options(argv: list[str] | None = None):
    parser = argparse.ArgumentParser(description='NEXUS Phase 6: deterministic by default; explicit local model opt-in')
    parser.add_argument('--ollama', action='store_true')
    parser.add_argument('--model', default='llama3.2:3b')
    parser.add_argument('--allow-local-evidence', action='store_true')
    args, forwarded = parser.parse_known_args(argv)
    # Validate supervisor options even when an existing app might be reused.
    from start_nexus import cli
    cli().parse_args(forwarded)
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}', args.model) or 'cloud' in args.model.lower():
        parser.error('Choose a locally installed non-cloud model name.')
    if args.ollama and not args.allow_local_evidence:
        parser.error('--ollama requires --allow-local-evidence: selected evidence stays on this machine.')
    if not args.ollama and (args.allow_local_evidence or args.model != 'llama3.2:3b'):
        parser.error('--model and --allow-local-evidence require --ollama.')
    if args.ollama and os.environ.get('NEXUS_AI_ENABLED', '').lower() == 'true':
        parser.error('Hosted and Ollama providers cannot both be enabled. Clear NEXUS_AI_ENABLED.')
    return args, forwarded


def local_model_preflight(model: str) -> None:
    # Metadata only: no inference and no automatic pull.
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise ValueError('Ollama metadata redirects are not permitted.')
    opener = build_opener(NoRedirect())
    with opener.open('http://127.0.0.1:11434/api/tags', timeout=3) as response:
        tags = json.loads(response.read(1_000_001))
    entry = next((m for m in tags.get('models', []) if model in (m.get('name'), m.get('model'))), None)
    if entry is None:
        raise ValueError(f'{model} is not installed. No download was attempted.')
    request = Request('http://127.0.0.1:11434/api/show', data=json.dumps({'model': model}).encode(),
                      headers={'Content-Type': 'application/json'})
    with opener.open(request, timeout=3) as response:
        shown = json.loads(response.read(1_000_001))
    if any(m.get('remote_host') or m.get('remote_model') for m in (entry, shown)):
        raise ValueError('Remote/cloud models are not permitted.')


def mode_matches(status: dict, args) -> bool:
    gateway = status.get('gateway', {})
    if not args.ollama:
        return status.get('mode') == 'DETERMINISTIC_ONLY' and gateway.get('evidenceConsent') is False
    return (status.get('mode') == 'OLLAMA_LOCAL' and gateway.get('evidenceConsent') is True
            and gateway.get('identity', {}).get('modelId') == args.model)


def main(argv: list[str] | None = None) -> int:
    args, forwarded = options(argv)
    for name in ('backend', 'frontend'):
        if rt.port_open(rt.PORTS[name]):
            pid = rt.port_pid(rt.PORTS[name])
            if not pid or not serves_this_worktree(rt.command_line(pid)):
                print(f'REFUSED: {name} port {rt.PORTS[name]} belongs to another or unverified worktree. '
                      'Stop that application yourself; no process was terminated.', file=sys.stderr)
                return 1
    if rt.live_orchestrator() and rt.nexus_backend() and rt.nexus_frontend():
        if not rt.http_text('http://localhost:8080/api/v1/assistant/evidence/lookups'):
            print('Assistant is disabled in the existing process. Stop this worktree with stop_nexus.py and restart start.bat.', file=sys.stderr)
            return 1
        try:
            status = json.loads(rt.http_text('http://localhost:8080/api/v1/assistant/model-status'))
        except (ValueError, TypeError):
            status = {}
        if not mode_matches(status, args):
            print('REFUSED: existing backend mode differs or cannot be verified. Run this worktree\'s '
                  'python stop_nexus.py, then restart with the desired mode. No process was terminated.', file=sys.stderr)
            return 1
        print('NEXUS Phase 6 is already running in the requested mode: http://localhost:5173/assistant')
        return 0
    if rt.port_open(rt.PORTS['backend']):
        print('REFUSED: an existing backend must be stopped before changing or verifying model mode.', file=sys.stderr)
        return 1
    if args.ollama:
        try:
            local_model_preflight(args.model)
        except Exception as error:
            print(f'REFUSED: local Ollama preflight failed ({error}). No model was downloaded.', file=sys.stderr)
            return 1
    env = dict(os.environ, SPRING_PROFILES_ACTIVE='phase6-local', SERVER_ADDRESS='127.0.0.1',
               NEXUS_AI_ENABLED='false', NEXUS_AI_OLLAMA_ENABLED=str(args.ollama).lower(),
               NEXUS_AI_OLLAMA_MODEL=args.model, NEXUS_AI_OLLAMA_BASE_URL='http://127.0.0.1:11434',
               NEXUS_AI_SEND_DEVICE_EVIDENCE=str(args.ollama and args.allow_local_evidence).lower())
    print(f'Mode: {"OLLAMA_LOCAL / " + args.model if args.ollama else "DETERMINISTIC_ONLY"}', flush=True)
    return subprocess.call([sys.executable, str(ROOT / 'start_nexus.py'), '--no-simulator', '--reuse', *forwarded], cwd=ROOT, env=env)


if __name__ == '__main__':
    raise SystemExit(main())
