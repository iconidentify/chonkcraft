#!/usr/bin/env bash
#
# Builds a Windows application image, and optionally an .msi.
#
#   scripts/release/build-windows-package.sh         an app image
#   scripts/release/build-windows-package.sh --msi   also an installer
#
# Must run on Windows, under a bash such as Git Bash. jpackage builds for the
# platform it runs on. An .msi additionally needs the WiX toolset on PATH,
# which is why the installer is opt-in rather than the default.

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib-release.sh"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *)
    echo "A Windows package must be built on Windows." >&2
    exit 1
    ;;
esac

root="$(release_repo_root)"
version="$(release_version)"
package_version="$(release_package_version)"
extra="${1:-}"

output="${root}/desktop/target/dist/windows"
input="${root}/desktop/target/jpackage-input"
icon="${root}/launcher/src/main/resources/icons/chonkcraft.ico"

if [[ ! -s "${icon}" ]]; then
  echo "The ChonkCraft Windows icon is missing: ${icon}" >&2
  exit 1
fi

if [[ "${extra}" != "--msi-from-app" ]]; then
  release_build_launcher_jar "${root}" >/dev/null
  release_stage_input "${root}" "${input}"
  runtime="$(release_build_runtime "${root}" windows)"
  rm -rf "${output}"
  mkdir -p "${output}"

  release_java_options windows

  "${root}/scripts/jbr/with-jbr-25.sh" jpackage \
    --type app-image \
    --dest "${output}" \
    --name "ChonkCraft" \
    --app-version "${package_version}" \
    --icon "${icon}" \
    --input "${input}" \
    --main-jar chonkcraft-launcher.jar \
    --main-class net.chonkbase.chonkcraft.launcher.Main \
    --vendor chonkbase.net \
    --description "Launcher for ChonkCraft and its Warcraft II graphics packs" \
    --runtime-image "${runtime}" \
    "${JAVA_OPTIONS[@]}"

  echo "${output}/ChonkCraft"
fi

if [[ "${extra}" == "--msi" || "${extra}" == "--msi-from-app" ]]; then
  app_image="${output}/ChonkCraft"
  [[ -x "${app_image}/ChonkCraft.exe" || -f "${app_image}/ChonkCraft.exe" ]] || {
    echo "The Windows application image is missing: ${app_image}" >&2
    exit 1
  }
  "${root}/scripts/jbr/with-jbr-25.sh" jpackage \
    --type msi \
    --dest "${output}" \
    --name "ChonkCraft" \
    --app-version "${package_version}" \
    --app-image "${app_image}" \
    --vendor chonkbase.net \
    --win-dir-chooser \
    --win-menu \
    --win-menu-group Games \
    --win-shortcut
  echo "${output}"/*.msi
fi
