#!/usr/bin/env bash
#
# Smoke tests the real packaged launcher and the replaceable game it starts.
#
# Give it an existing verified pack when possible:
#
#   CHONKCRAFT_ASSET_PACK=/path/to/chonkcraft.chonkpack \
#     scripts/release/verify-macos-app.sh desktop/target/dist/macos/ChonkCraft.app
#
# A raw WC2_INSTALL_DIR also works, but must first be converted and verified,
# so that lane takes longer.

set -euo pipefail

app="${1:-}"
if [[ -z "${app}" || ! -d "${app}" ]]; then
  echo "usage: $0 <path to ChonkCraft.app>" >&2
  exit 2
fi

source="${CHONKCRAFT_ASSET_PACK:-${WC2_INSTALL_DIR:-}}"
if [[ -z "${source}" || ! -e "${source}" ]]; then
  echo "Set CHONKCRAFT_ASSET_PACK or WC2_INSTALL_DIR to smoke test the game." >&2
  exit 2
fi

binary="${app}/Contents/MacOS/ChonkCraft"
runtime="${app}/Contents/runtime/Contents/Home/bin/java"
payload="${app}/Contents/app"
home="$(mktemp -d -t chonkcraft-smoke)"
launcher_shot="${home}/launcher.png"
game_shot="${home}/game.png"

echo "Checking the bundle is self-contained..."
[[ -x "${binary}" ]] || { echo "missing launcher: ${binary}" >&2; exit 1; }
[[ -x "${runtime}" ]] || {
  echo "no bundled Java executable; updates could not start" >&2
  exit 1
}
[[ -f "${payload}/bootstrap-game.jar" ]] || {
  echo "the bundled game jar is missing" >&2
  exit 1
}
script_extension=".$(printf '%s%s' l ua)"
if find "${payload}" -type f \( -name "*${script_extension}" -o -name '*.sms' -o -name '*content*.zip' \) \
    | grep -q .; then
  echo "the bundle contains forbidden runtime script content" >&2
  exit 1
fi

if /usr/libexec/PlistBuddy -c "Print :NSMicrophoneUsageDescription" \
    "${app}/Contents/Info.plist" >/dev/null 2>&1; then
  echo "the microphone usage key is still present; it would raise a consent dialog" >&2
  exit 1
fi

for key in CFBundleDisplayName CFBundleName; do
  value="$(/usr/libexec/PlistBuddy -c "Print :${key}" \
    "${app}/Contents/Info.plist")"
  [[ "${value}" == "ChonkCraft" ]] || {
    echo "${key} exposes '${value}' instead of ChonkCraft" >&2
    exit 1
  }
done

echo "Rendering the packaged launcher..."
"${binary}" --home "${home}" --render "${launcher_shot}" >/dev/null
[[ -s "${launcher_shot}" ]] || {
  echo "the packaged launcher did not render" >&2
  exit 1
}

echo "Adding and selecting the graphics pack..."
"${binary}" --home "${home}" --import "${source}" >/dev/null

echo "Launching the bundled game through the launcher..."
CHONKCRAFT_MAP=ALAMO.PUD CHONKCRAFT_SCREENSHOT="${game_shot}" \
  "${binary}" --home "${home}" --launch >/dev/null
if [[ ! -s "${game_shot}" ]]; then
  echo "the packaged game did not render a frame" >&2
  exit 1
fi

echo "Launcher: $(wc -c < "${launcher_shot}" | tr -d ' ') bytes"
echo "Game: $(wc -c < "${game_shot}" | tr -d ' ') bytes"
echo "OK: ${home}"
