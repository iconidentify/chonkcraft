#!/usr/bin/env bash
# Pinned retail spell dispatch proof plus complete player/referee spell loops.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
evidence="${repo_root}/tools/bne-readiness/spell-dispatch.json"
asset_pack="${CHONKCRAFT_ASSET_PACK:-${HOME}/.chonkcraft/work/warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack}"

if [[ ! -f "${asset_pack}" ]]; then
  echo "authenticated BNE asset pack missing; set CHONKCRAFT_ASSET_PACK" >&2
  exit 1
fi

if [[ -n "${BNE_EXE:-}" ]]; then
  retail_exe="${BNE_EXE}"
elif [[ -f "${repo_root}/tools/bne-harness/work/target-2.02/Warcraft II BNE.exe" ]]; then
  retail_exe="${repo_root}/tools/bne-harness/work/target-2.02/Warcraft II BNE.exe"
elif [[ -f "${repo_root}/../chonkcraft/tools/bne-harness/work/target-2.02/Warcraft II BNE.exe" ]]; then
  retail_exe="${repo_root}/../chonkcraft/tools/bne-harness/work/target-2.02/Warcraft II BNE.exe"
else
  echo "pinned BNE executable missing; set BNE_EXE" >&2
  exit 1
fi

python3 "${repo_root}/tools/bne-readiness/check_spell_dispatch.py" \
  --exe "${retail_exe}" --evidence "${evidence}"

CHONKCRAFT_ASSET_PACK="${asset_pack}" "${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=OffensiveSpellTest,SpellCastingTest,SpellRealDataTest,SpellBuffTest,RareSpellBehaviorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

python3 - "${repo_root}/engine/target/surefire-reports" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
expected = {
    "net.chonkbase.chonkcraft.engine.spell.OffensiveSpellTest": 10,
    "net.chonkbase.chonkcraft.engine.SpellCastingTest": 5,
    "net.chonkbase.chonkcraft.engine.spell.SpellRealDataTest": 6,
    "net.chonkbase.chonkcraft.engine.SpellBuffTest": 7,
    "net.chonkbase.chonkcraft.engine.RareSpellBehaviorTest": 8,
}
for name, count in expected.items():
    report = root / f"TEST-{name}.xml"
    if not report.is_file():
        raise SystemExit(f"spell report missing: {report}")
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
print("spell referee inventory: 36 pass, 0 skipped")
PY

echo "spell gate passed: authenticated retail dispatcher and complete spell effects"
