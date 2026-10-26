import tempfile
import io
import json
import unittest
from pathlib import Path
from unittest.mock import patch

import start_phase6 as start


class Phase6Startup(unittest.TestCase):
    def test_another_worktree_is_not_the_candidate(self):
        self.assertTrue(start.serves_this_worktree('node D:\\nexus-p6\\frontend\\vite.js', Path('D:/nexus-p6')))
        self.assertFalse(start.serves_this_worktree('node D:\\nexus\\frontend\\vite.js', Path('D:/nexus-p6')))
        self.assertFalse(start.serves_this_worktree('node D:\\nexus-p6-old\\frontend\\vite.js', Path('D:/nexus-p6')))

    def test_windows_spring_argfile_reveals_the_actual_classpath(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'spring-boot-example.argfile'
            path.write_text('"D:\\\\nexus-p6\\\\backend\\\\target\\\\classes"', encoding='utf-8')
            self.assertTrue(start.serves_this_worktree(f'java @"{path}" NexusApplication', Path('D:/nexus-p6')))
            self.assertFalse(start.serves_this_worktree(f'java @"{path}" NexusApplication', Path('D:/nexus')))

    def test_a_foreign_listener_is_refused_without_starting_or_stopping_anything(self):
        foreign = start.ROOT.parent / (start.ROOT.name + '-foreign') / 'frontend/vite.js'
        with patch.object(start.rt, 'port_open', return_value=True), patch.object(start.rt, 'port_pid', return_value=7), \
                patch.object(start.rt, 'command_line', return_value=f'node {foreign}'), \
                patch.object(start.subprocess, 'call') as launch:
            self.assertEqual(1, start.main([]))
            launch.assert_not_called()

    def test_default_and_explicit_local_options(self):
        args, _ = start.options([])
        self.assertFalse(args.ollama)
        with patch.dict(start.os.environ, {'NEXUS_AI_ENABLED': 'false'}):
            local, _ = start.options(['--ollama', '--allow-local-evidence'])
        self.assertTrue(local.ollama)
        self.assertFalse(start.mode_matches({'mode': 'DETERMINISTIC_ONLY'}, local))
        self.assertTrue(start.mode_matches({'mode': 'OLLAMA_LOCAL', 'gateway': {
            'evidenceConsent': True, 'identity': {'modelId': 'llama3.2:3b'}}}, local))

    def test_invalid_or_unconsented_options_fail(self):
        for flags in (['--ollama'], ['--allow-local-evidence'], ['--unknown'],
                      ['--ollama', '--allow-local-evidence', '--model', 'qwen3.5:cloud']):
            with self.subTest(flags=flags), self.assertRaises(SystemExit):
                start.options(flags)

    def test_default_launch_forces_both_providers_and_consent_off(self):
        with patch.object(start.rt, 'port_open', return_value=False), \
                patch.object(start.rt, 'live_orchestrator', return_value=False), \
                patch.object(start.subprocess, 'call', return_value=0) as launch:
            self.assertEqual(0, start.main([]))
            env = launch.call_args.kwargs['env']
            for name in ('NEXUS_AI_ENABLED', 'NEXUS_AI_OLLAMA_ENABLED', 'NEXUS_AI_SEND_DEVICE_EVIDENCE'):
                self.assertEqual('false', env[name])

    def test_explicit_local_launch_and_wrong_mode_reuse(self):
        flags = ['--ollama', '--model', 'llama3.2:3b', '--allow-local-evidence']
        with patch.dict(start.os.environ, {'NEXUS_AI_ENABLED': 'false'}), \
                patch.object(start.rt, 'port_open', return_value=False), \
                patch.object(start.rt, 'live_orchestrator', return_value=False), \
                patch.object(start, 'local_model_preflight') as probe, \
                patch.object(start.subprocess, 'call', return_value=0) as launch:
            self.assertEqual(0, start.main(flags))
            probe.assert_called_once_with('llama3.2:3b')
            env = launch.call_args.kwargs['env']
            self.assertEqual('false', env['NEXUS_AI_ENABLED'])
            self.assertEqual('true', env['NEXUS_AI_OLLAMA_ENABLED'])
            self.assertEqual('true', env['NEXUS_AI_SEND_DEVICE_EVIDENCE'])
        with patch.dict(start.os.environ, {'NEXUS_AI_ENABLED': 'false'}), \
                patch.object(start.rt, 'port_open', return_value=False), \
                patch.object(start.rt, 'live_orchestrator', return_value=True), \
                patch.object(start.rt, 'nexus_backend', return_value=True), \
                patch.object(start.rt, 'nexus_frontend', return_value=True), \
                patch.object(start.rt, 'http_text', side_effect=['["lookup"]', '{"mode":"DETERMINISTIC_ONLY"}']), \
                patch.object(start.subprocess, 'call') as launch:
            self.assertEqual(1, start.main(flags))
            launch.assert_not_called()

    def test_conflicting_hosted_provider_is_rejected(self):
        with patch.dict(start.os.environ, {'NEXUS_AI_ENABLED': 'true'}), self.assertRaises(SystemExit):
            start.options(['--ollama', '--allow-local-evidence'])

    def test_local_preflight_requires_installed_nonremote_metadata(self):
        def body(value):
            return io.BytesIO(json.dumps(value).encode())
        with patch.object(start, 'build_opener') as factory:
            factory.return_value.open.side_effect = [body({'models': []})]
            with self.assertRaisesRegex(ValueError, 'not installed'):
                start.local_model_preflight('llama3.2:3b')
        with patch.object(start, 'build_opener') as factory:
            factory.return_value.open.side_effect = [body({'models': [{'name': 'local-name'}]}),
                                                       body({'remote_host': 'https://ollama.com'})]
            with self.assertRaisesRegex(ValueError, 'Remote/cloud'):
                start.local_model_preflight('local-name')
            handler = factory.call_args.args[0]
            with self.assertRaisesRegex(ValueError, 'redirects'):
                handler.redirect_request(None, None, 302, 'redirect', {}, 'https://example.com')
        with patch.object(start, 'build_opener') as factory:
            factory.return_value.open.side_effect = [body({'models': [{'name': 'llama3.2:3b'}]}),
                                                       body({'capabilities': ['completion']})]
            start.local_model_preflight('llama3.2:3b')
            self.assertEqual(2, factory.return_value.open.call_count)


if __name__ == '__main__':
    unittest.main()
