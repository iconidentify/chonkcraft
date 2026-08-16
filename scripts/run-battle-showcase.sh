#!/usr/bin/env bash
#
# Launch a deterministic, camera-ready battle using the normal game engine.
#
#   scripts/run-battle-showcase.sh                 # 480 units, visual
#   scripts/run-battle-showcase.sh 400             # 400 units, visual
#   scripts/run-battle-showcase.sh --benchmark     # 400 units, 1800 cycles
#   scripts/run-battle-showcase.sh --benchmark 600 3600

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-}"

if [[ "${mode}" != "--benchmark" ]]; then
  export CHONKCRAFT_SHOWCASE=1
  export CHONKCRAFT_SHOWCASE_UNITS="${1:-480}"
  exec "${repo_root}/scripts/run-game.sh"
fi

units="${2:-400}"
cycles="${3:-1800}"
classpath_file="${repo_root}/desktop/target/runtime-classpath.txt"

"${repo_root}/scripts/jbr/with-jbr-25.sh" mvn -q -f "${repo_root}/pom.xml" \
  -pl desktop -am -DskipTests install
"${repo_root}/scripts/jbr/with-jbr-25.sh" mvn -q -f "${repo_root}/desktop/pom.xml" \
  dependency:build-classpath "-Dmdep.outputFile=${classpath_file}"

args=()
if [[ -n "${CHONKCRAFT_ASSET_PACK:-}" ]]; then
  args+=("-Dchonkcraft.pack=${CHONKCRAFT_ASSET_PACK}")
fi
if [[ -n "${WC2_INSTALL_DIR:-}" ]]; then
  args+=("-Dwc2.install.dir=${WC2_INSTALL_DIR}")
fi

exec "${repo_root}/scripts/jbr/with-jbr-25.sh" java \
  -cp "${repo_root}/desktop/target/classes:$(cat "${classpath_file}")" \
  "${args[@]}" \
  net.chonkbase.chonkcraft.desktop.BattleShowcaseBenchmark \
  "${units}" "${cycles}"
