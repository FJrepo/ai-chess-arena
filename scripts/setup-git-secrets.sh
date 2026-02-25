#!/usr/bin/env bash
set -euo pipefail

PATTERN="${1:-sk-or-v1-[A-Za-z0-9]{20,}}"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Error: not inside a git repository." >&2
  exit 1
fi

if ! command -v git-secrets >/dev/null 2>&1; then
  echo "Error: git-secrets is not installed." >&2
  exit 1
fi

git secrets --install -f
if git secrets --list | grep -F -- "$PATTERN" >/dev/null 2>&1; then
  echo "Pattern already registered, skipping add:"
  echo "  $PATTERN"
else
  git secrets --add "$PATTERN"
fi

echo "git-secrets hooks installed and pattern registered:"
echo "  $PATTERN"
