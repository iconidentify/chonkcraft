#!/usr/bin/env bash
# Exercise native asset lookup on a genuinely case-sensitive macOS volume.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
image="${TMPDIR:-/tmp}/chonkcraft-case-sensitivity.sparseimage"
volume="/Volumes/ChonkCraftCaseCheck"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "The current filesystem already provides the required case semantics."
    exit 0
fi

install_dir="${WC2_INSTALL_DIR:-}"
if [[ -z "$install_dir" || ! -d "$install_dir" ]]; then
    echo "Set WC2_INSTALL_DIR to your Warcraft II installation." >&2
    exit 2
fi

cleanup() {
    hdiutil detach "$volume" -quiet 2>/dev/null || true
    command rm -f "$image"
}
trap cleanup EXIT

command rm -f "$image"
hdiutil detach "$volume" -quiet 2>/dev/null || true
hdiutil create -size 3g -fs "Case-sensitive APFS" \
    -volname "ChonkCraftCaseCheck" -type SPARSE "$image" >/dev/null
hdiutil attach "$image" >/dev/null

: > "${volume}/probe-lower"
if [[ -e "${volume}/PROBE-LOWER" ]]; then
    echo "Created volume is not case-sensitive." >&2
    exit 1
fi

mkdir -p "${volume}/install"
rsync -a --exclude='cd/' "${install_dir}/" "${volume}/install/"

"${repo_root}/scripts/run-tests.sh" -B \
    -Dwc2.install.dir="${volume}/install"

echo "Case-sensitive native asset lookup PASS."
