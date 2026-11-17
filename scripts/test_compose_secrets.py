#!/usr/bin/env python3
"""Offline production secret initialization and distribution regressions."""

import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

import yaml

from release_assets import release_compose

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = (ROOT / 'docker-compose.prod.yml').read_text(encoding='utf-8')
SERVICES = yaml.safe_load(COMPOSE)['services']


class ComposeSecretsTest(unittest.TestCase):
    def test_initializer_is_pinned_consumers_wait_and_only_backend_can_write(self):
        for compose in [COMPOSE, release_compose(COMPOSE, 'v1.7.0')]:
            with self.subTest(asset=compose is not COMPOSE):
                config = yaml.safe_load(compose)
                init = config['services']['secrets-init']
                self.assertRegex(init['image'], r'^busybox:\d+\.\d+\.\d+$')
                self.assertEqual('0:0', init['user'])
                self.assertEqual('no', init['restart'])
                self.assertIn('secrets_prod', config['volumes'])
                self.assertEqual(['secrets_prod:/run/secrets'], init['volumes'])
                for name in ['db', 'backend']:
                    consumer = config['services'][name]
                    self.assertEqual('service_completed_successfully',
                                     consumer['depends_on']['secrets-init']['condition'])
                    self.assertIn('secrets_prod:/run/secrets' + (':ro' if name == 'db' else ''), consumer['volumes'])

    def test_database_and_backend_share_defaults_and_explicit_env(self):
        db = SERVICES['db']['environment']
        backend = SERVICES['backend']['environment']
        self.assertEqual('${DB_PASSWORD:-}', db['POSTGRES_PASSWORD'])
        self.assertEqual(db['POSTGRES_PASSWORD'], backend['DB_PASSWORD'])
        self.assertEqual('${DB_PASSWORD_FILE:-/run/secrets/db_password}', db['POSTGRES_PASSWORD_FILE'])
        self.assertEqual(db['POSTGRES_PASSWORD_FILE'], backend['DB_PASSWORD_FILE'])
        self.assertEqual('/run/secrets/instance_key', backend['INSTANCE_KEY_FILE'])
        self.assertEqual('/run/secrets', backend['SECRETS_DIR'])
        self.assertEqual('${ALLOWED_HOSTS:-}', backend['ALLOWED_HOSTS'])
        self.assertEqual('${OPENROUTER_API_KEY_FILE:-}', backend['OPENROUTER_API_KEY_FILE'])
        self.assertEqual('${OPENROUTER_API_KEY:-}', backend['OPENROUTER_API_KEY'])
        dockerfile = (ROOT / 'backend/Dockerfile').read_text()
        self.assertIn('useradd --uid 10001 --gid 10001', dockerfile)
        self.assertIn('USER app', dockerfile)

    def run_init(self, directory, check=True):
        # Execute the actual embedded initializer without Docker. Only chown is stubbed:
        # this unprivileged test cannot assign the container's numeric runtime owner.
        root = Path(directory)
        tools = root / 'tools'
        tools.mkdir(exist_ok=True)
        chown = tools / 'chown'
        chown.write_text('#!/bin/sh\n[ "$1" = "10001:10001" ]\n')
        chown.chmod(0o755)
        secrets = root / 'secrets'
        secrets.mkdir(exist_ok=True)
        script = SERVICES['secrets-init']['command'][0].replace('$$', '$')
        script = script.replace('/run/secrets', shlex.quote(str(secrets)))
        result = subprocess.run(['sh', '-ec', script], capture_output=True, text=True,
                                env={**os.environ, 'PATH': f'{tools}:{os.environ["PATH"]}'})
        if check:
            self.assertEqual(0, result.returncode, result.stderr)
        return secrets, result

    def test_first_start_creates_private_random_secrets_and_second_start_preserves_them(self):
        with tempfile.TemporaryDirectory() as directory:
            secrets, result = self.run_init(directory)
            before = {}
            for name in ['db_password', 'instance_key']:
                file = secrets / name
                self.assertRegex(file.read_text(), r'^[0-9a-f]{64}$')
                self.assertEqual(0o600, file.stat().st_mode & 0o777)
                before[name] = (file.read_bytes(), file.stat().st_mtime_ns, file.stat().st_ino)
                self.assertNotIn(file.read_text(), result.stdout + result.stderr)
            self.assertNotEqual(before['db_password'][0], before['instance_key'][0])
            self.run_init(directory)
            for name, expected in before.items():
                file = secrets / name
                self.assertEqual(expected, (file.read_bytes(), file.stat().st_mtime_ns, file.stat().st_ino))
            self.assertEqual(0o700, secrets.stat().st_mode & 0o777)
            self.assertEqual({'db_password', 'instance_key'}, {p.name for p in secrets.iterdir()})

    def test_existing_operator_secret_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            secrets = Path(directory) / 'secrets'
            secrets.mkdir()
            file = secrets / 'db_password'
            file.write_text('operator-managed-password')
            self.run_init(directory)
            self.assertEqual('operator-managed-password', file.read_text())

    def test_invalid_existing_files_fail_without_overwrite(self):
        for kind in ['empty', 'directory', 'symlink']:
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                secrets = Path(directory) / 'secrets'
                secrets.mkdir()
                target = secrets / 'db_password'
                if kind == 'empty':
                    target.touch()
                elif kind == 'directory':
                    target.mkdir()
                else:
                    target.symlink_to('missing-target')
                _, result = self.run_init(directory, check=False)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((secrets / 'instance_key').exists())
                if kind == 'empty':
                    self.assertEqual(b'', target.read_bytes())
                elif kind == 'symlink':
                    self.assertTrue(target.is_symlink())
                else:
                    self.assertTrue(target.is_dir())

    def test_database_wrapper_selects_explicit_env_or_file_and_preserves_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            entrypoint = Path(directory) / 'docker-entrypoint.sh'
            entrypoint.write_text('#!/bin/sh\n'
                                  'printf "%s\\n" "${POSTGRES_PASSWORD_FILE-unset}" "$@"\n')
            entrypoint.chmod(0o755)
            wrapper = [part.replace('$$', '$') for part in SERVICES['db']['entrypoint']]
            for explicit in ['', 'operator-password']:
                with self.subTest(explicit=bool(explicit)):
                    result = subprocess.run([*wrapper, 'postgres', '-c', 'max_connections=30'],
                                            capture_output=True, text=True, check=True,
                                            env={**os.environ, 'PATH': f'{directory}:{os.environ["PATH"]}',
                                                 'POSTGRES_PASSWORD': explicit,
                                                 'POSTGRES_PASSWORD_FILE': '/run/secrets/db_password'})
                    expected = 'unset' if explicit else '/run/secrets/db_password'
                    self.assertEqual([expected, 'postgres', '-c', 'max_connections=30'],
                                     result.stdout.splitlines())

    def test_precedence_and_upgrade_warning_are_documented(self):
        guide = (ROOT / 'docs/deployment.md').read_text()
        self.assertIn('explicit env value > `*_FILE` > generated default', guide)
        self.assertIn('Do not remove `DB_PASSWORD` from an existing `.env`', guide)
        self.assertIn('backend start with no key', guide)
        self.assertIn('UI-managed `openrouter_key` > none', guide)
        self.assertIn('secrets_prod', guide)
        env = (ROOT / '.env.example').read_text()
        self.assertIn('keep DB_PASSWORD in an existing .env', env)
        self.assertRegex(env, r'(?m)^# DB_PASSWORD=$')
        for document in ['README.md', 'docs/deployment.md']:
            self.assertIn('docker compose up -d --wait', (ROOT / document).read_text())

    def test_source_checkout_example_keeps_explicit_password_for_older_images(self):
        guide = (ROOT / 'docs/deployment.md').read_text()
        checkout = guide.split('### Run from a source checkout\n', 1)[1].split('\n## ', 1)[0]
        self.assertIn('IMAGE_TAG=v1.6.0', checkout)
        self.assertRegex(checkout, r'(?m)^DB_PASSWORD=\S+')
        self.assertIn('Generated secrets require **v1.7.0+ images**', checkout)
        self.assertIn('set `DB_PASSWORD` explicitly even for a new install', checkout)


if __name__ == '__main__':
    unittest.main()
