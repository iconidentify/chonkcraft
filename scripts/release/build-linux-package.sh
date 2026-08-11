#!/usr/bin/env bash
#
# Builds a Linux application image, and optionally a .deb or .rpm.
#
#   scripts/release/build-linux-package.sh          an app image
#   scripts/release/build-linux-package.sh --deb    also a .deb
#   scripts/release/build-linux-package.sh --rpm    also an .rpm
#
# Must run on Linux: jpackage builds for the platform it runs on, so there is
# no cross-compiling a Linux package from macOS.

set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib-release.sh"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "A Linux package must be built on Linux." >&2
  exit 1
fi

root="$(release_repo_root)"
version="$(release_version)"
package_version="$(release_package_version)"
extra="${1:-}"

output="${root}/desktop/target/dist/linux"
input="${root}/desktop/target/jpackage-input"
icon="${root}/launcher/src/main/resources/icons/chonkcraft.png"

if [[ ! -s "${icon}" ]]; then
  echo "The ChonkCraft Linux icon is missing: ${icon}" >&2
  exit 1
fi

release_build_launcher_jar "${root}" >/dev/null
release_stage_input "${root}" "${input}"
runtime="$(release_build_runtime "${root}" linux)"
rm -rf "${output}"
mkdir -p "${output}"

release_java_options linux

"${root}/scripts/jbr/with-jbr-25.sh" jpackage \
  --type app-image \
  --dest "${output}" \
  --name "chonkcraft" \
  --app-version "${package_version}" \
  --icon "${icon}" \
  --input "${input}" \
  --main-jar chonkcraft-launcher.jar \
  --main-class net.chonkbase.chonkcraft.launcher.Main \
  --vendor chonkbase.net \
  --description "Launcher for ChonkCraft and its Warcraft II graphics packs" \
  --runtime-image "${runtime}" \
  "${JAVA_OPTIONS[@]}"

echo "${output}/chonkcraft"

if [[ "${extra}" == "--deb" || "${extra}" == "--rpm" ]]; then
  type="${extra#--}"
  "${root}/scripts/jbr/with-jbr-25.sh" jpackage \
    --type "${type}" \
    --dest "${output}" \
    --name "chonkcraft" \
    --app-version "${package_version}" \
    --app-image "${output}/chonkcraft" \
    --vendor chonkbase.net \
    --description "Launcher for ChonkCraft and its Warcraft II graphics packs" \
    --linux-menu-group Game \
    --linux-shortcut
  echo "${output}"/chonkcraft*."${type}"
fi
