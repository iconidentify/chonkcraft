#!/usr/bin/env bash
#
# Builds one asset pack from a Warcraft II installation.
#
# The pack is what the game loads. Everything it draws, plays and reads is in
# it, in a modern encoding, and nothing outside it is needed at run time.
#
# Usage:
#   scripts/build-asset-pack.sh                       # from $WC2_INSTALL_DIR
#   scripts/build-asset-pack.sh --out /tmp/wc2.chonkpack
#   scripts/build-asset-pack.sh --no-verify           # faster, and worth less
#
# Verification is on by default. It reads every asset back out of the finished
# pack, decodes it, and compares it against what the installation decodes to.
# It roughly doubles the build and it is the reason the format can be trusted.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ -z "${WC2_INSTALL_DIR:-}" ]] && [[ ! " $* " =~ " --install " ]]; then
    cat >&2 <<'EOF'
Set WC2_INSTALL_DIR to your Warcraft II directory, the one holding
DATA/MAINDAT.WAR, or pass --install /path/to/Warcraft.

The extractor reads your own copy of the game. Nothing it produces is
redistributable and nothing in this repository ships game data.
EOF
    exit 2
fi

echo "Building the extractor..."
scripts/jbr/with-jbr-25.sh mvn -q -pl extractor -am install -DskipTests

# The classpath is written once by Maven rather than assembled here, so that
# adding a dependency to the extractor does not silently produce a
# NoClassDefFoundError in this script three weeks later.
classpath_file="extractor/target/extractor-classpath.txt"
scripts/jbr/with-jbr-25.sh mvn -q -pl extractor \
    dependency:build-classpath "-Dmdep.outputFile=$(pwd)/$classpath_file" -DincludeScope=runtime

exec scripts/jbr/with-jbr-25.sh java \
    -Xmx6g \
    -cp "extractor/target/classes:$(cat "$classpath_file")" \
    net.chonkbase.chonkcraft.extract.Main "$@"
