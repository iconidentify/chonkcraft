#!/usr/bin/env bash
#
# Builds one authenticated, atomically publishable game update:
#
#   latest.properties
#   releases/<version>/chonkcraft-game-<sha256>.jar
#   releases/release-notes/chonkcraft-release-notes-<sha256>.properties
#
# latest.properties is a single signed envelope. Publishing the immutable JAR
# first and replacing this one small file last means a player can see either
# the complete old release or the complete new release, never half of each.

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib-release.sh"

root="$(release_repo_root)"
version="$(release_version)"
output="${CHONKCRAFT_UPDATE_OUTPUT:-${root}/desktop/target/dist/update}"
game="${root}/desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"
key_id="${CHONKCRAFT_OTA_KEY_ID:-ota-2026-01}"
private_key="${CHONKCRAFT_OTA_ED25519_PRIVATE_KEY_BASE64:-}"

if [[ ! "$version" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$ ]]; then
  echo "The OTA version is not a safe release identifier: $version" >&2
  exit 1
fi
if [[ -z "$private_key" ]]; then
  echo "CHONKCRAFT_OTA_ED25519_PRIVATE_KEY_BASE64 is required." >&2
  exit 1
fi

hash_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

single_line_base64() {
  base64 < "$1" | tr -d '\r\n'
}

launcher="$(release_build_launcher_jar "${root}")"
rm -rf "${output}"
mkdir -p "${output}/releases/${version}" "${output}/releases/release-notes"

game_sha="$(hash_file "${game}")"
game_name="chonkcraft-game-${game_sha}.jar"
game_relative="releases/${version}/${game_name}"
cp "${game}" "${output}/${game_relative}"
game_bytes="$(wc -c < "${output}/${game_relative}" | tr -d ' ')"
release_notes="${CHONKCRAFT_RELEASE_NOTES:-ChonkCraft ${version}}"
notes="${CHONKCRAFT_RELEASE_TITLE:-${release_notes%%$'\n'*}}"
notes="${notes//\\/\\\\}"
revision="${GITHUB_SHA:-$(git -C "${root}" rev-parse HEAD)}"
published="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"

key_dir="$(mktemp -d)"
trap 'find "${key_dir}" -type f -delete 2>/dev/null || true; rmdir "${key_dir}" 2>/dev/null || true' EXIT
umask 077
printf '%s' "${private_key}" | base64 --decode > "${key_dir}/private.der"
openssl pkey -inform DER -in "${key_dir}/private.der" \
  -out "${key_dir}/private.pem" >/dev/null 2>&1

previous_notes="${key_dir}/previous-release-notes.properties"
if [[ -n "${CHONKCRAFT_RELEASE_HISTORY_FILE:-}" ]]; then
  cp "${CHONKCRAFT_RELEASE_HISTORY_FILE}" "${previous_notes}"
elif [[ -n "${CHONKCRAFT_RELEASE_HISTORY_CATALOG:-}" ]]; then
  "${root}/scripts/jbr/with-jbr-25.sh" java -cp "${launcher}" \
    net.chonkbase.chonkcraft.launcher.ReleaseNotesTool fetch \
    "${CHONKCRAFT_RELEASE_HISTORY_CATALOG}" "${key_dir}/history-home" "${previous_notes}"
fi

history_staged="${key_dir}/release-notes.properties"
CHONKCRAFT_VERSION="${version}" \
CHONKCRAFT_RELEASE_PUBLISHED="${published}" \
CHONKCRAFT_RELEASE_TITLE="${CHONKCRAFT_RELEASE_TITLE:-ChonkCraft ${version}}" \
CHONKCRAFT_RELEASE_NOTES="${release_notes}" \
GITHUB_SHA="${revision}" \
  "${root}/scripts/jbr/with-jbr-25.sh" java -cp "${launcher}" \
  net.chonkbase.chonkcraft.launcher.ReleaseNotesTool append \
  "${previous_notes}" "${history_staged}"
notes_sha="$(hash_file "${history_staged}")"
notes_name="chonkcraft-release-notes-${notes_sha}.properties"
notes_relative="releases/release-notes/${notes_name}"
cp "${history_staged}" "${output}/${notes_relative}"
notes_bytes="$(wc -c < "${output}/${notes_relative}" | tr -d ' ')"

{
  printf 'format=chonkcraft-release-3\n'
  printf 'version=%s\n' "${version}"
  printf 'game.url=%s\n' "${game_relative}"
  printf 'game.sha256=%s\n' "${game_sha}"
  printf 'game.bytes=%s\n' "${game_bytes}"
  printf 'source.revision=%s\n' "${revision}"
  printf 'published=%s\n' "${published}"
  printf 'notes=%s\n' "${notes}"
  printf 'notes.url=%s\n' "${notes_relative}"
  printf 'notes.sha256=%s\n' "${notes_sha}"
  printf 'notes.bytes=%s\n' "${notes_bytes}"
} > "${key_dir}/payload.properties"

openssl pkeyutl -sign -rawin -inkey "${key_dir}/private.pem" \
  -in "${key_dir}/payload.properties" -out "${key_dir}/signature.bin"

{
  printf 'format=chonkcraft-signed-release-1\n'
  printf 'key.id=%s\n' "${key_id}"
  printf 'payload=%s\n' "$(single_line_base64 "${key_dir}/payload.properties")"
  printf 'signature=%s\n' "$(single_line_base64 "${key_dir}/signature.bin")"
} > "${output}/latest.properties"

printf '%s\n' "${output}/latest.properties"
printf '%s\n' "${output}/${game_relative}"
printf '%s\n' "${output}/${notes_relative}"
