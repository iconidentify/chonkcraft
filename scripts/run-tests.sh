#!/usr/bin/env bash
#
# Full reactor test run on the pinned JetBrains Runtime 25 SDK.
#
# Mirrors chonkblocker/scripts/run-tests.sh so the two projects fail the same
# way on the same machine.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Expected specification failures in engine must not prevent Maven from
# reaching the dependent desktop module. The identity gate below the run, not
# Maven's early reactor exit, decides whether the failure set is acceptable.
exec "${repo_root}/scripts/jbr/with-jbr-25.sh" mvn --fail-never \
  -f "${repo_root}/pom.xml" "$@" test
