#!/usr/bin/env bash
# Fail when an accepted player control silently makes no progress.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report="${repo_root}/desktop/target/surefire-reports/TEST-net.chonkbase.chonkcraft.desktop.ControlLivenessPlayabilityTest.xml"

"${repo_root}/scripts/run-tests.sh" -pl desktop -am \
  '-Dtest=ControlLivenessPlayabilityTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

python3 - "${report}" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

report = Path(sys.argv[1])
if not report.is_file():
    raise SystemExit(f"control-liveness report missing: {report}")
suite = ET.parse(report).getroot()
observed = int(suite.attrib.get("tests", -1))
skipped = int(suite.attrib.get("skipped", -1))
failures = int(suite.attrib.get("failures", -1))
errors = int(suite.attrib.get("errors", -1))
if observed != 2 or skipped or failures or errors:
    raise SystemExit(
        "ControlLivenessPlayabilityTest: expected 2/0/0/0 "
        "tests/skips/failures/errors, "
        f"got {observed}/{skipped}/{failures}/{errors}"
    )
print("control-liveness inventory: 2 pass, 0 skipped")
PY

echo "control-liveness gate passed: 1/3/9-unit controls, redirects, combat and real UDP remained responsive"
