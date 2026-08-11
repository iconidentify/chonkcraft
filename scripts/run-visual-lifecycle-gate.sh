#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 || ! -f "$1" ]]; then
  echo "usage: scripts/run-visual-lifecycle-gate.sh /path/to/BNE.chonkpack [state-cache]" >&2
  exit 2
fi

pack=$1
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state_cache=${2:-"${HOME}/.chonkcraft/work/bne-oracle/state-cache"}

if [[ ! -d "$state_cache" ]]; then
  echo "missing BNE state evidence: $state_cache" >&2
  exit 2
fi

cd "$root"
python3 tools/bne-harness/scripts/bne_visual_evidence.py "$state_cache"
scripts/jbr/with-jbr-25.sh mvn -f "$root/pom.xml" -pl desktop -am \
  -Dchonkcraft.pack="$pack" \
  -Dtest=VisualLifecycleRealDataTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
