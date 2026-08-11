#!/usr/bin/env bash
#
# Launch the port against a real Warcraft II installation.
#
#   scripts/run-game.sh                 first map found
#   scripts/run-game.sh ALAMO.PUD       a specific map
#
# Configure the sources once via the environment:
#   CHONKCRAFT_ASSET_PACK  an asset pack built by scripts/build-asset-pack.sh
#   WC2_INSTALL_DIR    the game directory containing DATA/MAINDAT.WAR
#
# A pack wins when one is named or is lying beside the installation as
# chonkcraft.chonkpack, because that is what a player will have. Set only
# WC2_INSTALL_DIR to read the 1995 files directly, which is what the tests do.
#
# Set CHONKCRAFT_SKIP_BUILD=1 to reuse the last build.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
map="${1:-}"
classpath_file="${repo_root}/desktop/target/runtime-classpath.txt"

if [[ "${CHONKCRAFT_SKIP_BUILD:-0}" != "1" || ! -f "${classpath_file}" ]]; then
  # -pl desktop -am builds desktop and everything it depends on. The classpath
  # is written out rather than passed to exec:java, because exec:java on a
  # multi-module reactor runs for every module, parent included.
  "${repo_root}/scripts/jbr/with-jbr-25.sh" mvn -q -f "${repo_root}/pom.xml" \
    -pl desktop -am -DskipTests install
  "${repo_root}/scripts/jbr/with-jbr-25.sh" mvn -q -f "${repo_root}/desktop/pom.xml" \
    dependency:build-classpath "-Dmdep.outputFile=${classpath_file}"
fi

args=()
if [[ -n "${CHONKCRAFT_ASSET_PACK:-}" ]]; then
  args+=("-Dchonkcraft.pack=${CHONKCRAFT_ASSET_PACK}")
fi
if [[ -n "${WC2_INSTALL_DIR:-}" ]]; then
  args+=("-Dwc2.install.dir=${WC2_INSTALL_DIR}")
fi
if [[ -n "${map}" ]]; then
  args+=("-Dchonkcraft.map=${map}")
fi

# PlatformFullscreen reaches com.apple.eawt reflectively for true macOS
# fullscreen. Without this it fails soft to the undecorated fallback, same as
# ChonkBlocker, which passes the equivalent flag in its surefire argLine.
jvm_flags=()
if [[ "$(uname -s)" == "Darwin" ]]; then
  jvm_flags+=("--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED")
fi

exec "${repo_root}/scripts/jbr/with-jbr-25.sh" java \
  "${jvm_flags[@]}" \
  -cp "${repo_root}/desktop/target/classes:$(cat "${classpath_file}")" \
  "${args[@]}" \
  net.chonkbase.chonkcraft.desktop.Main
