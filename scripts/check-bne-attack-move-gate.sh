#!/usr/bin/env bash
# Complete BNE attack-move referees plus a fail-closed legacy diagnostic inventory.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
diagnostic_log="${repo_root}/target/bne-playability/attack-move-legacy-diagnostic.log"
diagnostic_report="${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.AttackMoveTest.xml"
classification="${repo_root}/tools/bne-readiness/attack-move-diagnostics.json"
mkdir -p "$(dirname "${diagnostic_log}")"

# These are the release authority: a synthetic obstacle referee and a second
# referee using the authenticated retail roster/data through the wire seam.
"${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=BattleNetAttackMovePlayabilityTest,BattleNetAttackMoveRetailPlayabilityTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# The desktop seam is part of the order, not presentation around it: a mixed
# selected squad must all retain one flushed target and only an accepted order
# may answer the player.
"${repo_root}/scripts/run-tests.sh" -pl desktop -am \
  '-Dtest=PlayerOrderDeliveryTest,AcknowledgeOnceTest,RightClickTableTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# AttackMoveTest is an upstream diagnostic inventory, not a release oracle.
# It is allowed to be red only at explicitly reviewed names. Any new failure,
# error, missing report or test-count drift fails this gate.
set +e
"${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=AttackMoveTest' \
  -Dsurefire.failIfNoSpecifiedTests=false >"${diagnostic_log}" 2>&1
diagnostic_status=$?
set -e

python3 - "${classification}" "${diagnostic_report}" "${diagnostic_status}" <<'PY'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

catalog_path = Path(sys.argv[1])
report_path = Path(sys.argv[2])
process_status = int(sys.argv[3])

catalog = json.loads(catalog_path.read_text())
entries = catalog.get("classified_failures", [])
names = [entry.get("name") for entry in entries]
if catalog.get("schema") != 1 or len(names) != 24 or len(set(names)) != len(names):
    raise SystemExit("attack-move diagnostic catalog must contain 24 unique schema-v1 names")
if any(not entry.get("category") or not entry.get("disposition") for entry in entries):
    raise SystemExit("every attack-move diagnostic needs a category and disposition")
if not report_path.is_file():
    raise SystemExit(f"legacy diagnostic report missing: {report_path}")

root = ET.parse(report_path).getroot()
if root.attrib.get("name") != catalog.get("test_class"):
    raise SystemExit("legacy diagnostic report is for the wrong test class")
tests = int(root.attrib.get("tests", -1))
errors = int(root.attrib.get("errors", -1))
if tests != 65:
    raise SystemExit(f"legacy diagnostic inventory changed size: expected 65, got {tests}")
if errors:
    raise SystemExit(f"legacy diagnostic produced {errors} errors")

actual = set()
for case in root.findall("testcase"):
    if case.find("failure") is not None:
        actual.add(case.attrib["name"])
    if case.find("error") is not None:
        raise SystemExit(f"legacy diagnostic errored: {case.attrib['name']}")

unknown = sorted(actual - set(names))
if unknown:
    raise SystemExit("unclassified attack-move failures: " + ", ".join(unknown))
if process_status != 0:
    raise SystemExit(f"legacy diagnostic runner itself exited {process_status}")
# run-tests.sh deliberately invokes Maven with --fail-never so dependent
# modules still execute. Its process status is therefore zero for classified
# specification failures; the freshly written Surefire XML above is the
# fail-closed authority for their exact names, count and error state.

retired = sorted(set(names) - actual)
print(f"attack-move legacy diagnostics: {len(actual)} classified, 0 unclassified, "
      f"{len(retired)} retired")
for category in sorted({entry["category"] for entry in entries}):
    count = sum(1 for entry in entries
                if entry["name"] in actual and entry["category"] == category)
    print(f"  {category}: {count}")
PY

echo "attack-move gate passed: complete BNE referees clean; legacy inventory fail-closed"
