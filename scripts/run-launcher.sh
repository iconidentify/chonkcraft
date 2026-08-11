#!/usr/bin/env bash
#
# Builds and opens the first-class launcher with the current self-contained game JAR.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
launcher="${repo_root}/launcher/target/chonkcraft-launcher-0.1.0-SNAPSHOT-app.jar"
game="${repo_root}/desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"

if [[ "${CHONKCRAFT_SKIP_BUILD:-0}" != "1" || ! -f "${launcher}" || ! -f "${game}" ]]; then
  "${repo_root}/scripts/jbr/with-jbr-25.sh" mvn -q -f "${repo_root}/pom.xml" \
    -pl launcher,desktop -am -DskipTests package
fi
if modified="$(stat -f %m "${game}" 2>/dev/null)"; then
  :
else
  modified="$(stat -c %Y "${game}")"
fi
bootstrap_version="${CHONKCRAFT_VERSION:-development-${modified}}"
update_options=()
if [[ "${CHONKCRAFT_SKIP_UPDATE:-0}" == "1" ]]; then
  update_options+=("-Dchonkcraft.update.check=false")
fi

exec "${repo_root}/scripts/jbr/with-jbr-25.sh" java \
  -Xms256m -Xmx6144m \
  "${update_options[@]}" \
  -Dchonkcraft.version="${CHONKCRAFT_VERSION:-Development}" \
  -Dchonkcraft.bootstrap.game="${game}" \
  -Dchonkcraft.bootstrap.version="${bootstrap_version}" \
  -jar "${launcher}" "$@"
