#!/usr/bin/env bash
#
# Diff the vendored runtime against its source in seven-days-to-tomorrow.
#
# runtime/ is a copy, kept source-identical so drift is visible. Only the pom
# differs, and only in its parent coordinates, so it is excluded here.
#
# Usage:
#   scripts/sync-runtime.sh            report differences
#   scripts/sync-runtime.sh --apply    copy upstream over the local tree

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
upstream="${SEVEN_DAYS_DIR:-${repo_root}/../../seven-days-to-tomorrow/runtime}"

if [[ ! -d "${upstream}/src" ]]; then
  echo "sync-runtime: upstream runtime not found at ${upstream}" >&2
  echo "Set SEVEN_DAYS_DIR to the seven-days-to-tomorrow runtime directory." >&2
  exit 1
fi

if [[ "${1:-}" == "--apply" ]]; then
  rsync -a --delete "${upstream}/src/" "${repo_root}/runtime/src/"
  echo "sync-runtime: copied ${upstream}/src into runtime/src"
  exit 0
fi

if diff -r "${upstream}/src" "${repo_root}/runtime/src"; then
  echo "sync-runtime: vendored runtime matches upstream"
else
  echo
  echo "sync-runtime: differences above. Re-run with --apply to take upstream." >&2
  exit 1
fi
