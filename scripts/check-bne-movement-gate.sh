#!/usr/bin/env bash
# Focused movement and terrain-domain tests.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
asset_pack="${CHONKCRAFT_ASSET_PACK:-${HOME}/.chonkcraft/work/warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack}"

if [[ ! -f "${asset_pack}" ]]; then
  echo "authenticated BNE asset pack missing; set CHONKCRAFT_ASSET_PACK" >&2
  exit 1
fi

CHONKCRAFT_ASSET_PACK="${asset_pack}" "${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=BattleNetMovementPlayabilityTest,BattleNetPathFinderTest,BattleNetWallFollowBoundsTest,BattleNetNavalLegalityRealDataTest,Orc11MarkedGoldSkirtRealDataTest,Human13Ogre1519RefusalHandoffRealDataTest,TransportUnloadTest,ShoreBuildingTest,NavalPatrolCoastGoalRealDataTest,NavalAirTest,RefusedStepTest,BattleNetRefusalSleepTest,BattleNetChaseRefusalTest,BattleNetSeaOccupancyTest,BattleshipCoastalAttackTest,PathAroundUnitsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

python3 - "${repo_root}/engine/target/surefire-reports" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
names = {
    "BattleNetMovementPlayabilityTest", "BattleNetPathFinderTest",
    "BattleNetWallFollowBoundsTest", "BattleNetNavalLegalityRealDataTest",
    "Orc11MarkedGoldSkirtRealDataTest",
    "Human13Ogre1519RefusalHandoffRealDataTest",
    "TransportUnloadTest", "ShoreBuildingTest", "NavalPatrolCoastGoalRealDataTest",
    "NavalAirTest", "RefusedStepTest", "BattleNetRefusalSleepTest",
    "BattleNetChaseRefusalTest", "BattleNetSeaOccupancyTest",
    "BattleshipCoastalAttackTest", "PathAroundUnitsTest",
}
tests = skipped = failures = errors = 0
seen = set()
for report in root.glob("TEST-*.xml"):
    suite = ET.parse(report).getroot()
    short = suite.attrib.get("name", "").rsplit(".", 1)[-1]
    if short not in names:
        continue
    seen.add(short)
    tests += int(suite.attrib.get("tests", 0))
    skipped += int(suite.attrib.get("skipped", 0))
    failures += int(suite.attrib.get("failures", 0))
    errors += int(suite.attrib.get("errors", 0))
missing = names - seen
if missing or tests != 110 or skipped or failures or errors:
    raise SystemExit(
        f"movement referee: expected 110/0/0/0 with every class present; "
        f"got {tests}/{skipped}/{failures}/{errors}, missing={sorted(missing)}"
    )
print("movement edge referee: 110 pass, 0 skipped")
PY

echo "movement gate passed: large footprints, congestion and refusal recovery"
