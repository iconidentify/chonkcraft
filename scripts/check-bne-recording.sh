#!/usr/bin/env bash
# Authenticate and replay one passive multiplayer flight record.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 RECORDING_DIRECTORY [AUTHENTICATED_CHONKPACK]" >&2
  exit 2
fi

recording="$1"
asset_pack="${2:-${CHONKCRAFT_ASSET_PACK:-}}"
if [[ ! -d "${recording}" ]]; then
  echo "recording directory missing: ${recording}" >&2
  exit 2
fi
if [[ -z "${asset_pack}" || ! -f "${asset_pack}" ]]; then
  echo "pass an authenticated ChonkPack or set CHONKCRAFT_ASSET_PACK" >&2
  exit 2
fi

"${repo_root}/scripts/jbr/with-jbr-25.sh" mvn -q -pl desktop -am -DskipTests package

java_args=(
  "-Dchonkcraft.pack=${asset_pack}"
)
source_status="$(git -C "${repo_root}" status --porcelain --untracked-files=all \
  | awk '$1 != "??" || $2 !~ /^\.opencode\//')"
if [[ -z "${source_status}" ]]; then
  revision="$(git -C "${repo_root}" rev-parse HEAD)"
  java_args+=(
    "-Dchonkcraft.source.clean=true"
    "-Dchonkcraft.source.revision=${revision}"
  )
fi

exec "${repo_root}/scripts/jbr/with-jbr-25.sh" java -Xmx2048m \
  "${java_args[@]}" \
  -cp "${repo_root}/desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar" \
  net.chonkbase.chonkcraft.desktop.BneRecordingCertification "${recording}"
