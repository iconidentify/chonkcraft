#!/usr/bin/env bash
# Complete retail idle-defense referee plus fail-closed targeting diagnostics.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
diagnostic_log="${repo_root}/target/bne-playability/idle-targeting-diagnostics.log"
classification="${repo_root}/tools/bne-readiness/idle-targeting-diagnostics.json"
mkdir -p "$(dirname "${diagnostic_log}")"

"${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=BattleNetIdleTargetingPlayabilityTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# These two inventories retain useful focused checks, but one mixes a
# cycle-level BNE hypothesis with 25 passing native-shaped checks and the
# other explicitly specifies the superseded ChonkCraft/LegacyEngine chooser.
set +e
"${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=BattleNetIdleAttackTest,TargetChoiceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false >"${diagnostic_log}" 2>&1
diagnostic_status=$?
set -e

python3 - "${repo_root}" "${classification}" "${diagnostic_status}" <<'PY'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root_dir = Path(sys.argv[1])
catalog = json.loads(Path(sys.argv[2]).read_text())
process_status = int(sys.argv[3])
if catalog.get("schema") != 1:
    raise SystemExit("idle-targeting diagnostic catalog must be schema 1")

all_actual = set()
all_known = set()
for inventory in catalog.get("inventories", []):
    test_class = inventory.get("test_class", "")
    entries = inventory.get("classified_failures", [])
    names = [entry.get("name") for entry in entries]
    if len(names) != len(set(names)):
        raise SystemExit(f"duplicate classified name in {test_class}")
    if any(not entry.get("category") or not entry.get("disposition") for entry in entries):
        raise SystemExit(f"incomplete diagnostic disposition in {test_class}")
    report = (root_dir / "engine/target/surefire-reports"
              / f"TEST-{test_class}.xml")
    if not report.is_file():
        raise SystemExit(f"idle-targeting diagnostic report missing: {report}")
    xml = ET.parse(report).getroot()
    if xml.attrib.get("name") != test_class:
        raise SystemExit(f"diagnostic report is for the wrong class: {report}")
    tests = int(xml.attrib.get("tests", -1))
    errors = int(xml.attrib.get("errors", -1))
    if tests != inventory.get("test_count"):
        raise SystemExit(f"{test_class} inventory changed size: "
                         f"expected {inventory.get('test_count')}, got {tests}")
    if errors:
        raise SystemExit(f"{test_class} produced {errors} errors")
    actual = {case.attrib["name"] for case in xml.findall("testcase")
              if case.find("failure") is not None}
    unknown = sorted(actual - set(names))
    if unknown:
        raise SystemExit(f"unclassified {test_class} failures: " + ", ".join(unknown))
    all_actual.update((test_class, name) for name in actual)
    all_known.update((test_class, name) for name in names)
    print(f"{test_class}: {tests - len(actual)} pass, {len(actual)} classified, "
          f"0 unclassified, {len(set(names) - actual)} retired")

if all_actual and process_status == 0:
    raise SystemExit("idle-targeting diagnostics report failures but returned success")
if not all_actual and process_status != 0:
    raise SystemExit(f"idle-targeting diagnostics exited {process_status} with clean reports")
print(f"idle-targeting diagnostics: {len(all_actual)} classified, 0 unclassified, "
      f"{len(all_known - all_actual)} retired")
PY

echo "idle-targeting gate passed: retail outcome clean; diagnostics fail-closed"
