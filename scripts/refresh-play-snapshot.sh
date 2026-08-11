#!/usr/bin/env bash
#
# Copies the current build into ~/.chonkcraft-play, the snapshot the game is
# played from.
#
# It exists because doing this by hand went wrong in the way hand-written
# lists always go wrong: the loop named four modules and the project has six,
# so removed modules were never copied and the snapshot kept picking up stale jars
# from lib/ while getting a fresh `engine` on top of it. That combination
# starts up and then dies on the first call into the stale half --
# NoSuchMethodError, from a build where every test passed.
#
# So the module list comes from the pom, and the third-party jars are
# refreshed alongside the classes rather than being left where they landed
# the first time.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="${1:-$HOME/.chonkcraft-play}"

modules=$(grep -o '<module>[^<]*</module>' "$here/pom.xml" \
        | sed 's/<[^>]*>//g')

missing=""
for module in $modules; do
    if [ ! -d "$here/$module/target/classes" ]; then
        missing="$missing $module"
    fi
done
if [ -n "$missing" ]; then
    echo "not built:$missing -- run the build first" >&2
    exit 1
fi

rm -rf "$target/classes" "$target/lib"
mkdir -p "$target/classes" "$target/lib"

for module in $modules; do
    cp -R "$here/$module/target/classes/." "$target/classes/"
done

# Third-party jars only: the project's own modules are already in classes/,
# and an older copy of one of them sitting in lib/ is exactly the trap this
# script is here to avoid.
classpath=$("$here/scripts/jbr/with-jbr-25.sh" mvn -q -pl desktop \
        dependency:build-classpath -Dmdep.outputFile=/dev/stdout \
        -DincludeScope=runtime 2>/dev/null | grep -v '^\[' | tail -1)
printf '%s' "$classpath" | tr ':' '\n' | grep '\.jar$' | while read -r jar; do
    case "$jar" in
        *"/chonkcraft-"*|*"/chonk-"*) continue ;;
    esac
    cp "$jar" "$target/lib/" 2>/dev/null || true
done

echo "snapshot refreshed from: $(echo $modules | tr '\n' ' ')"
echo "  classes: $(find "$target/classes" -name '*.class' | wc -l | tr -d ' ')"
echo "  jars:    $(ls "$target/lib" | wc -l | tr -d ' ')"
