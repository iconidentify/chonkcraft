#!/usr/bin/env bash
# Authenticated ai.bin semantics plus a complete autonomous opponent loop.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
asset_pack="${CHONKCRAFT_ASSET_PACK:-${HOME}/.chonkcraft/packs/warcraft-ii-battle-net-edition-usa-full.chonkpack}"
reports="${repo_root}/engine/target/surefire-reports"

if [[ ! -f "${asset_pack}" ]]; then
  echo "authenticated BNE asset pack missing; set CHONKCRAFT_ASSET_PACK" >&2
  exit 1
fi
CHONKCRAFT_ASSET_PACK="${asset_pack}" \
"${repo_root}/scripts/run-tests.sh" \
  -pl engine -am \
  '-Dtest=BattleNetAiBytecodeTest,BattleNetAiForcePredicateTest,BattleNetAiRetailDataTest,AiCompetenceTest,BattleNetHumanFiveGuardBehaviorRealDataTest,BattleNetAiPatrolLivenessTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

python3 - "${reports}" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
expected = {
    "net.chonkbase.chonkcraft.engine.ai.BattleNetAiBytecodeTest": 8,
    "net.chonkbase.chonkcraft.engine.ai.BattleNetAiForcePredicateTest": 3,
    "net.chonkbase.chonkcraft.engine.ai.BattleNetAiRetailDataTest": 1,
    "net.chonkbase.chonkcraft.engine.perf.AiCompetenceTest": 1,
    "net.chonkbase.chonkcraft.engine.BattleNetHumanFiveGuardBehaviorRealDataTest": 1,
    "net.chonkbase.chonkcraft.engine.BattleNetAiPatrolLivenessTest": 3,
}
for name, count in expected.items():
    report = root / f"TEST-{name}.xml"
    if not report.is_file():
        raise SystemExit(f"AI report missing: {report}")
    suite = ET.parse(report).getroot()
    observed = int(suite.attrib.get("tests", -1))
    skipped = int(suite.attrib.get("skipped", -1))
    failures = int(suite.attrib.get("failures", -1))
    errors = int(suite.attrib.get("errors", -1))
    if observed != count or skipped or failures or errors:
        raise SystemExit(
            f"{name}: expected {count}/0/0/0 tests/skips/failures/errors, "
            f"got {observed}/{skipped}/{failures}/{errors}"
        )
print("retail AI inventory: 17 pass, 0 skipped")
PY

echo "retail AI gate passed: ai.bin opponent gathers, builds, forms and attacks"
