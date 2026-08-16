#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${BNE_ORACLE_HOST:-i9beef}"
PACK="${CHONKCRAFT_ASSET_PACK:-$HOME/.chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack}"
OUT="${BNE_AI_CYCLE_OUT:-$ROOT/tools/bne-harness/work/ai-cycle}"
CYCLES="${BNE_AI_CYCLES:-12}"
PLAYER="${BNE_AI_PLAYER:-1}"
SEED="${BNE_AI_SEED:-1}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
CASE_ID="${BNE_AI_CASE_ID:-ai-cycle-$STAMP}"
REMOTE_OUTPUT="ai-cycle/$CASE_ID"
NATIVE_SCENARIO="${BNE_AI_NATIVE_SCENARIO:-Campaign\\Orc\\Orc01.pud}"
JAVA_MAP="${BNE_AI_JAVA_MAP:-campaigns/orc/level01o}"

test -f "$PACK" || {
  echo "missing authenticated BNE ChonkPack: $PACK" >&2
  exit 1
}
[[ "$CYCLES" =~ ^[1-9][0-9]*$ ]] || {
  echo "BNE_AI_CYCLES must be a positive integer" >&2
  exit 1
}
mkdir -p "$OUT"

REMOTE_ROOT=".local/share/chonkcraft-bne-oracle"
ssh "$HOST" "root=\"\$HOME/$REMOTE_ROOT\"; python3 \"\$root/harness/scripts/bne_headless.py\" run --oracle-root \"\$root\" --case-id '$CASE_ID' --output '$REMOTE_OUTPUT' --scenario '$NATIVE_SCENARIO' --cycles '$CYCLES' --seed '$SEED' --trace-ai-build-state"

for extension in trace.txt manifest.json bnefx; do
  scp -q "$HOST:$REMOTE_ROOT/output/$REMOTE_OUTPUT/$CASE_ID.$extension" \
    "$OUT/$CASE_ID.$extension"
done
unzip -p "$PACK" assets/archives/maindat/0277.bin > "$OUT/ai.bin"
test -s "$OUT/ai.bin" || {
  echo "pack has no authenticated maindat/0277.bin" >&2
  exit 1
}

CYCLE_ARGS=()
for ((cycle = 1; cycle <= CYCLES; cycle++)); do
  CYCLE_ARGS+=(--cycle "$cycle")
done
python3 "$ROOT/tools/bne-harness/scripts/bne_ai_decision_ledger.py" from-trace \
  "$OUT/$CASE_ID.trace.txt" \
  --ai-bin "$OUT/ai.bin" \
  --active-player "$PLAYER" \
  "${CYCLE_ARGS[@]}" \
  --output "$OUT/native.json"

"$ROOT/scripts/jbr/with-jbr-25.sh" mvn -q -pl desktop -am \
  -DskipTests package
APP_JAR="$ROOT/desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar"
test -f "$APP_JAR" || {
  echo "current-head desktop app jar was not produced" >&2
  exit 1
}
CHONKCRAFT_ASSET_PACK="$PACK" "$ROOT/scripts/jbr/with-jbr-25.sh" java \
  -cp "$APP_JAR" net.chonkbase.chonkcraft.desktop.BneAiDecisionAdapter \
  --map "$JAVA_MAP" --player 0 --seed "$SEED" --cycles "$CYCLES" \
  --output "$OUT/java.json"

set +e
python3 "$ROOT/tools/bne-harness/scripts/bne_ai_decision_ledger.py" compare \
  "$OUT/native.json" "$OUT/java.json" > "$OUT/comparison.json"
COMPARE_STATUS=$?
set -e
python3 -m json.tool "$OUT/comparison.json" >/dev/null
if [[ "$COMPARE_STATUS" -gt 1 ]]; then
  echo "AI ledger comparison failed as infrastructure" >&2
  exit "$COMPARE_STATUS"
fi

python3 "$ROOT/tools/bne-harness/scripts/bne_ai_decision_ledger.py" coverage \
  "$OUT/native.json" --active-player "$PLAYER" "${CYCLE_ARGS[@]}" \
  > "$OUT/native-coverage.json"
python3 "$ROOT/tools/bne-harness/scripts/bne_ai_decision_ledger.py" coverage \
  "$OUT/java.json" --active-player "$PLAYER" "${CYCLE_ARGS[@]}" \
  > "$OUT/java-coverage.json"

echo "native/Java AI evidence: $OUT"
python3 - <<'PY' "$OUT/comparison.json"
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
if value["identical"]:
    print("AI decision window: EXACT")
elif value["state_identical"]:
    item = value["telemetry_difference"]
    print("AI committed state: EXACT; telemetry debt: "
          "cycle={cycle} player={player} field={field}".format(**item))
else:
    item = value["state_difference"]
    print("AI first state difference: "
          "cycle={cycle} player={player} field={field}".format(**item))
PY
