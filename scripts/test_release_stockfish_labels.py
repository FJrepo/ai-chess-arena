#!/usr/bin/env python3
"""Exercise the release workflow's real label gate without Docker or a registry."""

import os
from pathlib import Path
import subprocess
import unittest


class StockfishReleaseLabelsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/release.yml').read_text()
        step = workflow.split('      - name: Verify Stockfish license files and labels\n', 1)[1]
        body = step.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0]
        cls.gate = '\n'.join(line.removeprefix('          ') for line in body.splitlines())

    def check_labels(self, version='sf_19', source=None, licenses='MIT, GPL-3.0-or-later'):
        source = source if source is not None else 'https://github.com/official-stockfish/Stockfish/tree/sf_19'
        # Substitute every requested Go-template field, including concatenated fields:
        # this deliberately reproduces the false positive in the previous grep gate.
        mock = r'''
function docker() {
  case "$1" in
    pull|run) return 0 ;;
    inspect)
      local template="${@: -1}"
      template="${template//'{{ index .Config.Labels "dev.aichessarena.stockfish.version" }}'/$TEST_VERSION}"
      template="${template//'{{ index .Config.Labels "dev.aichessarena.stockfish.source" }}'/$TEST_SOURCE}"
      template="${template//'{{ index .Config.Labels "org.opencontainers.image.licenses" }}'/$TEST_LICENSES}"
      printf '%s\n' "$template"
      ;;
    *) return 1 ;;
  esac
}
'''
        result = subprocess.run(
            ['bash', '-e', '-o', 'pipefail', '-c', mock + self.gate],
            env={**os.environ, 'BACKEND_PINNED': 'fixture', 'TEST_VERSION': version,
                 'TEST_SOURCE': source, 'TEST_LICENSES': licenses},
            capture_output=True, text=True, check=False,
        )
        return result.returncode

    def test_exact_expected_labels_pass(self):
        self.assertEqual(0, self.check_labels())

    def test_old_version_with_expected_source_is_rejected(self):
        self.assertNotEqual(0, self.check_labels(version='sf_18'))

    def test_version_substring_is_rejected(self):
        self.assertNotEqual(0, self.check_labels(version='prefix_sf_19_suffix'))

    def test_wrong_source_with_expected_version_is_rejected(self):
        self.assertNotEqual(0, self.check_labels(source='https://example.invalid/sf_19'))

    def test_wrong_license_is_rejected(self):
        self.assertNotEqual(0, self.check_labels(licenses='MIT, GPL-3.0-only'))

    def test_missing_labels_are_rejected(self):
        for label in ['version', 'source', 'licenses']:
            with self.subTest(label=label):
                self.assertNotEqual(0, self.check_labels(**{label: ''}))


if __name__ == '__main__':
    unittest.main()
