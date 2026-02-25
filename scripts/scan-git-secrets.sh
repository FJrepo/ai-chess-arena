#!/usr/bin/env bash
set -euo pipefail

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Error: not inside a git repository." >&2
  exit 1
fi

if ! command -v git-secrets >/dev/null 2>&1; then
  echo "Error: git-secrets is not installed." >&2
  exit 1
fi

echo "Scanning tracked files..."
git secrets --scan

echo "Scanning untracked files..."
mapfile -d '' -t untracked_files < <(git ls-files -z --others --exclude-standard)
if [ "${#untracked_files[@]}" -gt 0 ]; then
  git secrets --scan "${untracked_files[@]}"
else
  echo "No untracked files to scan."
fi

echo "Scanning commit history..."
git secrets --scan-history

echo "git-secrets scan completed."
