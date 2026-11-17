#!/usr/bin/env python3
"""Offline setup asset regressions; install scripts/requirements-test.txt first."""

import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

import yaml

from release_assets import release_compose


ROOT = Path(__file__).resolve().parents[1]
COMPOSE = (ROOT / 'docker-compose.prod.yml').read_text(encoding='utf-8')
ENV_EXAMPLE = (ROOT / '.env.example').read_bytes()
WORKFLOW = yaml.safe_load((ROOT / '.github/workflows/release.yml').read_text(encoding='utf-8'))
STEPS = WORKFLOW['jobs']['publish-release']['steps']


class ReleaseAssetsTest(unittest.TestCase):
    def test_both_images_default_to_the_exact_release_tag(self):
        for tag in ['v1.5.1', 'v12.34.56']:
            with self.subTest(tag=tag):
                services = yaml.safe_load(release_compose(COMPOSE, tag))['services']
                for component in ['backend', 'frontend']:
                    self.assertEqual('ghcr.io/${IMAGE_NAMESPACE:-fjrepo}/chess-' + component
                                     + ':${IMAGE_TAG:-' + tag + '}', services[component]['image'])

    def test_only_the_two_tag_expressions_change(self):
        generated = release_compose(COMPOSE, 'v1.5.1')
        required = '${IMAGE_TAG:?set IMAGE_TAG to a release version such as v1.1.0}'
        self.assertEqual(2, generated.count('${IMAGE_TAG:-v1.5.1}'))
        self.assertEqual(COMPOSE, generated.replace('${IMAGE_TAG:-v1.5.1}', required))

    def test_generated_file_is_yaml_with_the_same_services_and_volume(self):
        original = yaml.safe_load(COMPOSE)
        generated = yaml.safe_load(release_compose(COMPOSE, 'v1.5.1'))
        for component in ['backend', 'frontend']:
            generated['services'][component]['image'] = original['services'][component]['image']
        self.assertEqual(original, generated)
        self.assertNotIn('ports', generated['services']['db'])
        self.assertNotIn('ports', generated['services']['backend'])
        self.assertEqual(['127.0.0.1:${FRONTEND_PORT:-4200}:8080'],
                         generated['services']['frontend']['ports'])

    def test_only_credentials_are_required_in_release_asset(self):
        required = set(re.findall(r'\$\{(\w+):\?', release_compose(COMPOSE, 'v1.5.1')))
        self.assertEqual({'OPENROUTER_API_KEY', 'DB_PASSWORD'}, required)
        self.assertEqual(required | {'IMAGE_TAG'}, set(re.findall(r'\$\{(\w+):\?', COMPOSE)))

    def test_env_template_covers_every_production_variable_without_credentials(self):
        values = dict(re.findall(r'^(\w+)=(.*)$', ENV_EXAMPLE.decode('utf-8'), re.MULTILINE))
        self.assertEqual(set(re.findall(r'\$\{(\w+)', COMPOSE)), set(values))
        for variable in ['OPENROUTER_API_KEY', 'DB_PASSWORD', 'IMAGE_TAG']:
            self.assertEqual('', values[variable])
        self.assertEqual('fjrepo', values['IMAGE_NAMESPACE'])

    def test_invalid_tags_are_rejected(self):
        for tag in ['', 'latest', '1.5.1', 'v1.5', 'v1.5.1-rc1', 'v1.5.1\n', '${OTHER}',
                    'v1.5.1; echo unexpected']:
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, 'vX.Y.Z'):
                release_compose(COMPOSE, tag)

    def test_missing_or_unexpected_tag_expressions_are_rejected(self):
        for compose in ['', 'image: ${IMAGE_TAG:?required}', COMPOSE + '${IMAGE_TAG:?extra}',
                        COMPOSE.replace('${IMAGE_TAG:?', '${IMAGE_TAG:-'),
                        COMPOSE.replace('${IMAGE_TAG:?', '${IMAGE_TAG}')]:
            with self.subTest(compose=compose), self.assertRaisesRegex(ValueError, 'exactly two'):
                release_compose(compose, 'v1.5.1')

    def test_preserves_crlf_and_other_text(self):
        compose = '# Keep this comment.\r\n' + COMPOSE.replace('\n', '\r\n')
        generated = release_compose(compose, 'v1.5.1')
        self.assertTrue(generated.startswith('# Keep this comment.\r\n'))
        self.assertEqual(compose.count('\r\n'), generated.count('\r\n'))


class ReleaseAssetsWorkflowTest(unittest.TestCase):
    def test_workflow_generates_both_assets_before_release_creation(self):
        prepare = next(step for step in STEPS if step['name'] == 'Prepare setup assets')
        publish = next(step for step in STEPS if step['name'] == 'Create GitHub Release')
        self.assertLess(STEPS.index(prepare), STEPS.index(publish))
        self.assertEqual('${{ needs.validate-tag.outputs.version }}', prepare['env']['VERSION'])
        with tempfile.TemporaryDirectory() as directory:
            # Run the workflow's real command using only the two inputs and generator.
            root = Path(directory)
            (root / 'scripts').mkdir()
            (root / 'scripts/release_assets.py').write_bytes((ROOT / 'scripts/release_assets.py').read_bytes())
            (root / 'docker-compose.prod.yml').write_text(COMPOSE, encoding='utf-8')
            (root / '.env.example').write_bytes(ENV_EXAMPLE)
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', prepare['run']],
                                    cwd=root, env={**os.environ, 'VERSION': 'v1.5.1'},
                                    capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode, result.stderr)
            assets = root / 'release-assets'
            self.assertEqual({'.env.example', 'docker-compose.yml'}, {p.name for p in assets.iterdir()})
            self.assertEqual(ENV_EXAMPLE, (assets / '.env.example').read_bytes())
            self.assertEqual(release_compose(COMPOSE, 'v1.5.1').encode('utf-8'),
                             (assets / 'docker-compose.yml').read_bytes())

    def test_immutable_release_creation_includes_both_assets_and_rejects_missing_files(self):
        creators = [step for step in STEPS if step.get('uses', '').startswith('softprops/action-gh-release@')]
        self.assertEqual(1, len(creators))
        inputs = creators[0]['with']
        self.assertEqual({'release-assets/docker-compose.yml', 'release-assets/.env.example'},
                         set(inputs['files'].splitlines()))
        self.assertTrue(inputs['fail_on_unmatched_files'])
        self.assertEqual('release-notes.md', inputs['body_path'])

    def test_invalid_tag_cli_leaves_no_partial_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'assets'
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/release_assets.py'),
                                     'latest', '--compose', str(ROOT / 'docker-compose.prod.yml'),
                                     '--env-example', str(ROOT / '.env.example'), '--output-dir', str(output)],
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('vX.Y.Z', result.stderr)
            self.assertNotIn('Traceback', result.stderr)
            self.assertFalse(output.exists())


if __name__ == '__main__':
    unittest.main()
