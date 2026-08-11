#!/bin/bash
#
# Traces one map in both engines and reports the first cycle they disagree on.
#
# This is the legacy comparison loop. It requires the traced reference engine,
# its data checkout, and a compiled ChonkCraft engine; the checks below name any
# missing prerequisite directly.
#
# Usage:
#   scripts/parity-sweep.sh <map> [cycles]
#   scripts/parity-sweep.sh maps/demo/demo03 300
#   scripts/parity-sweep.sh "maps/skirmish/(3)critter-attack" 900
#   scripts/parity-sweep.sh campaigns/human/level01h 900
#
# Campaign missions trace too: the trace build steps past the briefing menu
# that used to wall them off, and EngineTrace loads a campaigns/ path as the
# full mission, triggers and all, because that is what upstream's command
# line runs for one.
#
# The map is named the way the game names it, without the .smp.gz. Traces are
# written beside each other under $PARITY_DIR (default /tmp/chonkcraft-parity) so a
# second run of the same map overwrites rather than accumulating.
#
# Wants, and will tell you if it has not got:
#   - upstream LegacyEngine built with tools/legacyEngine-trace.patch applied, at
#     $LEGACY_ENGINE_BUILD (default ~/src/legacyEngine/build/legacyEngine)
#   - the ChonkCraft data at $CHONKCRAFT_DATA (default ~/src/chonkcraft-data)
#   - this port compiled: mvn -o test-compile -pl engine
set -u

map="${1:-}"
cycles="${2:-300}"
if [ -z "$map" ]; then
    echo "usage: $0 <map> [cycles]" >&2
    exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
out="${PARITY_DIR:-/tmp/chonkcraft-parity}"
legacyEngine="${LEGACY_ENGINE_BUILD:-$HOME/src/legacyEngine/build/legacyEngine}"
data="${CHONKCRAFT_DATA:-$HOME/src/chonkcraft-data}"
install="${WC2_INSTALL:-$HOME/src/wc2-install}"

for needed in "$legacyEngine" "$data"; do
    if [ ! -e "$needed" ]; then
        echo "$0: required comparison input is missing: $needed" >&2
        exit 1
    fi
done

mkdir -p "$out"
tag=$(echo "$map" | tr '/()' '___')
upstream="$out/u-$tag.txt"
ours="$out/j-$tag.txt"

# The upstream trace is cached. The binary is deterministic -- the whole
# point of the harness -- so the same binary over the same data for the same
# window writes the same file, and re-running it on every sweep was the
# single biggest cost of an iteration. The key covers everything that
# decides the trace's content: the binary itself (which embeds the trace
# patch), the map's own files, a manifest of the shipped scripts the map
# setup loads, and the window. Change any of them and the key changes, so a
# stale entry is never *found*, not merely distrusted.
#
#   PARITY_NO_CACHE=1      ignore the cache entirely, write nothing to it
#   PARITY_VERIFY_CACHE=1  use the cache AND re-trace, and fail loudly if
#                          the two disagree -- the audit that keeps the
#                          cache honest, worth running after rebuilding
#                          the binary or editing the data
run_upstream() {
    # LEGACY_ENGINE_TRACE names the file; _CYCLES bounds the run and _EXIT stops
    # it there rather than leaving the process up. It exits non-zero on the
    # way out and that is expected -- the trace is already written and
    # complete -- so the call is wrapped to keep the shell's own job report
    # for it off the terminal. That report is written by the shell that
    # waited on the process, to *its* own stderr, so redirecting the child's
    # is not enough -- this script's stderr is put aside for the duration
    # and restored after.
    exec 3>&2 2>/dev/null
    LEGACY_ENGINE_TRACE="$1" \
    LEGACY_ENGINE_TRACE_CYCLES="$cycles" \
    LEGACY_ENGINE_TRACE_EXIT=1 \
    SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
        timeout 300 "$legacyEngine" -d "$data" -r -l -W 800x600 "$map.smp.gz" >/dev/null 2>&1
    exec 2>&3 3>&-
}

cachedir="$out/upstream-cache"
mkdir -p "$cachedir"
binhash=$(sha256sum "$legacyEngine" | cut -c1-16)
maphash=$(cat "$data/$map.smp.gz" "$data/$map".sms* "$data/$map"_c*.sms \
        2>/dev/null | sha256sum | cut -c1-16)
scripthash=$(find "$data/scripts" -type f -printf '%P %s %T@\n' 2>/dev/null \
        | sort | sha256sum | cut -c1-16)
cached="$cachedir/$tag-$cycles-$binhash-$maphash-$scripthash.txt"

if [ -z "${PARITY_NO_CACHE:-}" ] && [ -s "$cached" ]; then
    cp "$cached" "$upstream"
    if [ -n "${PARITY_VERIFY_CACHE:-}" ]; then
        run_upstream "$upstream.fresh"
        if ! cmp -s "$upstream" "$upstream.fresh"; then
            echo "$map: CACHED UPSTREAM TRACE DISAGREES WITH A FRESH ONE --" \
                 "the cache key missed something; kept both:" \
                 "$cached and $upstream.fresh" >&2
            exit 1
        fi
        rm -f "$upstream.fresh"
    fi
else
    run_upstream "$upstream"
    if [ -z "${PARITY_NO_CACHE:-}" ] && [ -s "$upstream" ]; then
        cp "$upstream" "$cached"
    fi
fi

# And this port, from the same map through EngineTrace.
classes="engine/target/classes:engine/target/test-classes:data/target/classes"
classes="$classes:assetpack/target/classes:runtime/target/classes"
(cd "$root" && scripts/jbr/with-jbr-25.sh java -cp "$classes" \
    -Dwc2.install.dir="$install" \
    -Djava.awt.headless=true \
    net.chonkbase.chonkcraft.engine.parity.EngineTrace "$map" "$cycles" "$ours") >/dev/null 2>&1

if [ ! -s "$upstream" ] || [ ! -s "$ours" ]; then
    size() { if [ -f "$1" ]; then wc -c <"$1"; else echo 0; fi; }
    echo "$map: one side wrote nothing -- upstream $(size "$upstream") bytes," \
         "ours $(size "$ours") bytes" >&2
    exit 1
fi

echo -n "$map: "
(cd "$root" && python3 scripts/diff-determinism.py --all "$upstream" "$ours" 2>&1 | tail -1)
