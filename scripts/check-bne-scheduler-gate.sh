#!/usr/bin/env bash
# Retail-data whole-match determinism plus fail-closed legacy grid diagnostics.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
diagnostic_log="${repo_root}/target/bne-playability/scheduler-legacy-diagnostic.log"
report="${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.SimulationTest.xml"
classification="${repo_root}/tools/bne-readiness/scheduler-diagnostics.json"
mkdir -p "$(dirname "${diagnostic_log}")"

"${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=BattleNetSchedulerPlayabilityTest,MissileDeterminismTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

set +e
rm -f "${report}"
"${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=SimulationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false >"${diagnostic_log}" 2>&1
diagnostic_status=$?
set -e

python3 - "${classification}" "${report}" "${diagnostic_status}" <<'PY'
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

catalog = json.loads(Path(sys.argv[1]).read_text())
report = Path(sys.argv[2])
status = int(sys.argv[3])
entries = catalog.get("classified_failures", [])
names = [entry.get("name") for entry in entries]
if catalog.get("schema") != 1 or len(names) != 3 or len(names) != len(set(names)):
    raise SystemExit("scheduler diagnostic catalog must contain three unique schema-v1 names")
if any(not entry.get("category") or not entry.get("disposition") for entry in entries):
    raise SystemExit("every scheduler diagnostic needs a category and disposition")
if not report.is_file():
    raise SystemExit(f"scheduler diagnostic report missing: {report}")
xml = ET.parse(report).getroot()
if xml.attrib.get("name") != catalog.get("test_class"):
    raise SystemExit("scheduler diagnostic report is for the wrong class")
tests = int(xml.attrib.get("tests", -1))
errors = int(xml.attrib.get("errors", -1))
if tests != catalog.get("test_count"):
    raise SystemExit(f"SimulationTest inventory changed: expected 22, got {tests}")
if errors:
    raise SystemExit(f"SimulationTest produced {errors} errors")
actual = {case.attrib["name"] for case in xml.findall("testcase")
          if case.find("failure") is not None}
unknown = sorted(actual - set(names))
if unknown:
    raise SystemExit("unclassified SimulationTest failures: " + ", ".join(unknown))
if status != 0:
    raise SystemExit(f"SimulationTest diagnostic runner exited {status}")
# run-tests.sh deliberately uses Maven --fail-never. Its zero status allows
# classified assertion failures; the freshly generated Surefire XML above is
# the authority for exact names, count and error state.
print(f"SimulationTest: {tests - len(actual)} pass, {len(actual)} classified, "
      f"0 unclassified, {len(set(names) - actual)} retired")
PY

echo "scheduler gate passed: 1800-cycle retail replay and both RNG streams deterministic"
