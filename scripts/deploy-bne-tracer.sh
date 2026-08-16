#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${CHONKCRAFT_ORACLE_HOST:-i9beef}"
REMOTE_ROOT="${CHONKCRAFT_ORACLE_ROOT:-.local/share/chonkcraft-bne-oracle}"
DLL="$ROOT/tools/bne-harness/build/bne-trace.dll"
HEADLESS="$ROOT/tools/bne-harness/scripts/bne_headless.py"

cmake --build "$ROOT/tools/bne-harness/build" --target bne-trace -j "${BNE_BUILD_JOBS:-8}"
local_sha="$(shasum -a 256 "$DLL" | awk '{print $1}')"

for harness in harness harness-branch-witness; do
  remote="$REMOTE_ROOT/$harness/build/bne-trace.dll"
  temporary="$remote.next"
  scp -q "$DLL" "$HOST:$temporary"
  ssh "$HOST" "test -f '$remote' && cp -p '$remote' '$remote.previous' || true; mv '$temporary' '$remote'; chmod 755 '$remote'"
  scp -q "$HEADLESS" "$HOST:$REMOTE_ROOT/$harness/scripts/bne_headless.py.next"
  ssh "$HOST" "mv '$REMOTE_ROOT/$harness/scripts/bne_headless.py.next' '$REMOTE_ROOT/$harness/scripts/bne_headless.py'; chmod 755 '$REMOTE_ROOT/$harness/scripts/bne_headless.py'"
done

for harness in harness harness-branch-witness; do
  remote_sha="$(ssh "$HOST" "sha256sum '$REMOTE_ROOT/$harness/build/bne-trace.dll'" | awk '{print $1}')"
  test "$remote_sha" = "$local_sha" || {
    echo "remote tracer identity mismatch in $harness" >&2
    exit 1
  }
done

echo "deployed bne-trace.dll $local_sha to $HOST"
