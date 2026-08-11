#!/usr/bin/env bash
# Publishes a complete, verified cross-platform installer set. Immutable
# versioned files land first; stable download names and the public catalog move
# only after every uploaded byte has been checked in the serving pod.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${root}/scripts/release/lib-release.sh"
version="$(release_version)"
namespace="${CHONKCRAFT_UPDATE_NAMESPACE:-chonkcraft-updates}"
selector="app.kubernetes.io/name=chonkcraft-updates"
source_dir="${CHONKCRAFT_INSTALLER_ROOT:-${root}/desktop/target/dist/installers}"
public_root="https://updates.chonkbase.net/downloads"
remote_root="/usr/share/nginx/html"
remote_dir="${remote_root}/downloads/${version}"
remote_latest="${remote_root}/downloads/latest"

[[ "${version}" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$ ]] || {
  echo "Unsafe installer version: ${version}" >&2
  exit 1
}

platforms=(macos windows linux)
artifacts=(
  "ChonkCraft-${version}-macos-arm64.dmg"
  "ChonkCraft-${version}-windows-x64.msi"
  "ChonkCraft-${version}-linux-x64.AppImage"
)
stable=(
  "ChonkCraft-macos-arm64.dmg"
  "ChonkCraft-windows-x64.msi"
  "ChonkCraft-linux-x64.AppImage"
)

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

for index in "${!artifacts[@]}"; do
  artifact="${source_dir}/${artifacts[$index]}"
  checksum="${artifact}.sha256"
  [[ -s "${artifact}" ]] || { echo "Installer not found: ${artifact}" >&2; exit 1; }
  [[ -s "${checksum}" ]] || { echo "Checksum not found: ${checksum}" >&2; exit 1; }
  expected="$(hash_file "${artifact}")"
  recorded="$(awk '{print $1; exit}' "${checksum}")"
  [[ "${expected}" == "${recorded}" ]] || {
    echo "Checksum mismatch for ${artifact}" >&2
    exit 1
  }
done

pod="$(kubectl -n "${namespace}" get pod -l "${selector}" \
  --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')"
[[ -n "${pod}" ]] || { echo "No running update server pod was found." >&2; exit 1; }

incoming="${remote_root}/.incoming-installers-${GITHUB_RUN_ID:-$$}-${GITHUB_RUN_ATTEMPT:-0}"
catalog="$(mktemp)"
mac_properties="$(mktemp)"
trap 'rm -f "${catalog}" "${mac_properties}"; kubectl -n "${namespace}" exec "${pod}" -- rm -rf "${incoming}" >/dev/null 2>&1 || true' EXIT

CATALOG_PATH="${catalog}" SOURCE_DIR="${source_dir}" RELEASE_VERSION="${version}" \
  SOURCE_REVISION="${GITHUB_SHA:-$(git -C "${root}" rev-parse HEAD)}" \
  PUBLIC_ROOT="${public_root}" python3 - <<'PY'
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

version = os.environ["RELEASE_VERSION"]
source_dir = Path(os.environ["SOURCE_DIR"])
public_root = os.environ["PUBLIC_ROOT"].rstrip("/")
spec = {
    "macos": (f"ChonkCraft-{version}-macos-arm64.dmg", "Apple silicon", "DMG"),
    "windows": (f"ChonkCraft-{version}-windows-x64.msi", "x86-64", "MSI"),
    "linux": (f"ChonkCraft-{version}-linux-x64.AppImage", "x86-64", "AppImage"),
}
platforms = {}
for platform, (name, architecture, package_format) in spec.items():
    path = source_dir / name
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    platforms[platform] = {
        "architecture": architecture,
        "format": package_format,
        "url": f"{public_root}/{version}/{name}",
        "sha256": digest,
        "bytes": path.stat().st_size,
    }

catalog = {
    "format": "chonkcraft-installers-1",
    "version": version,
    "published": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "sourceRevision": os.environ["SOURCE_REVISION"],
    "platforms": platforms,
}
Path(os.environ["CATALOG_PATH"]).write_text(
    json.dumps(catalog, indent=2, sort_keys=True) + "\n", encoding="utf-8"
)
PY

mac_name="${artifacts[0]}"
mac_sha="$(hash_file "${source_dir}/${mac_name}")"
cat > "${mac_properties}" <<EOF
format=chonkcraft-macos-release-1
version=${version}
dmg.url=downloads/${version}/${mac_name}
dmg.sha256=${mac_sha}
dmg.bytes=$(wc -c < "${source_dir}/${mac_name}" | tr -d ' ')
source.revision=${GITHUB_SHA:-$(git -C "${root}" rev-parse HEAD)}
published=$(date -u +'%Y-%m-%dT%H:%M:%SZ')
EOF

kubectl -n "${namespace}" exec "${pod}" -- mkdir -p "${incoming}" "${remote_dir}" "${remote_latest}"
for index in "${!artifacts[@]}"; do
  artifact="${source_dir}/${artifacts[$index]}"
  kubectl -n "${namespace}" cp "${artifact}" "${pod}:${incoming}/${artifacts[$index]}"
  actual="$(kubectl -n "${namespace}" exec "${pod}" -- sha256sum \
    "${incoming}/${artifacts[$index]}" | awk '{print $1}')"
  expected="$(hash_file "${artifact}")"
  [[ "${actual}" == "${expected}" ]] || {
    echo "Uploaded installer failed SHA-256: ${artifacts[$index]}" >&2
    exit 1
  }
done
kubectl -n "${namespace}" cp "${catalog}" "${pod}:${incoming}/latest.json"
kubectl -n "${namespace}" cp "${mac_properties}" "${pod}:${incoming}/latest-macos.properties"

# The single remote transaction preserves immutable history, refreshes stable
# download names, and makes the new catalog visible last.
remote_script='set -eu
incoming="$1"; version_dir="$2"; latest_dir="$3"
shift 3
while [ "$#" -gt 0 ]; do
  version_name="$1"; stable_name="$2"; shift 2
  if [ -f "$version_dir/$version_name" ]; then
    cmp -s "$incoming/$version_name" "$version_dir/$version_name" || {
      echo "immutable installer differs: $version_name" >&2; exit 1;
    }
    rm "$incoming/$version_name"
  else
    mv "$incoming/$version_name" "$version_dir/$version_name"
  fi
  cp "$version_dir/$version_name" "$latest_dir/.${stable_name}.new"
  mv "$latest_dir/.${stable_name}.new" "$latest_dir/$stable_name"
done
mv "$incoming/latest-macos.properties" /usr/share/nginx/html/downloads/latest-macos.properties
mv "$incoming/latest.json" /usr/share/nginx/html/downloads/latest.json
rmdir "$incoming"'

remote_args=()
for index in "${!artifacts[@]}"; do
  remote_args+=("${artifacts[$index]}" "${stable[$index]}")
done
kubectl -n "${namespace}" exec "${pod}" -- sh -c "${remote_script}" sh \
  "${incoming}" "${remote_dir}" "${remote_latest}" "${remote_args[@]}"

rm -f "${catalog}" "${mac_properties}"
trap - EXIT

curl --fail --silent --show-error --retry 12 --retry-all-errors --retry-delay 5 \
  "${public_root}/latest.json" >/dev/null
for index in "${!stable[@]}"; do
  curl --fail --silent --show-error --retry 12 --retry-all-errors --retry-delay 5 \
    "${public_root}/latest/${stable[$index]}" -o /dev/null
done
printf 'Published ChonkCraft %s for macOS, Windows, and Linux.\n' "${version}"
