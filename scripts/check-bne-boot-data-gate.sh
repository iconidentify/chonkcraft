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
catalog="${release_dir}/latest.properties"
if [[ ! -s "${catalog}" ]]; then
  echo "release catalog missing or empty: ${catalog}" >&2
  exit 1
fi
python3 - "${release_dir}" "${catalog}" <<'PY'
import base64
from pathlib import Path
import sys

release_dir = Path(sys.argv[1]).resolve()
catalog = {}
for line in Path(sys.argv[2]).read_text(encoding="utf-8").splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        catalog[key] = value
if catalog.get("format") != "chonkcraft-signed-release-1":
    raise SystemExit("release catalog has the wrong signed-envelope format")
try:
    payload_text = base64.b64decode(
        catalog["payload"], validate=True).decode("utf-8")
except (KeyError, ValueError, UnicodeDecodeError) as error:
    raise SystemExit(f"release catalog payload is invalid: {error}")
payload = {}
for line in payload_text.splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        payload[key] = value
game = (release_dir / payload.get("game.url", "")).resolve()
try:
    game.relative_to(release_dir)
except ValueError:
    raise SystemExit("release catalog game.url escapes the update directory")
if not game.is_file() or game.stat().st_size <= 0:
    raise SystemExit(f"release artifact missing or empty: {game}")
PY

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
