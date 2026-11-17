#!/usr/bin/env python3
"""Offline regressions for changelog extraction and the release workflow's shell steps."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

from release_notes import extract_release_notes


ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8')
WORKFLOW = (ROOT / '.github/workflows/release.yml').read_text(encoding='utf-8')


def workflow_step(name):
    step = WORKFLOW.split(f'      - name: {name}\n', 1)[1]
    body = step.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0]
    return '\n'.join(line.removeprefix('          ') for line in body.splitlines())


def headings(notes):
    return re.findall(r'^## (.+)$', notes, re.MULTILINE)


class ReleaseNotesTest(unittest.TestCase):
    def test_real_1_4_0(self):
        notes = extract_release_notes('v1.4.0', CHANGELOG)
        self.assertEqual(['Highlights', 'Upgrade notes', 'Added', 'Changed', 'Fixed', 'Security'],
                         headings(notes))
        intro = CHANGELOG.split('## [1.4.0] - 2026-11-16\n\n', 1)[1].split('\n\n', 1)[0]
        self.assertTrue(notes.startswith(f'## Highlights\n\n{intro}\n\n## Upgrade notes\n'))
        for content in [
            '- Migration V15 runs automatically:',
            '- Model picker in tournament setup:',
            '- The final v1.4.0 default is **no time limit per move**',
            '- Models were given an outdated move list',
            '- DNS rebinding is blocked:',
        ]:
            with self.subTest(content=content):
                self.assertIn(content, notes)
        self.assertTrue(notes.endswith('- The frontend service has a container healthcheck in both Compose files.\n'))
        self.assertNotIn('Patch release: the per-move time limit', notes)
        self.assertNotIn('## [Unreleased]', notes)

    def test_real_1_3_1_preserves_fixed_before_changed(self):
        notes = extract_release_notes('1.3.1', CHANGELOG)
        self.assertEqual(['Highlights', 'Upgrade notes', 'Fixed', 'Changed'], headings(notes))
        self.assertTrue(notes.startswith('## Highlights\n\nPatch release: the per-move time limit'))
        self.assertIn('Migration V11 runs automatically:', notes)
        self.assertIn('The move timeout is enforced as a hard deadline', notes)
        self.assertIn('`openrouter.request-safety-cap`, default 10 minutes', notes)
        self.assertNotIn('Migration V15', notes)
        self.assertNotIn('## Added', notes)

    def test_without_highlights(self):
        self.assertEqual('## Fixed\n\n- A fix.\n', extract_release_notes(
            '1.0.0', '## [1.0.0] - 2026-01-01\n\n### Fixed\n- A fix.\n'))

    def test_without_upgrade_notes(self):
        self.assertEqual('## Highlights\n\nAn introduction.\n\n## Added\n\n- A feature.\n',
                         extract_release_notes('1.0.0',
                             '## [1.0.0]\n\nAn introduction.\n\n### Added\n- A feature.'))

    def test_missing_version(self):
        with self.assertRaisesRegex(ValueError, 'No CHANGELOG section found for version 9.9.9'):
            extract_release_notes('v9.9.9', CHANGELOG)

    def test_version_match_is_exact(self):
        with self.assertRaisesRegex(ValueError, 'No CHANGELOG section'):
            extract_release_notes('1.4.0', '## [1.4.01] - 2026-01-01\n\nWrong version.')

    def test_invalid_versions(self):
        for version in ['Unreleased', '1.4', 'v1.4.0-rc1']:
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, 'Version must be'):
                extract_release_notes(version, CHANGELOG)

    def test_real_last_section(self):
        notes = extract_release_notes('0.1.0', CHANGELOG)
        self.assertEqual(['Added', 'Changed', 'Security'], headings(notes))
        self.assertTrue(notes.endswith('- Parameterized database credentials in `docker-compose.yml`.\n'))
        self.assertNotIn('## [0.2.0]', notes)

    def test_last_section_without_trailing_newline(self):
        self.assertEqual('## Fixed\n\n- Last fix.\n', extract_release_notes(
            '1.0.0', '# Changelog\n\n## [1.0.0]\n\n### Fixed\n- Last fix.'))

    def test_upgrade_notes_moves_first_and_other_sections_keep_order(self):
        notes = extract_release_notes('1.0.0', '''## [1.0.0]

Summary.

### Security
- Security fix.

### Fixed
- Fix.

### Upgrade notes
- Back up first.

### Added
- Feature.

### Changed
- Change.
''')
        self.assertEqual(['Highlights', 'Upgrade notes', 'Security', 'Fixed', 'Added', 'Changed'],
                         headings(notes))
        self.assertIn('## Upgrade notes\n\n- Back up first.\n\n## Security', notes)

    def test_link_references_at_eof_do_not_leak(self):
        notes = extract_release_notes('1.0.0', '''## [1.0.0]

### Fixed
- Last fix.

[Unreleased]: https://example.invalid/compare/v1.0.0...HEAD
[1.0.0]: https://example.invalid/releases/tag/v1.0.0
''')
        self.assertEqual('## Fixed\n\n- Last fix.\n', notes)

    def test_markdown_lists_links_and_nested_headings_are_preserved(self):
        notes = extract_release_notes('1.0.0', '''## [1.0.0]

### Added
- **Feature**: [guide](docs/guide.md).
  - Nested `code`.

#### Details
More information.
''')
        self.assertIn('- **Feature**: [guide](docs/guide.md).\n  - Nested `code`.\n', notes)
        self.assertIn('### Details\nMore information.', notes)

    def test_cli_prints_notes(self):
        result = subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/release_notes.py'),
                                 'v1.3.1', str(ROOT / 'CHANGELOG.md')],
                                capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(extract_release_notes('1.3.1', CHANGELOG), result.stdout)

    def test_cli_missing_version_fails_without_partial_notes(self):
        result = subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/release_notes.py'),
                                 'v9.9.9', str(ROOT / 'CHANGELOG.md')],
                                capture_output=True, text=True, check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertIn('No CHANGELOG section found for version 9.9.9', result.stderr)
        self.assertNotIn('Traceback', result.stderr)


class ReleaseNotesWorkflowTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        (self.root / 'scripts').mkdir()
        shutil.copy(ROOT / 'scripts/release_notes.py', self.root / 'scripts/release_notes.py')
        (self.root / 'CHANGELOG.md').write_text(CHANGELOG, encoding='utf-8')
        self.env = {**os.environ, 'VERSION': 'v1.4.0',
                    'BACKEND_IMAGE': 'ghcr.io/example/chess-backend',
                    'FRONTEND_IMAGE': 'ghcr.io/example/chess-frontend',
                    'BACKEND_DIGEST': 'sha256:' + 'a' * 64,
                    'FRONTEND_DIGEST': 'sha256:' + 'b' * 64,
                    'GITHUB_SERVER_URL': 'https://github.com',
                    'GITHUB_REPOSITORY': 'example/chess',
                    'GIT_AUTHOR_NAME': 'Release Tests', 'GIT_COMMITTER_NAME': 'Release Tests',
                    'GIT_AUTHOR_EMAIL': 'release@example.invalid',
                    'GIT_COMMITTER_EMAIL': 'release@example.invalid'}
        self.git('init', '--quiet')

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, env=self.env,
                              capture_output=True, text=True, check=True)

    def commit(self):
        self.git('-c', 'commit.gpgsign=false', 'commit', '--quiet', '--allow-empty', '-m', 'Release fixture')

    def run_step(self, name, version='v1.4.0'):
        return subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', workflow_step(name)],
                              cwd=self.root, env={**self.env, 'VERSION': version},
                              capture_output=True, text=True, check=False)

    def test_validate_tag_accepts_present_entry(self):
        result = self.run_step('Validate changelog entry')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stdout)

    def test_validate_tag_rejects_missing_entry(self):
        result = self.run_step('Validate changelog entry', version='v9.9.9')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('::error::CHANGELOG.md must contain a release section for v9.9.9.', result.stdout)
        self.assertIn('No CHANGELOG section found for version 9.9.9', result.stderr)

    def test_body_appends_digests_and_previous_release_compare_link(self):
        self.commit()
        self.git('tag', 'v1.3.1')
        self.commit()
        for tag in ['v1.4.0-rc1', 'v9.9.9-extra', 'v9.9.9.9']:
            self.git('tag', tag)
        self.commit()
        self.git('tag', 'v1.4.0')
        result = self.run_step('Build release notes')
        self.assertEqual(0, result.returncode, result.stderr)
        notes = (self.root / 'release-notes.md').read_text(encoding='utf-8')
        self.assertTrue(notes.startswith(extract_release_notes('1.4.0', CHANGELOG)))
        verification = notes.split('\n## Verification\n\n', 1)[1]
        for component in ['BACKEND', 'FRONTEND']:
            self.assertIn(f'- `{self.env[component + "_IMAGE"]}:v1.4.0` '
                          f'(`{self.env[component + "_DIGEST"]}`)', verification)
        self.assertTrue(notes.endswith('[Full Changelog](https://github.com/example/chess/compare/v1.3.1...v1.4.0)\n'))
        self.assertEqual('Verification', headings(notes)[-1])

    def test_first_release_links_to_tagged_history(self):
        self.commit()
        self.git('tag', 'v1.4.0')
        result = self.run_step('Build release notes')
        self.assertEqual(0, result.returncode, result.stderr)
        notes = (self.root / 'release-notes.md').read_text(encoding='utf-8')
        self.assertTrue(notes.endswith('[Full Changelog](https://github.com/example/chess/commits/v1.4.0)\n'))

    def test_publish_step_also_rejects_missing_entry(self):
        result = self.run_step('Build release notes', version='v9.9.9')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', (self.root / 'release-notes.md').read_text())

    def test_workflow_gates_builds_and_disables_generated_notes(self):
        validation = WORKFLOW.split('  validate-tag:\n', 1)[1].split('\n  ci:', 1)[0]
        self.assertIn('- name: Validate changelog entry\n', validation)
        ci = WORKFLOW.split('  ci:\n', 1)[1].split('\n  build-candidates:', 1)[0]
        self.assertIn('    needs: validate-tag\n', ci)
        build = WORKFLOW.split('  build-candidates:\n', 1)[1].split('\n  smoke-test:', 1)[0]
        self.assertIn('      - validate-tag\n', build)
        publish = WORKFLOW.split('  publish-release:\n', 1)[1]
        self.assertIn('          fetch-depth: 0\n', publish)
        self.assertIn('          generate_release_notes: false\n', publish)
        self.assertIn('          body_path: release-notes.md\n', publish)
        self.assertNotIn('generate_release_notes: true', WORKFLOW)


if __name__ == '__main__':
    unittest.main()
