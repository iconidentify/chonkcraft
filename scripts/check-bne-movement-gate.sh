#!/usr/bin/env bash
# Focused movement and terrain-domain tests.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=BattleNetMovementPlayabilityTest,BattleNetPathFinderTest,BattleNetWallFollowBoundsTest,BattleNetNavalLegalityRealDataTest,TransportUnloadTest,ShoreBuildingTest,NavalPatrolCoastGoalRealDataTest,NavalAirTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

echo "movement gate passed"
