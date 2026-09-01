#!/usr/bin/env bash
# Catalog, simulation and rendered-pixel referee for every projectile lifecycle.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
asset_pack="${CHONKCRAFT_ASSET_PACK:-${HOME}/.chonkcraft/work/warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack}"

if [[ ! -f "${asset_pack}" ]]; then
  echo "authenticated BNE asset pack missing; set CHONKCRAFT_ASSET_PACK" >&2
  exit 1
fi

# Stale Surefire XML from an earlier pack or older test count used to make
# this referee report 3 skipped MissileRenderingTest methods after a clean
# 4-pass run. Delete the suites this gate reads before asking Maven again.
rm -f \
  "${repo_root}/engine/target/surefire-reports/TEST-"*Missile*.xml \
  "${repo_root}/engine/target/surefire-reports/TEST-"*Projectile*.xml \
  "${repo_root}/engine/target/surefire-reports/TEST-"*CombatFeedback*.xml \
  "${repo_root}/engine/target/surefire-reports/TEST-"*ClickMarker*.xml \
  "${repo_root}/engine/target/surefire-reports/TEST-"*PresentationAhead*.xml \
  "${repo_root}/engine/target/surefire-reports/TEST-"*RareSpell*.xml \
  "${repo_root}/desktop/target/surefire-reports/TEST-"*Missile*.xml \
  "${repo_root}/desktop/target/surefire-reports/TEST-"*ImpactRendering*.xml

CHONKCRAFT_ASSET_PACK="${asset_pack}" "${repo_root}/scripts/run-tests.sh" \
  -pl engine,desktop -am \
  '-Dtest=BattleNetProjectilePoolOrderTest,BattleNetMissileMotionTest,CombatFeedbackTest,MissileRenderingTest,ImpactRenderingTest,NativeMissileRealDataTest,ClickMarkerTest,PresentationAheadProjectilePrepareTest,RareSpellBehaviorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

python3 - "${repo_root}/engine/target/surefire-reports" \
  "${repo_root}/desktop/target/surefire-reports" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

expected = {
    "BattleNetProjectilePoolOrderTest": 5,
    "BattleNetMissileMotionTest": 8,
    "CombatFeedbackTest": 18,
    "MissileRenderingTest": 4,
    "ImpactRenderingTest": 2,
    "NativeMissileRealDataTest": 1,
    "ClickMarkerTest": 3,
    "PresentationAheadProjectilePrepareTest": 15,
    "RareSpellBehaviorTest": 8,
}
observed = {}
for root_name in sys.argv[1:]:
    for report in Path(root_name).glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        short = suite.attrib.get("name", "").rsplit(".", 1)[-1]
        if short in expected:
            observed[short] = tuple(int(suite.attrib.get(key, 0))
                    for key in ("tests", "skipped", "failures", "errors"))
for name, count in expected.items():
    actual = observed.get(name)
    if actual != (count, 0, 0, 0):
        raise SystemExit(f"{name}: expected {(count, 0, 0, 0)}, got {actual}")
print("projectile lifecycle referee: 64 pass, 0 skipped")
PY

echo "projectile gate passed: flight, impact, persistent effects and click feedback"
