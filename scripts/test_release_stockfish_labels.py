#!/usr/bin/env python3
"""Check release build labels and the real label gate without Docker or a registry."""

import os
from pathlib import Path
import re
import shlex
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
LICENSE_LABEL = 'org.opencontainers.image.licenses'


class ReleaseBuildLabelsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        job = workflow.split('\n  build-candidates:\n', 1)[1].split('\n  smoke-test:\n', 1)[0]
        cls.metadata = job.split('      - name: Docker metadata\n', 1)[1].split('\n      - name:', 1)[0]
        cls.build = job.split('      - name: Build and push candidate image\n', 1)[1]

    def test_backend_metadata_license_matches_dockerfile(self):
        dockerfile = (ROOT / 'backend/Dockerfile').read_text().replace('\\\n', ' ')
        labels = {}
        for instruction in dockerfile.splitlines():
            if instruction.startswith('LABEL '):
                labels.update(token.split('=', 1) for token in shlex.split(instruction)[1:])
        licenses = labels[LICENSE_LABEL]
        # Require a backend-only override: an empty frontend input keeps its generated labels.
        label_input = re.search(r'^          labels: \|\n((?:            .*\n)+)', self.metadata, re.MULTILINE)
        self.assertIsNotNone(label_input, 'Metadata must explicitly preserve the backend Dockerfile license')
        self.assertEqual(
            f"${{{{ matrix.component == 'backend' && '{LICENSE_LABEL}={licenses}' || '' }}}}",
            '\n'.join(line.removeprefix('            ') for line in label_input[1].splitlines()),
        )

    def test_candidate_build_publishes_all_metadata_labels_for_selected_dockerfile(self):
        self.assertRegex(self.metadata, r'(?m)^        id: meta$')
        self.assertRegex(self.metadata, r'(?m)^        uses: docker/metadata-action@\S+$')
        self.assertRegex(self.build, r'(?m)^        uses: docker/build-push-action@\S+$')
        for key, value in {
            'context': './${{ matrix.component }}',
            'file': './${{ matrix.component }}/Dockerfile',
            'labels': '${{ steps.meta.outputs.labels }}',
        }.items():
            with self.subTest(input=key):
                self.assertRegex(self.build, rf'(?m)^          {key}: {re.escape(value)}$')


class StockfishReleaseLabelsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/release.yml').read_text()
        step = workflow.split('      - name: Verify Stockfish license files and labels\n', 1)[1]
        body = step.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0]
        cls.gate = '\n'.join(line.removeprefix('          ') for line in body.splitlines())

    def check_labels(self, version='sf_19', source=None, licenses='MIT, GPL-3.0-or-later',
                     bad_arch='arm64', missing_license_arch=''):
        source = source if source is not None else 'https://github.com/official-stockfish/Stockfish/tree/sf_19'
        # Substitute every requested Go-template field, including concatenated fields:
        # this deliberately reproduces the false positive in the previous grep gate.
        mock = r'''
function docker() {
  case "$1" in
    buildx)
      printf '%s\n' '{"manifests":[{"platform":{"os":"linux","architecture":"amd64"},"digest":"sha256:amd64"},{"platform":{"os":"linux","architecture":"arm64"},"digest":"sha256:arm64"}]}'
      ;;
    pull) return 0 ;;
    run) test "$7" != "fixture@sha256:$TEST_MISSING_LICENSE_ARCH" ;;
    inspect)
      local template="${@: -1}"
      local version='sf_19' source='https://github.com/official-stockfish/Stockfish/tree/sf_19' licenses='MIT, GPL-3.0-or-later'
      if [[ "$2" == "fixture@sha256:$TEST_BAD_ARCH" ]]; then
        version="$TEST_VERSION" source="$TEST_SOURCE" licenses="$TEST_LICENSES"
      fi
      template="${template//'{{ index .Config.Labels "dev.aichessarena.stockfish.version" }}'/$version}"
      template="${template//'{{ index .Config.Labels "dev.aichessarena.stockfish.source" }}'/$source}"
      template="${template//'{{ index .Config.Labels "org.opencontainers.image.licenses" }}'/$licenses}"
      printf '%s\n' "$template"
      ;;
    *) return 1 ;;
  esac
}
'''
        result = subprocess.run(
            ['bash', '-e', '-o', 'pipefail', '-c', mock + self.gate],
            env={**os.environ, 'BACKEND_PINNED': 'fixture', 'TEST_VERSION': version,
                 'TEST_SOURCE': source, 'TEST_LICENSES': licenses, 'TEST_BAD_ARCH': bad_arch,
                 'TEST_MISSING_LICENSE_ARCH': missing_license_arch},
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

    def test_repository_only_license_is_rejected(self):
        for arch in ['amd64', 'arm64']:
            with self.subTest(arch=arch):
                self.assertNotEqual(0, self.check_labels(licenses='MIT', bad_arch=arch))

    def test_missing_labels_are_rejected(self):
        for label in ['version', 'source', 'licenses']:
            with self.subTest(label=label):
                self.assertNotEqual(0, self.check_labels(**{label: ''}))

    def test_wrong_labels_on_either_platform_are_rejected(self):
        for arch in ['amd64', 'arm64']:
            for label in ['version', 'source', 'licenses']:
                with self.subTest(arch=arch, label=label):
                    self.assertNotEqual(0, self.check_labels(bad_arch=arch, **{label: 'wrong'}))

    def test_missing_license_files_on_either_platform_are_rejected(self):
        for arch in ['amd64', 'arm64']:
            with self.subTest(arch=arch):
                self.assertNotEqual(0, self.check_labels(missing_license_arch=arch))


if __name__ == '__main__':
    unittest.main()
