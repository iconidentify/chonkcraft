#!/usr/bin/env bash
# Wraps the jpackage Linux app image in the same pinned Type-2 AppImage format
# used by the sister project.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: build-linux-appimage.sh <appimagetool-path>" >&2
  exit 64
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${root}/scripts/release/lib-release.sh"
version="$(release_version)"
appimagetool="$1"
linux_dist="${root}/desktop/target/dist/linux"
installer_dist="${root}/desktop/target/dist/installers"
source_app="${linux_dist}/chonkcraft"
appdir="${linux_dist}/ChonkCraft.AppDir"
output="${installer_dist}/ChonkCraft-${version}-linux-x64.AppImage"
icon="${root}/launcher/src/main/resources/icons/chonkcraft.png"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "An AppImage must be built on Linux." >&2
  exit 1
fi
if [[ ! -x "${appimagetool}" ]]; then
  echo "appimagetool is not executable: ${appimagetool}" >&2
  exit 1
fi

"${root}/scripts/release/build-linux-package.sh" >/dev/null
[[ -x "${source_app}/bin/chonkcraft" ]] || {
  echo "jpackage launcher is missing: ${source_app}/bin/chonkcraft" >&2
  exit 1
}
[[ -x "${source_app}/lib/runtime/bin/java" ]] || {
  echo "bundled Java runtime is missing from the Linux app image" >&2
  exit 1
}

rm -rf "${appdir}"
mkdir -p "${appdir}" "${installer_dist}"
cp -R "${source_app}"/. "${appdir}"/

cat > "${appdir}/AppRun" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${here}/bin/chonkcraft" "$@"
EOF
chmod +x "${appdir}/AppRun"

cat > "${appdir}/ChonkCraft.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=ChonkCraft
Comment=Launch ChonkCraft and manage its graphics packs
Exec=chonkcraft
Icon=ChonkCraft
Categories=Game;StrategyGame;
Terminal=false
EOF
cp "${icon}" "${appdir}/ChonkCraft.png"

rm -f "${output}" "${output}.sha256"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 VERSION="${version}" \
  "${appimagetool}" "${appdir}" "${output}"
chmod +x "${output}"
sha256sum "${output}" > "${output}.sha256"
printf '%s\n' "${output}"
