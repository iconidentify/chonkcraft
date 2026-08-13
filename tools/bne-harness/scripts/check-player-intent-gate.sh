#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
REPLAY_ROOT=${BNE_REPLAY_ROOT:-$ROOT/../.chonkcraft-replay-evidence/replay-pack-1}
PACK=${CHONKCRAFT_ASSET_PACK:-$HOME/.chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack}
CORPUS_SHA256=306f7de5d8675d828f8a086fad3494e2dc2f25d0605df5175fc75010fc773673

if [ ! -d "$REPLAY_ROOT" ]; then
    echo "player-intent gate: replay corpus not found: $REPLAY_ROOT" >&2
    echo "Set BNE_REPLAY_ROOT to the authenticated 27-file corpus." >&2
    exit 1
fi
if [ ! -f "$PACK" ]; then
    echo "player-intent gate: BNE ChonkPack not found: $PACK" >&2
    echo "Set CHONKCRAFT_ASSET_PACK to the authenticated pack." >&2
    exit 1
fi

REPORT=$(mktemp "${TMPDIR:-/tmp}/chonkcraft-player-intent.XXXXXX.json")
trap 'rm -f "$REPORT"' EXIT HUP INT TERM

python3 "$ROOT/tools/bne-harness/scripts/bne_replay.py" corpus \
    --expect-corpus-sha256 "$CORPUS_SHA256" "$REPLAY_ROOT" > "$REPORT"
python3 - "$REPORT" <<'PY'
import json
import sys

report = json.load(open(sys.argv[1], encoding="utf-8"))
expected = {
    "replay_count": 27,
    "embedded_command_count": 168788,
    "commands_with_multi_unit_selection": 22518,
}
for name, wanted in expected.items():
    if report.get(name) != wanted:
        raise SystemExit(
            f"player-intent gate: {name}={report.get(name)!r}; expected {wanted}")
if report.get("selection_sizes", {}).get("9") != 593:
    raise SystemExit("player-intent gate: nine-unit selection evidence changed")
print("retail replay corpus: PASS — 27 replays, 168788 events, 22518 group events")
PY

cd "$ROOT"
mvn -q -pl desktop -am \
    -Dchonkcraft.pack="$PACK" \
    -Dtest=PlayerIntentJournalTest,PlayerOrderDeliveryTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
echo "Java ordered-selection and fulfillment gates: PASS"
