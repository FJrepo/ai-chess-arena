#!/usr/bin/env bash
set -euo pipefail

STOCKFISH_VERSION="${STOCKFISH_VERSION:-sf_18}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
ASSET_OVERRIDE="${STOCKFISH_ASSET:-}"
SHA256_OVERRIDE="${STOCKFISH_SHA256:-}"
DRY_RUN=0

usage() {
  cat <<'EOF'
Install Stockfish with OS/architecture autodetection.

Usage:
  scripts/install-stockfish.sh [options]

Options:
  --install-dir <dir>  Install destination (default: $HOME/.local/bin)
  --version <tag>      Stockfish release tag (default: sf_18)
  --asset <name>       Override release asset filename (requires --sha256)
  --sha256 <value>     Override asset SHA256 (requires --asset)
  --dry-run            Print resolved selection without downloading
  -h, --help           Show this help

Environment overrides:
  INSTALL_DIR, STOCKFISH_VERSION, STOCKFISH_ASSET, STOCKFISH_SHA256
EOF
}

log() {
  printf '%s\n' "$*" >&2
}

fail() {
  log "Error: $*"
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --install-dir)
      [[ $# -ge 2 ]] || fail "--install-dir requires a value"
      INSTALL_DIR="$2"
      shift 2
      ;;
    --version)
      [[ $# -ge 2 ]] || fail "--version requires a value"
      STOCKFISH_VERSION="$2"
      shift 2
      ;;
    --asset)
      [[ $# -ge 2 ]] || fail "--asset requires a value"
      ASSET_OVERRIDE="$2"
      shift 2
      ;;
    --sha256)
      [[ $# -ge 2 ]] || fail "--sha256 requires a value"
      SHA256_OVERRIDE="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: $1"
      ;;
  esac
done

raw_os="$(uname -s)"
raw_arch="$(uname -m)"

case "$raw_arch" in
  x86_64|amd64) arch="x86_64" ;;
  arm64|aarch64) arch="arm64" ;;
  *) arch="$raw_arch" ;;
esac

os="$raw_os"
asset=""
sha256=""

if [[ -n "$ASSET_OVERRIDE" || -n "$SHA256_OVERRIDE" ]]; then
  [[ -n "$ASSET_OVERRIDE" && -n "$SHA256_OVERRIDE" ]] || fail "--asset and --sha256 must be provided together"
  asset="$ASSET_OVERRIDE"
  sha256="$SHA256_OVERRIDE"
else
  case "${os}:${arch}:${STOCKFISH_VERSION}" in
    Linux:x86_64:sf_18)
      asset="stockfish-ubuntu-x86-64-avx2.tar"
      sha256="536c0c2c0cf06450df0bfb5e876ef0d3119950703a8f143627f990c7b5417964"
      ;;
    Darwin:arm64:sf_18)
      asset="stockfish-macos-m1-apple-silicon.tar"
      sha256="4d77c4aa3ad9bd1ea8111f2ac5a4620fe7ebf998d6893bf828d49ccd579c8cb0"
      ;;
    Darwin:x86_64:sf_18)
      asset="stockfish-macos-x86-64-avx2.tar"
      sha256="41d30e0860ad924a6ceb422c3a36eba43bbe5ae87d3310840da50e71c53f35d9"
      ;;
    Linux:arm64:sf_18)
      cat >&2 <<'EOF'
Error: Stockfish 18 does not currently publish an official Linux ARM64 binary asset.
Options:
  1) Build Stockfish from source on ARM64.
  2) Run Docker with linux/amd64 emulation.
  3) Provide a custom asset + SHA256 via --asset and --sha256.
EOF
      exit 2
      ;;
    *)
      fail "Unsupported platform or version: os=${os}, arch=${arch}, version=${STOCKFISH_VERSION}"
      ;;
  esac
fi

download_url="https://github.com/official-stockfish/Stockfish/releases/download/${STOCKFISH_VERSION}/${asset}"
binary_name="${asset%.tar}"
target_path="${INSTALL_DIR}/stockfish"

if [[ $DRY_RUN -eq 1 ]]; then
  cat <<EOF
stockfish_version=${STOCKFISH_VERSION}
os=${os}
arch=${arch}
asset=${asset}
sha256=${sha256}
install_dir=${INSTALL_DIR}
target_path=${target_path}
download_url=${download_url}
EOF
  exit 0
fi

command -v tar >/dev/null 2>&1 || fail "tar is required"
if command -v curl >/dev/null 2>&1; then
  downloader="curl"
elif command -v wget >/dev/null 2>&1; then
  downloader="wget"
else
  fail "curl or wget is required"
fi

if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
  fail "sha256sum or shasum is required"
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
archive_path="${tmp_dir}/stockfish.tar"

log "Downloading ${download_url}"
if [[ "$downloader" == "curl" ]]; then
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 10 "$download_url" -o "$archive_path"
else
  wget -t 3 -T 30 -O "$archive_path" "$download_url"
fi

if command -v sha256sum >/dev/null 2>&1; then
  actual_sha="$(sha256sum "$archive_path" | awk '{print $1}' | tr '[:upper:]' '[:lower:]')"
else
  actual_sha="$(shasum -a 256 "$archive_path" | awk '{print $1}' | tr '[:upper:]' '[:lower:]')"
fi
expected_sha="$(printf '%s' "$sha256" | tr '[:upper:]' '[:lower:]')"
[[ "$actual_sha" == "$expected_sha" ]] || fail "SHA256 mismatch for ${asset}"

extract_dir="${tmp_dir}/extract"
mkdir -p "$extract_dir"
tar -xf "$archive_path" -C "$extract_dir"

candidate="${extract_dir}/stockfish/${binary_name}"
if [[ ! -f "$candidate" ]]; then
  candidate="$(find "$extract_dir" -type f -name "$binary_name" -print -quit || true)"
fi
[[ -n "$candidate" && -f "$candidate" ]] || fail "Could not find extracted binary: ${binary_name}"

mkdir -p "$INSTALL_DIR"
if [[ ! -w "$INSTALL_DIR" ]]; then
  fail "Install directory is not writable: ${INSTALL_DIR}"
fi

install -m 0755 "$candidate" "$target_path"
log "Installed Stockfish to ${target_path}"

if [[ ":${PATH}:" != *":${INSTALL_DIR}:"* ]]; then
  log "Note: ${INSTALL_DIR} is not currently in PATH."
  log "Add this to your shell profile:"
  log "  export PATH=\"${INSTALL_DIR}:\$PATH\""
fi

if "$target_path" <<<"quit" >/dev/null 2>&1; then
  log "Stockfish installation check passed."
else
  log "Installed binary, but runtime check did not complete cleanly."
fi
