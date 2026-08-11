#!/usr/bin/env bash
# Certify the release-shaped two-file boot path, not a developer checkout.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
asset_pack="${CHONKCRAFT_ASSET_PACK:-}"

if [[ ! -f "${asset_pack}" ]]; then
  echo "CHONKCRAFT_ASSET_PACK must name the authenticated BNE pack" >&2
  exit 2
fi
CHONKCRAFT_SKIP_BUILD=0 \
  "${repo_root}/scripts/release/build-update-assets.sh" >/dev/null

release_dir="${repo_root}/desktop/target/dist/update"
game_jar="${release_dir}/chonkcraft-game.jar"
catalog="${release_dir}/chonkcraft-release.properties"
for artifact in "${game_jar}" "${catalog}"; do
  if [[ ! -s "${artifact}" ]]; then
    echo "release artifact missing or empty: ${artifact}" >&2
    exit 1
  fi
done

# The tests receive only the authenticated pack. No source checkout or
# separately installed content tree can change what this invocation reads.
CHONKCRAFT_ASSET_PACK="${asset_pack}" \
  "${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=StandaloneBootTest,CampaignRealDataTest,NativeRosterRealDataTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# The updater/launcher installer is a separate trust boundary: hashes, safe
# extraction and atomic version promotion are exercised here as well.
"${repo_root}/scripts/run-tests.sh" -pl launcher -am \
  -Dtest=GameReleaseManagerTest -Dsurefire.failIfNoSpecifiedTests=false
