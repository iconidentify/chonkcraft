#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="${BNE_NEXT_LEVEL_OUT:-$ROOT/tools/bne-harness/work/next-level}"
PACK="${CHONKCRAFT_ASSET_PACK:-$HOME/.chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack}"

REQUIRE_CERTIFIED=0
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --require-certified) REQUIRE_CERTIFIED=1 ;;
    *) echo "unknown next-level gate argument: $1" >&2; exit 2 ;;
  esac
  shift
done

test -f "$PACK" || { echo "missing required BNE ChonkPack: $PACK" >&2; exit 1; }
mkdir -p "$OUT"

PYTHONPATH="$ROOT/tools/bne-harness/tests:$ROOT/tools/bne-harness/scripts${PYTHONPATH:+:$PYTHONPATH}" \
python3 -m unittest \
  tools/bne-harness/tests/test_bne_player_transaction.py \
  tools/bne-harness/tests/test_bne_replay_outcome.py \
  tools/bne-harness/tests/test_bne_ai_decision_ledger.py \
  tools/bne-harness/tests/test_bne_ai_conductor.py \
  tools/bne-harness/tests/test_bne_combat_lifecycle.py \
  tools/bne-harness/tests/test_bne_headless.py \
  tools/bne-harness/tests/test_bne_snapshot_capture.py \
  tools/bne-harness/tests/test_bne_static_analysis.py \
  tools/bne-harness/tests/test_bne_divergence_compiler.py \
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

AI_DISCOVERY="$OUT/ai-discovery.json"
AI_DISCOVERY_CURRENT=0
if [[ "${BNE_SKIP_AI_DISCOVERY:-0}" != "1" ]]; then
  if python3 tools/bne-harness/scripts/bne_java.py ai-conductor \
      > "$AI_DISCOVERY.tmp"; then
    mv "$AI_DISCOVERY.tmp" "$AI_DISCOVERY"
    AI_DISCOVERY_CURRENT=1
  else
    rm -f "$AI_DISCOVERY.tmp"
    echo "AI discovery unavailable; scorecard will retain that evidence debt" >&2
  fi
fi

STATUS_ARGS=(
  --root "$ROOT"
  --asset-pack "$PACK"
  --command-report "$OUT/command-split-report.json"
  --output "$OUT/status.json"
  --markdown-output "$OUT/NEXT.md"
)
if [[ "$AI_DISCOVERY_CURRENT" == "1" ]]; then
  STATUS_ARGS+=(--ai-discovery "$AI_DISCOVERY")
fi
if [[ -n "${BNE_PLAYER_CERTIFICATION:-}" ]]; then
  STATUS_ARGS+=(--player-certification "$BNE_PLAYER_CERTIFICATION")
fi
if [[ -n "${BNE_REPLAY_CERTIFICATION:-}" ]]; then
  STATUS_ARGS+=(--replay-certification "$BNE_REPLAY_CERTIFICATION")
fi
if [[ -n "${BNE_REPLAY_CORPUS:-}" ]]; then
  STATUS_ARGS+=(--replay-corpus "$BNE_REPLAY_CORPUS")
fi
if [[ -n "${BNE_REPLAY_REPORTS:-}" ]]; then
  IFS=':' read -r -a REPLAY_REPORTS <<< "$BNE_REPLAY_REPORTS"
  for report in "${REPLAY_REPORTS[@]}"; do
    STATUS_ARGS+=(--replay-report "$report")
  done
fi
if [[ -n "${BNE_AI_CONDUCTOR_REPORT:-}" ]]; then
  STATUS_ARGS+=(--ai-conductor-report "$BNE_AI_CONDUCTOR_REPORT")
fi
if [[ -n "${BNE_NATIVE_AI_LEDGER:-}" ]]; then
  STATUS_ARGS+=(--native-ai "$BNE_NATIVE_AI_LEDGER")
fi
if [[ -n "${BNE_JAVA_AI_LEDGER:-}" ]]; then
  STATUS_ARGS+=(--java-ai "$BNE_JAVA_AI_LEDGER")
fi
if [[ -n "${BNE_COMBAT_PROOF:-}" ]]; then
  IFS=':' read -r -a COMBAT_PROOFS <<< "$BNE_COMBAT_PROOF"
  for proof in "${COMBAT_PROOFS[@]}"; do
    STATUS_ARGS+=(--combat-proof "$proof")
  done
fi
if [[ -n "${BNE_CAMPAIGN_PROOF:-}" ]]; then
  STATUS_ARGS+=(--campaign-proof "$BNE_CAMPAIGN_PROOF")
fi
if [[ -n "${BNE_DIVERGENCE_WORK_ORDER:-}" ]]; then
  STATUS_ARGS+=(--divergence-work-order "$BNE_DIVERGENCE_WORK_ORDER")
fi
if [[ -n "${BNE_DIVERGENCE_POINTER:-}" ]]; then
  STATUS_ARGS+=(--divergence-pointer "$BNE_DIVERGENCE_POINTER")
fi
if [[ -n "${BNE_PLAYER_TRANSACTION_RECEIPTS:-}" ]]; then
  IFS=':' read -r -a RECEIPTS <<< "$BNE_PLAYER_TRANSACTION_RECEIPTS"
  for receipt in "${RECEIPTS[@]}"; do
    STATUS_ARGS+=(--player-transaction "$receipt")
  done
fi
if [[ -n "${BNE_PLAYER_PROOF_STORE:-}" ]]; then
  STATUS_ARGS+=(--player-proof-store "$BNE_PLAYER_PROOF_STORE")
fi
if [[ "$REQUIRE_CERTIFIED" == "1" ]]; then
  STATUS_ARGS+=(--require-certified)
fi
python3 tools/bne-harness/scripts/bne_next_level_gate.py "${STATUS_ARGS[@]}"

echo "next-level parity status: $OUT/status.json"
