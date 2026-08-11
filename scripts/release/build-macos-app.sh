#!/usr/bin/env bash
#
# Builds a macOS application bundle.
#
#   scripts/release/build-macos-app.sh            an .app
#   scripts/release/build-macos-app.sh --dmg      an .app and a .dmg
#
# The result is self-contained: jpackage bundles a trimmed JetBrains Runtime,
# so a player needs no Java. It still needs their own Warcraft II data, which
# is not redistributable and is never included.
#
# Developer ID signing is selected automatically when an identity is available.
# CI sets CHONKCRAFT_MAC_SIGNING=require and CHONKCRAFT_MAC_NOTARIZATION=require
# so a release can never silently degrade to an ad-hoc or unstapled artifact.

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib-release.sh"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "A macOS bundle must be built on macOS." >&2
  exit 1
fi

root="$(release_repo_root)"
version="$(release_version)"
want_dmg="${1:-}"

output="${root}/desktop/target/dist/macos"
input="${root}/desktop/target/jpackage-input"
app="${output}/ChonkCraft.app"
icon="${root}/launcher/src/main/resources/icons/chonkcraft.icns"

if [[ ! -s "${icon}" ]]; then
  echo "The ChonkCraft application icon is missing: ${icon}" >&2
  exit 1
fi

release_build_launcher_jar "${root}" >/dev/null
release_stage_input "${root}" "${input}"
runtime="$(release_build_runtime "${root}" mac)"
rm -rf "${app}"
mkdir -p "${output}"

release_java_options mac

"${root}/scripts/jbr/with-jbr-25.sh" jpackage \
  --type app-image \
  --dest "${output}" \
  --name "ChonkCraft" \
  --app-version "1.0.0" \
  --icon "${icon}" \
  --input "${input}" \
  --main-jar chonkcraft-launcher.jar \
  --main-class net.chonkbase.chonkcraft.launcher.Main \
  --vendor chonkbase.net \
  --description "ChonkCraft game launcher" \
  --mac-app-category games \
  --mac-package-identifier net.chonkbase.chonkcraft \
  --mac-package-name "ChonkCraft" \
  --runtime-image "${runtime}" \
  "${JAVA_OPTIONS[@]}"

# Apple notarization examines native code even when it is nested inside a JAR.
# JNA ships both Mac architectures in the shaded game jar, where neither can be
# signed independently. Stage only this bundle's architecture beside the jars,
# remove both embedded copies, and let the launcher pass the staged location to
# the child game JVM.
game_jar="${app}/Contents/app/bootstrap-game.jar"
case "$(uname -m)" in
  arm64|aarch64) jna_resource='com/sun/jna/darwin-aarch64/libjnidispatch.jnilib' ;;
  x86_64|amd64) jna_resource='com/sun/jna/darwin-x86-64/libjnidispatch.jnilib' ;;
  *) echo "Unsupported macOS architecture: $(uname -m)" >&2; exit 1 ;;
esac
jna_stage="$(mktemp -d "${output}/jna-stage.XXXXXX")"
(
  cd "${jna_stage}"
  "${root}/scripts/jbr/with-jbr-25.sh" jar xf "${game_jar}" "${jna_resource}"
)
cp "${jna_stage}/${jna_resource}" "${app}/Contents/app/libjnidispatch.jnilib"
chmod 755 "${app}/Contents/app/libjnidispatch.jnilib"
set +e
zip -dq "${game_jar}" 'com/sun/jna/darwin-*/libjnidispatch.jnilib'
zip_status=$?
set -e
if [[ "${zip_status}" -ne 0 && "${zip_status}" -ne 12 ]]; then
  echo "Failed to remove embedded macOS JNA libraries (zip exit ${zip_status})." >&2
  exit "${zip_status}"
fi
if unzip -Z1 "${game_jar}" \
    | grep -E '^com/sun/jna/darwin-.*/libjnidispatch\.jnilib$' >/dev/null; then
  echo "The packaged game jar still contains an unsigned macOS JNA library." >&2
  exit 1
fi

# jpackage adds a microphone usage key to every macOS app, including
# playback-only ones. Leaving it lets the Java Sound backend raise a consent
# dialog the game has no use for. Removing it invalidates the ad-hoc seal, so
# the bundle is resigned.
plist="${app}/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $(release_marketing_version)" \
  "${plist}"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $(release_macos_build_version)" \
  "${plist}"
/usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName ChonkCraft" "${plist}" \
  2>/dev/null || /usr/libexec/PlistBuddy -c \
    "Add :CFBundleDisplayName string ChonkCraft" "${plist}"
/usr/libexec/PlistBuddy -c "Set :CFBundleName ChonkCraft" "${plist}" \
  2>/dev/null || /usr/libexec/PlistBuddy -c \
    "Add :CFBundleName string ChonkCraft" "${plist}"
/usr/libexec/PlistBuddy -c \
  "Set :NSHumanReadableCopyright © 2026 chonkbase.net" "${plist}" \
  2>/dev/null || /usr/libexec/PlistBuddy -c \
    "Add :NSHumanReadableCopyright string © 2026 chonkbase.net" "${plist}"
if /usr/libexec/PlistBuddy -c "Print :NSMicrophoneUsageDescription" "${plist}" >/dev/null 2>&1; then
  /usr/libexec/PlistBuddy -c "Delete :NSMicrophoneUsageDescription" "${plist}"
