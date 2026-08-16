#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${BNE_NEXT_LEVEL_OUT:-$ROOT/tools/bne-harness/work/next-level}"
PACK="${CHONKCRAFT_ASSET_PACK:-$HOME/.chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack}"

test -f "$PACK" || { echo "missing required BNE ChonkPack: $PACK" >&2; exit 1; }
mkdir -p "$OUT"

python3 -m unittest \
  tools/bne-harness/tests/test_bne_player_transaction.py \
  tools/bne-harness/tests/test_bne_ai_decision_ledger.py \
  tools/bne-harness/tests/test_bne_combat_lifecycle.py \
  tools/bne-harness/tests/test_bne_headless.py \
  tools/bne-harness/tests/test_bne_field_parity.py \
  tools/bne-harness/tests/test_bne_campaign_lifecycle.py \
  tools/bne-harness/tests/test_bne_next_level_gate.py

CHONKCRAFT_ASSET_PACK="$PACK" "$ROOT/scripts/run-tests.sh" \
  -pl engine,desktop -am \
  -Dtest=BattleNetAiDecisionLedgerEmitRealDataTest,BneAiDecisionAdapterTest,SaveGameTest,SavedTriggerWiringTest,PlayerIntentJournalTest,SelectionChangeTest,HotkeyBindingTest,CampaignTriggerPlayabilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false

python3 tools/bne-harness/scripts/bne_campaign_lifecycle.py inventory \
  engine/src/main/resources/chonkcraft/missions.tsv \
  --output "$OUT/campaign-inventory.json"
python3 tools/bne-harness/scripts/bne_combat_lifecycle.py inventory \
  tools/bne-harness/combat-lifecycle-requirements.json \
  --output "$OUT/combat-inventory.json" >/dev/null

COMMAND_FIXTURES=()
while IFS= read -r fixture; do
  COMMAND_FIXTURES+=("$fixture")
done < <(find tools/bne-harness/work/playtest-explorer/commanded \
  -type f -name '*.bnefx' | sort)
if [[ "${#COMMAND_FIXTURES[@]}" -eq 0 ]]; then
  echo "no authenticated commanded fixtures" >&2
  exit 1
fi
python3 tools/bne-harness/scripts/bne_playtest_explorer.py execute-commanded \
  "${COMMAND_FIXTURES[@]}" \
  --output "$OUT/execution-ledger.json" \
  --registry "$OUT/native-command-registry.json" \
  --inventory tools/bne-harness/work/playtest-explorer/coverage-inventory.json \
  --asset-pack "$PACK"
python3 tools/bne-harness/scripts/bne_playtest_explorer.py worklist \
  "$OUT/execution-ledger.json" \
  --inventory tools/bne-harness/work/playtest-explorer/coverage-inventory.json \
  --output "$OUT/player-worklist.json" \
  --markdown "$OUT/player-worklist.generated"

STATUS_ARGS=(
  --root "$ROOT"
  --command-report "$OUT/command-split-report.json"
  --output "$OUT/status.json"
)
if [[ -n "${BNE_NATIVE_AI_LEDGER:-}" ]]; then
  STATUS_ARGS+=(--native-ai "$BNE_NATIVE_AI_LEDGER")
fi
if [[ -n "${BNE_JAVA_AI_LEDGER:-}" ]]; then
  STATUS_ARGS+=(--java-ai "$BNE_JAVA_AI_LEDGER")
fi
if [[ -n "${BNE_COMBAT_PROOF:-}" ]]; then
  STATUS_ARGS+=(--combat-proof "$BNE_COMBAT_PROOF")
fi
if [[ -n "${BNE_CAMPAIGN_PROOF:-}" ]]; then
  STATUS_ARGS+=(--campaign-proof "$BNE_CAMPAIGN_PROOF")
fi
if [[ -n "${BNE_PLAYER_TRANSACTION_RECEIPTS:-}" ]]; then
  IFS=':' read -r -a RECEIPTS <<< "$BNE_PLAYER_TRANSACTION_RECEIPTS"
  for receipt in "${RECEIPTS[@]}"; do
    STATUS_ARGS+=(--player-transaction "$receipt")
  done
fi
if [[ "${1:-}" == "--require-certified" ]]; then
  STATUS_ARGS+=(--require-certified)
fi
python3 tools/bne-harness/scripts/bne_next_level_gate.py "${STATUS_ARGS[@]}"

echo "next-level parity status: $OUT/status.json"
