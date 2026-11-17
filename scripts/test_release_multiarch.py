#!/usr/bin/env python3
"""Exercise the real release index and ARM64 UCI gates without a registry or Docker."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


def workflow_gate(name):
    workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/release.yml').read_text()
    step = workflow.split(f'      - name: {name}\n', 1)[1]
    body = step.split('        run: |\n', 1)[1].split('\n      - name:', 1)[0]
    return '\n'.join(line.removeprefix('          ') for line in body.splitlines())


def image_index(arches=('amd64', 'arm64')):
    return {
        'mediaType': 'application/vnd.oci.image.index.v1+json',
        'manifests': [
            {'platform': {'os': 'linux', 'architecture': arch}}
            for arch in arches
        ] + [{'platform': {'os': 'unknown', 'architecture': 'unknown'}}],
    }


class MultiarchReleaseTest(unittest.TestCase):
    def run_gate(self, name, **env):
        with tempfile.TemporaryDirectory() as directory:
            docker = Path(directory) / 'docker'
            docker.write_text('''#!/usr/bin/env bash
set -eu
case "$1" in
  buildx)
    test "$2 $3" = 'imagetools inspect'
    if [[ "${5:-}" == '--raw' ]]; then
      case "$4" in
        backend) printf '%s\\n' "$TEST_BACKEND_INDEX" ;;
        frontend) printf '%s\\n' "$TEST_FRONTEND_INDEX" ;;
        *) exit 1 ;;
      esac
    fi
    ;;
  run)
    test "$*" = 'run --rm -i --platform linux/arm64 --network none --entrypoint /usr/local/bin/stockfish backend'
    test "$(cat)" = $'uci\\nquit'
    printf '%s\\n' "$TEST_UCI_OUTPUT"
    exit "${TEST_UCI_STATUS:-0}"
    ;;
  *) exit 1 ;;
esac
''')
            docker.chmod(0o755)
            return subprocess.run(
                ['bash', '-e', '-o', 'pipefail', '-c', workflow_gate(name)],
                env={**os.environ, 'PATH': directory + os.pathsep + os.environ['PATH'],
                     'BACKEND_PINNED': 'backend', 'FRONTEND_PINNED': 'frontend', **env},
                capture_output=True, text=True, check=False,
            )

    def check_indexes(self, backend=None, frontend=None):
        return self.run_gate(
            'Verify candidate platforms',
            TEST_BACKEND_INDEX=json.dumps(backend if backend is not None else image_index()),
            TEST_FRONTEND_INDEX=json.dumps(frontend if frontend is not None else image_index()),
        )

    def test_both_platforms_with_attestations_pass(self):
        result = self.check_indexes()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_platform_in_either_image_is_rejected(self):
        for component in ['backend', 'frontend']:
            for arch in ['amd64', 'arm64']:
                with self.subTest(component=component, remaining_arch=arch):
                    self.assertNotEqual(0, self.check_indexes(**{component: image_index([arch])}).returncode)

    def test_single_platform_manifest_is_rejected(self):
        self.assertNotEqual(0, self.check_indexes(backend={
            'mediaType': 'application/vnd.oci.image.manifest.v1+json',
        }).returncode)

    def test_non_oci_index_is_rejected(self):
        index = image_index()
        index['mediaType'] = 'application/vnd.docker.distribution.manifest.list.v2+json'
        self.assertNotEqual(0, self.check_indexes(frontend=index).returncode)

    def test_arm64_uci_handshake_passes(self):
        result = self.run_gate('Check arm64 Stockfish UCI under emulation',
                               TEST_UCI_OUTPUT='id name Stockfish 19\nuciok')
        self.assertEqual(0, result.returncode, result.stderr)

    def test_arm64_wrong_version_or_incomplete_handshake_is_rejected(self):
        for output in ['id name Stockfish 18\nuciok', 'id name Stockfish 190\nuciok',
                       'id name Stockfish 19', 'uciok', 'id name Stockfish 19\nprefix_uciok_suffix']:
            with self.subTest(output=output):
                result = self.run_gate('Check arm64 Stockfish UCI under emulation',
                                       TEST_UCI_OUTPUT=output)
                self.assertNotEqual(0, result.returncode)

    def test_arm64_execution_failure_is_rejected(self):
        result = self.run_gate('Check arm64 Stockfish UCI under emulation',
                               TEST_UCI_OUTPUT='id name Stockfish 19\nuciok', TEST_UCI_STATUS='1')
        self.assertNotEqual(0, result.returncode)


if __name__ == '__main__':
    unittest.main()