fi

signing_request="${CHONKCRAFT_MAC_SIGNING:-auto}"
identity="${MAC_SIGNING_IDENTITY:-}"
if [[ "${signing_request}" != "ad-hoc" && -z "${identity}" ]]; then
  identity="$(security find-identity -v -p codesigning \
    | awk -F'"' '/Developer ID Application:/{print $2; exit}')"
fi

if [[ -n "${identity}" && "${signing_request}" != "ad-hoc" ]]; then
  export MAC_SIGNING_IDENTITY="${identity}"
  printf '%s\n' developer-id > "${app}/Contents/app/SIGNING.txt"
  "${root}/scripts/release/sign-macos-app.sh" "${app}"
  signing_mode=developer-id
else
  if [[ "${signing_request}" == "require" ]]; then
    echo "Developer ID signing was required, but no identity is available." >&2
    exit 1
  fi
  printf '%s\n' ad-hoc > "${app}/Contents/app/SIGNING.txt"
  codesign --force --deep --sign - "${app}" >/dev/null
  codesign --verify --deep --strict --verbose=2 "${app}"
  signing_mode=ad-hoc
fi

echo "${app}"

if [[ "${want_dmg}" == "--dmg" ]]; then
  dmg="${output}/ChonkCraft-${version}.dmg"
  staging="${output}/dmg-staging"
  rm -f "${dmg}"
  rm -rf "${staging}"
  mkdir -p "${staging}"
  ditto "${app}" "${staging}/ChonkCraft.app"
  ln -s /Applications "${staging}/Applications"
  dmg_created=false
  for attempt in 1 2 3; do
    if hdiutil create -volname ChonkCraft -srcfolder "${staging}" \
        -ov -format UDZO "${dmg}"; then
      dmg_created=true
      break
    fi
    echo "DMG creation attempt ${attempt} failed; retrying." >&2
    sleep 5
  done
  if [[ "${dmg_created}" != true ]]; then
    echo "Unable to create the DMG after three attempts." >&2
    exit 1
  fi
  hdiutil verify "${dmg}"

  dmg_codesign=(--force --sign "${identity}")
  if [[ "${signing_mode}" == developer-id ]]; then
    dmg_codesign=(--force --timestamp --sign "${identity}")
    if [[ -n "${MAC_KEYCHAIN_PATH:-}" ]]; then
      dmg_codesign+=(--keychain "${MAC_KEYCHAIN_PATH}")
    fi
  else
    dmg_codesign=(--force --sign -)
  fi
  codesign "${dmg_codesign[@]}" "${dmg}"
  codesign --verify --verbose=2 "${dmg}"

  notary_args=()
  api_values=(
    "${APP_STORE_CONNECT_API_KEY_PATH:-}"
    "${APP_STORE_CONNECT_KEY_ID:-}"
    "${APP_STORE_CONNECT_ISSUER_ID:-}"
  )
  api_count=0
  for value in "${api_values[@]}"; do
    [[ -n "${value}" ]] && ((api_count += 1))
  done
  if (( api_count == 3 )); then
    [[ -f "${APP_STORE_CONNECT_API_KEY_PATH}" ]] || {
      echo "App Store Connect private key not found: ${APP_STORE_CONNECT_API_KEY_PATH}" >&2
      exit 1
    }
    notary_args=(
      --key "${APP_STORE_CONNECT_API_KEY_PATH}"
      --key-id "${APP_STORE_CONNECT_KEY_ID}"
      --issuer "${APP_STORE_CONNECT_ISSUER_ID}"
    )
  elif (( api_count != 0 )); then
    echo "App Store Connect notarization requires key path, key ID, and issuer ID." >&2
    exit 1
  elif [[ -n "${APPLE_ID:-}" && -n "${APPLE_APP_PASSWORD:-}" && -n "${APPLE_TEAM_ID:-}" ]]; then
    notary_args=(
      --apple-id "${APPLE_ID}"
      --password "${APPLE_APP_PASSWORD}"
      --team-id "${APPLE_TEAM_ID}"
    )
  fi

  notarization_mode=skipped
  if [[ "${signing_mode}" == developer-id && "${#notary_args[@]}" -gt 0 ]]; then
    result="${output}/notary-result.json"
    xcrun notarytool submit "${dmg}" "${notary_args[@]}" \
      --wait --output-format json | tee "${result}"
    status="$(python3 -c \
      'import json,sys; print(json.load(open(sys.argv[1]))["status"])' "${result}")"
    submission_id="$(python3 -c \
      'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${result}")"
    if [[ "${status}" != Accepted ]]; then
      xcrun notarytool log "${submission_id}" "${notary_args[@]}" || true
      echo "Apple notarization did not accept the DMG: ${status}" >&2
      exit 1
    fi
    xcrun stapler staple "${dmg}"
    xcrun stapler validate "${dmg}"
    spctl --assess --type open --context context:primary-signature \
      --verbose=2 "${dmg}"
    notarization_mode=stapled
  elif [[ "${CHONKCRAFT_MAC_NOTARIZATION:-auto}" == "require" ]]; then
    echo "Notarization was required, but complete credentials are unavailable." >&2
    exit 1
  else
    echo "Notarization credentials unavailable; the local DMG was not notarized." >&2
  fi

  echo "${dmg}"
  echo "signing=${signing_mode}"
  echo "notarization=${notarization_mode}"
fi
