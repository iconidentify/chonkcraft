#!/usr/bin/env bash
#
# Shared release helpers.

set -euo pipefail

release_repo_root() {
  cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd
}

# Public release identifier. The native bundle uses a private jpackage seed
# version and then receives the real marketing/build values before signing, so
# an honest 0.x beta can be shipped without lying in the filename or release.
release_version() {
  echo "${CHONKCRAFT_VERSION:-0.1.1-beta11}"
}

release_marketing_version() {
  local version
  version="$(release_version)"
  version="${version%%-*}"
  if [[ ! "${version}" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]]; then
    echo "Invalid macOS marketing version: ${version}" >&2
    return 1
  fi
  echo "${version}"
}

# jpackage's Linux and Windows backends accept only a numeric application
# version even when the public release is a beta. Keep the honest public
# version in filenames, the launcher, and the catalog while using the numeric
# core for native package metadata.
release_package_version() {
  release_marketing_version
}

release_macos_build_version() {
  local build="${GITHUB_RUN_NUMBER:-1}"
  [[ "${build}" =~ ^[1-9][0-9]*$ ]] || build=1
  echo "${build}"
}

# Builds the launcher and game, then returns the launcher's shaded jar.
release_build_launcher_jar() {
  local root="$1"
  local jar="${root}/launcher/target/chonkcraft-launcher-0.1.0-SNAPSHOT-app.jar"
  local game="${root}/desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"

  if [[ "${CHONKCRAFT_SKIP_BUILD:-0}" != "1" || ! -f "${jar}" || ! -f "${game}" ]]; then
    "${root}/scripts/jbr/with-jbr-25.sh" mvn -q -f "${root}/pom.xml" \
      -pl launcher,desktop -am -DskipTests package
  fi
  if [[ ! -f "${jar}" ]]; then
    echo "The launcher jar was not produced: ${jar}" >&2
    return 1
  fi
  if [[ ! -f "${game}" ]]; then
    echo "The game jar was not produced: ${game}" >&2
    return 1
  fi
  echo "${jar}"
}

# Stages the durable launcher and self-contained bootstrap game.
release_stage_input() {
  local root="$1"
  local input="$2"
  local launcher="${root}/launcher/target/chonkcraft-launcher-0.1.0-SNAPSHOT-app.jar"
  local game="${root}/desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"

  rm -rf "${input}"
  mkdir -p "${input}"
  cp "${launcher}" "${input}/chonkcraft-launcher.jar"
  cp "${game}" "${input}/bootstrap-game.jar"
  printf '%s\n' "$(release_version)" > "${input}/bootstrap-version.txt"
}

# Builds the small runtime shared by the launcher and the replaceable game.
#
# jpackage's generated runtime deliberately omits bin/java because its native
# launcher loads libjvm directly. ChonkCraft must keep bin/java: the durable
# launcher starts the current game jar in a separate process so the jar can be
# replaced by an update without replacing the running launcher.
release_build_runtime() {
  local root="$1"
  local platform="$2"
  local runtime="${root}/desktop/target/jpackage-runtime-${platform}"
  local launcher="${root}/launcher/target/chonkcraft-launcher-0.1.0-SNAPSHOT-app.jar"
  local game="${root}/desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"
  local modules

  # Derive the runtime from the two artifacts it must execute. A handwritten
  # list previously omitted java.net.http: the packaged launcher still opened,
  # but every online lobby worker died before its first request. jdeps makes a
  # new JDK dependency fail at package time instead of on a player's machine.
  modules="$("${root}/scripts/jbr/with-jbr-25.sh" jdeps \
    --ignore-missing-deps \
    --multi-release 25 \
    --print-module-deps \
    "${launcher}" "${game}")"
  if [[ ! "${modules}" =~ ^[a-zA-Z0-9._,-]+$ ]]; then
    echo "Could not derive a safe runtime module set: ${modules}" >&2
    return 1
  fi
  # TLS can select the EC provider at runtime, which static bytecode analysis
  # cannot see. Keep it explicitly in every self-contained distribution.
  case ",${modules}," in
    *,jdk.crypto.ec,*) ;;
    *) modules="${modules},jdk.crypto.ec" ;;
  esac

  rm -rf "${runtime}"
  "${root}/scripts/jbr/with-jbr-25.sh" jlink \
    --add-modules "${modules}" \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --output "${runtime}"

  # Exercise the exact trimmed runtime against the exact game artifact. A
  # launcher render cannot prove that lazy networking classes are loadable.
  "${runtime}/bin/java" -cp "${game}" \
    net.chonkbase.chonkcraft.desktop.RuntimeCapabilityProbe >&2
  echo "${runtime}"
}

# The JVM flags the game needs at runtime, appended to an array named by the
# caller. Written this way rather than returning a string because macOS ships
# bash 3.2, which has neither mapfile nor nameref, and word-splitting a string
# of flags would break on any that contained a space.
#
# PlatformFullscreen reaches com.apple.eawt reflectively for true macOS
# fullscreen; without the open it falls soft to an undecorated window. The
# launcher also builds and verifies graphics packs, so its heap ceiling is
# larger than the game process it starts.
release_java_options() {
  local platform="$1"
  JAVA_OPTIONS=(
    --java-options -Xms256m
    --java-options -Xmx6144m
    --java-options "-Dchonkcraft.version=$(release_version)"
  )
  if [[ "${platform}" == "mac" ]]; then
    JAVA_OPTIONS+=(
      --java-options --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED
      --java-options '-Dchonkcraft.packaged.native.dir=$APPDIR'
      --java-options '-Djna.boot.library.path=$APPDIR'
      --java-options -Djna.nounpack=true
    )
  fi
}
