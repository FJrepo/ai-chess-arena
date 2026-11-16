"""Offline installer regressions: python3 -B -m unittest discover -s scripts -p 'test_*.py'."""

import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest


INSTALLER = Path(__file__).resolve().with_name("install-stockfish.sh")
AMD64_SHA = "9defc0d4e55d49c65a6d042f3e571a39fcea499ade6dbe741b53b8c65e03611f"
ARM64_SHA = "fe26cfd1d9db4c8af3d21e24d9ff34cacb31c1f940085a7583da11796f2bac01"
MACOS_SHA = "a1f0e3bcc5a6927a11fe6fc8e54a779754645f3c2bae2cf13420fd1957adaa77"


class InstallStockfishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith("STOCKFISH_") and k != "INSTALL_DIR"}
        self.env.update(PATH=f"{self.bin}:{os.environ['PATH']}",
                        TEST_OS="Linux", TEST_ARCH="x86_64")
        self.mock("uname", 'case "$1" in -s) echo "$TEST_OS";; -m) echo "$TEST_ARCH";; esac\n')
        self.mock("curl", 'for arg do destination="$arg"; done\ncp "$TEST_ARCHIVE" "$destination"\n')

    def mock(self, name, body):
        path = self.bin / name
        path.write_text("#!/bin/sh\nset -eu\n" + body)
        path.chmod(0o755)

    def run_installer(self, *args):
        return subprocess.run(["bash", str(INSTALLER), *args], env=self.env,
                              text=True, capture_output=True, timeout=10)

    def archive(self, names):
        path = self.root / "fixture.tar.gz"
        binary = b"#!/bin/sh\nexit 0\n"
        with tarfile.open(path, "w:gz") as archive:
            for name in names:
                info = tarfile.TarInfo(name)
                info.size = len(binary)
                info.mode = 0o755
                archive.addfile(info, io.BytesIO(binary))
        self.env["TEST_ARCHIVE"] = str(path)
        return hashlib.sha256(path.read_bytes()).hexdigest(), binary

    def install(self, checksum):
        return self.run_installer("--asset", "stockfish-linux-x86-64-universal.tar.gz",
                                  "--sha256", checksum, "--install-dir", str(self.root / "installed"))

    def test_default_platform_assets_and_checksums(self):
        for os_name, arch, asset_arch, sha in [
            ("Linux", "x86_64", "linux-x86-64", AMD64_SHA),
            ("Linux", "amd64", "linux-x86-64", AMD64_SHA),
            ("Linux", "aarch64", "linux-arm64", ARM64_SHA),
            ("Linux", "arm64", "linux-arm64", ARM64_SHA),
            ("Darwin", "arm64", "macos", MACOS_SHA),
            ("Darwin", "x86_64", "macos", MACOS_SHA),
        ]:
            with self.subTest(os=os_name, arch=arch):
                self.env.update(TEST_OS=os_name, TEST_ARCH=arch)
                result = self.run_installer("--dry-run")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("stockfish_version=sf_19", result.stdout)
                self.assertIn(f"asset=stockfish-{asset_arch}-universal.tar.gz", result.stdout)
                self.assertIn(f"sha256={sha}", result.stdout)

    def test_explicit_legacy_version_is_preserved(self):
        result = self.run_installer("--version", "sf_18", "--dry-run")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("asset=stockfish-ubuntu-x86-64-avx2.tar", result.stdout)

    def test_unsupported_architecture_fails_before_download(self):
        self.env["TEST_ARCH"] = "unknown"
        result = self.run_installer("--dry-run")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unsupported platform or version", result.stderr)

    def test_override_requires_checksum(self):
        result = self.run_installer("--asset", "custom.tar.gz", "--dry-run")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must be provided together", result.stderr)

    def test_gzip_install_finds_binary_in_changed_archive_layout(self):
        sha, binary = self.archive(["nested/layout/stockfish-linux-x86-64-universal"])
        result = self.install(sha)
        self.assertEqual(result.returncode, 0, result.stderr)
        installed = self.root / "installed" / "stockfish"
        self.assertEqual(installed.read_bytes(), binary)
        self.assertEqual(installed.stat().st_mode & 0o777, 0o755)
        self.assertIn("installation check passed", result.stderr)

    def test_checksum_mismatch_never_installs(self):
        self.archive(["stockfish/stockfish-linux-x86-64-universal"])
        result = self.install("0" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA256 mismatch", result.stderr)
        self.assertFalse((self.root / "installed").exists())

    def test_missing_or_ambiguous_binary_never_installs(self):
        for names in [["README.md"], ["a/stockfish-linux-x86-64-universal",
                                     "b/stockfish-linux-x86-64-universal"]]:
            with self.subTest(names=names):
                sha, _ = self.archive(names)
                result = self.install(sha)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Could not find extracted binary", result.stderr)
                self.assertFalse((self.root / "installed").exists())


if __name__ == "__main__":
    unittest.main()
