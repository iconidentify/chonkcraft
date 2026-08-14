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
OUTCOME=$(mktemp "${TMPDIR:-/tmp}/chonkcraft-replay-outcome.XXXXXX.json")
PLAN=$(mktemp "${TMPDIR:-/tmp}/chonkcraft-replay-plan.XXXXXX.json")
SMOKE=$(mktemp "${TMPDIR:-/tmp}/chonkcraft-replay-smoke.XXXXXX.json")
trap 'rm -f "$REPORT" "$OUTCOME" "$PLAN" "$SMOKE"' EXIT HUP INT TERM

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

python3 "$ROOT/tools/bne-harness/scripts/bne_replay_outcome.py" corpus \
    --expect-corpus-sha256 "$CORPUS_SHA256" \
    --asset-pack "$PACK" \
    --output "$OUTCOME" "$REPLAY_ROOT" >/dev/null
python3 - "$OUTCOME" <<'PY'
import json
import sys

report = json.load(open(sys.argv[1], encoding="utf-8"))
expected = {
    "replay_count": 27,
    "snapshot_bytes": 1025260,
    "record_count": 764756,
    "command_count": 168788,
    "outcome_corpus_sha256":
        "d809845e539e1a660d928105b51e09878af3233dbbed04f87a120830b639d123",
}
for name, wanted in expected.items():
    if report.get(name) != wanted:
        raise SystemExit(
            f"replay-outcome gate: {name}={report.get(name)!r}; expected {wanted}")
print("retail outcome schedules: PASS — 764756 exact dispatcher records")
PY

python3 -m unittest \
    "$ROOT/tools/bne-harness/tests/test_bne_replay.py" \
    "$ROOT/tools/bne-harness/tests/test_bne_replay_outcome.py"

cd "$ROOT"
mvn -q -pl desktop -am \
    -Dchonkcraft.pack="$PACK" \
    -Dtest=PlayerIntentJournalTest,PlayerOrderDeliveryTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
echo "Java ordered-selection and fulfillment gates: PASS"

REPLAY="$REPLAY_ROOT/NerzyvsHTOSGOW.wir"
if [ ! -f "$REPLAY" ]; then
    echo "player-intent gate: certified replay not found: $REPLAY" >&2
    exit 1
fi
python3 "$ROOT/tools/bne-harness/scripts/bne_replay_outcome.py" plan \
    "$REPLAY" --asset-pack "$PACK" --output "$PLAN" >/dev/null
mvn -q -pl desktop -am -DskipTests package
CHONKCRAFT_ASSET_PACK="$PACK" java \
    -cp "$ROOT/desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar" \
    net.chonkbase.chonkcraft.desktop.BneReplaySmokeCertification \
    "$PLAN" > "$SMOKE"
python3 - "$SMOKE" <<'PY'
import json
import sys

report = json.load(open(sys.argv[1], encoding="utf-8"))
minimums = {
    "processed_records": 2451,
    "processed_commands": 288,
    "submitted_orders": 100,
    "accepted_orders": 80,
    "progressed_orders": 80,
    "bound_native_units": 16,
}
for name, floor in minimums.items():
    if report.get(name, -1) < floor:
        raise SystemExit(
            f"replay smoke gate: {name}={report.get(name)!r}; floor {floor}")
if report.get("progressed_orders") != report.get("accepted_orders"):
    raise SystemExit("replay smoke gate: an accepted order made no progress")
stop = report.get("stopped_at") or {}
if (stop.get("name") != "unit-identity-unresolved"
        or stop.get("record") < 2451
        or stop.get("native_unit") != 1569):
    raise SystemExit(f"replay smoke gate: unexpected stop boundary {stop!r}")
production = stop.get("production_state") or []
if any(unit.get("type") == "unit-orc-barracks" for unit in production):
    raise SystemExit("replay smoke gate: the retained boundary unexpectedly has a barracks")
print("real BNE replay execution: PASS — 2451+ records, 80 accepted orders progressed")
PY
