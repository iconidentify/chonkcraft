#!/usr/bin/env bash
# Fail when an accepted player control silently makes no progress.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# A failed compile must not borrow a passing report from an earlier build.
rm -f \
  "${repo_root}/desktop/target/surefire-reports/TEST-net.chonkbase.chonkcraft.desktop.PlayerOrderDeliveryTest.xml" \
  "${repo_root}/desktop/target/surefire-reports/TEST-net.chonkbase.chonkcraft.desktop.ControlLivenessPlayabilityTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetDemolitionRealDataTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.Issue12OrderLivenessTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetCombatReplacementRealDataTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetMovingAttackReplacementRealDataTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetPlayerPatrolRealDataTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetPlayerAttackTargetRealDataTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetPlayerFollowRealDataTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetPlayerGoldLoopRealDataTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetChaseDestArmAfterAttackWaitRealDataTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetSettleStandAndFightRealDataTest.xml" \
  "${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.BattleNetInPlaceFirstTakeRealDataTest.xml"

"${repo_root}/scripts/run-tests.sh" -pl desktop -am \
  '-Dtest=BattleNetDemolitionRealDataTest,PlayerOrderDeliveryTest,ControlLivenessPlayabilityTest,Issue12OrderLivenessTest,BattleNetCombatReplacementRealDataTest,BattleNetMovingAttackReplacementRealDataTest,BattleNetPlayerPatrolRealDataTest,BattleNetPlayerAttackTargetRealDataTest,BattleNetPlayerFollowRealDataTest,BattleNetPlayerGoldLoopRealDataTest,BattleNetChaseDestArmAfterAttackWaitRealDataTest,BattleNetSettleStandAndFightRealDataTest,BattleNetInPlaceFirstTakeRealDataTest#aSettledHuman1FootmanFirstTakesSixAtCycle427' \
  -Dsurefire.failIfNoSpecifiedTests=false

python3 - "${repo_root}" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
inventory = (
    ("desktop", "PlayerOrderDeliveryTest", 17),
    ("desktop", "ControlLivenessPlayabilityTest", 2),
    ("engine", "BattleNetDemolitionRealDataTest", 10),
    ("engine", "Issue12OrderLivenessTest", 2),
    ("engine", "BattleNetCombatReplacementRealDataTest", 5),
    ("engine", "BattleNetMovingAttackReplacementRealDataTest", 9),
    ("engine", "BattleNetPlayerPatrolRealDataTest", 3),
    ("engine", "BattleNetPlayerAttackTargetRealDataTest", 1),
    ("engine", "BattleNetPlayerFollowRealDataTest", 6),
    ("engine", "BattleNetPlayerGoldLoopRealDataTest", 3),
    ("engine", "BattleNetChaseDestArmAfterAttackWaitRealDataTest", 1),
    ("engine", "BattleNetSettleStandAndFightRealDataTest", 1),
    ("engine", "BattleNetInPlaceFirstTakeRealDataTest", 1),
)
for module, name, expected in inventory:
    report = root / module / "target/surefire-reports" / (
        f"TEST-net.chonkbase.chonkcraft.{module}.{name}.xml")
    if not report.is_file():
        raise SystemExit(f"control-liveness report missing: {report}")
    suite = ET.parse(report).getroot()
    counts = tuple(int(suite.attrib.get(key, -1))
                   for key in ("tests", "skipped", "failures", "errors"))
    if counts != (expected, 0, 0, 0):
        raise SystemExit(
            f"{name}: expected {expected}/0/0/0 tests/skips/failures/errors, "
            f"got {'/'.join(map(str, counts))}")
print("control-liveness inventory: 61 pass, 0 skipped")
PY

echo "control-liveness gate passed: 1/3/9-unit controls, redirects, combat and real UDP remained responsive"
