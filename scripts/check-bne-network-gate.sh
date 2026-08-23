#!/usr/bin/env bash
# Complete Java-to-Java BNE-style lockstep, including deterministic UDP faults.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report="${repo_root}/engine/target/surefire-reports/TEST-net.chonkbase.chonkcraft.engine.network.LockstepTest.xml"

"${repo_root}/scripts/run-tests.sh" -pl engine -am \
  '-Dtest=LockstepTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

python3 - "${report}" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

report = Path(sys.argv[1])
if not report.is_file():
    raise SystemExit(f"lockstep report missing: {report}")
suite = ET.parse(report).getroot()
observed = int(suite.attrib.get("tests", -1))
skipped = int(suite.attrib.get("skipped", -1))
failures = int(suite.attrib.get("failures", -1))
errors = int(suite.attrib.get("errors", -1))
if observed != 36 or skipped or failures or errors:
    raise SystemExit(
        "LockstepTest: expected 36/0/0/0 tests/skips/failures/errors, "
        f"got {observed}/{skipped}/{failures}/{errors}"
    )
print("lockstep inventory: 36 pass, 0 skipped")
PY

echo "network gate passed: independent peers converge through 1800 cycles and adverse UDP"

asset_pack="${1:-${CHONKCRAFT_ASSET_PACK:-}}"
if [[ -z "${asset_pack}" ]]; then
  echo "real multiplayer startup skipped: pass an authenticated ChonkPack to exercise it"
  exit 0
fi
if [[ ! -f "${asset_pack}" ]]; then
  echo "authenticated ChonkPack missing: ${asset_pack}" >&2
  exit 2
fi

# The engine tests above prove the protocol under injected faults. This proves
# the production desktop seam they do not touch: two operating-system
# processes, the real lobby socket, host-to-client map transfer, the final slot
# table, two different local views, and the same world after six net cycles.
work="$(mktemp -d "${TMPDIR:-/tmp}/chonkcraft-multiplayer.XXXXXX")"
host_pid=""
client_pid=""
watchdog_pid=""
cleanup() {
  [[ -z "${host_pid}" ]] || kill "${host_pid}" 2>/dev/null || true
  [[ -z "${client_pid}" ]] || kill "${client_pid}" 2>/dev/null || true
  [[ -z "${watchdog_pid}" ]] || kill "${watchdog_pid}" 2>/dev/null || true
  rm -rf "${work}"
}
trap cleanup EXIT

"${repo_root}/scripts/jbr/with-jbr-25.sh" mvn -q -pl desktop -am -DskipTests install
"${repo_root}/scripts/jbr/with-jbr-25.sh" mvn -q -pl desktop \
  dependency:build-classpath -Dmdep.outputFile="${work}/classpath.txt"
classpath="${repo_root}/desktop/target/classes:${repo_root}/engine/target/classes:${repo_root}/data/target/classes:${repo_root}/assetpack/target/classes:${repo_root}/runtime/target/classes:${repo_root}/extractor/target/classes:$(<"${work}/classpath.txt")"
port="$(python3 - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"

CHONKCRAFT_ASSET_PACK="${asset_pack}" \
  "${repo_root}/scripts/jbr/with-jbr-25.sh" java -cp "${classpath}" \
  net.chonkbase.chonkcraft.desktop.NetworkPeer \
  --lobby-host "${port}" --computer-player true --cycles 180 \
  --game-template top-vs-bottom \
  >"${work}/host.log" 2>&1 &
host_pid=$!
sleep 1
CHONKCRAFT_ASSET_PACK="${asset_pack}" \
  "${repo_root}/scripts/jbr/with-jbr-25.sh" java -cp "${classpath}" \
  net.chonkbase.chonkcraft.desktop.NetworkPeer \
  --lobby-join "127.0.0.1:${port}" --without-map true --cycles 180 \
  >"${work}/client.log" 2>&1 &
client_pid=$!

# A startup deadlock used to wait for the peer until NetworkPeer's one-minute
# diagnostic deadline. Twenty seconds is generous for 180 cycles locally and
# keeps the gate useful when the first frame cannot initialize.
(
  sleep 20
  kill "${host_pid}" "${client_pid}" 2>/dev/null || true
) &
watchdog_pid=$!

set +e
wait "${host_pid}"
host_status=$?
wait "${client_pid}"
client_status=$?
set -e
kill "${watchdog_pid}" 2>/dev/null || true
wait "${watchdog_pid}" 2>/dev/null || true
watchdog_pid=""
host_pid=""
client_pid=""

if [[ ${host_status} -ne 0 || ${client_status} -ne 0 ]]; then
  echo "real multiplayer startup failed (host=${host_status}, client=${client_status})" >&2
  sed 's/^/host: /' "${work}/host.log" >&2
  sed 's/^/client: /' "${work}/client.log" >&2
  exit 1
fi
grep -Eq 'initial view: visible=[1-9][0-9]* start=\[[0-9]+, [0-9]+\]' "${work}/host.log"
grep -Eq 'initial view: visible=[1-9][0-9]* start=\[[0-9]+, [0-9]+\]' "${work}/client.log"
grep -Eq 'rendered frame: nonblack=[1-9][0-9]*' "${work}/host.log"
grep -Eq 'rendered frame: nonblack=[1-9][0-9]*' "${work}/client.log"
grep -q 'source=host-transfer' "${work}/client.log"
grep -q 'template=TOP_VS_BOTTOM' "${work}/host.log"
grep -q 'template=TOP_VS_BOTTOM' "${work}/client.log"
grep -q 'finished: cycles=180' "${work}/host.log"
grep -q 'finished: cycles=180' "${work}/client.log"
host_hash="$(sed -n 's/.*hash=\([0-9a-f]*\)$/\1/p' "${work}/host.log" | tail -1)"
client_hash="$(sed -n 's/.*hash=\([0-9a-f]*\)$/\1/p' "${work}/client.log" | tail -1)"
if [[ -z "${host_hash}" || "${host_hash}" != "${client_hash}" ]]; then
  echo "real multiplayer worlds disagree: host=${host_hash}, client=${client_hash}" >&2
  exit 1
fi
echo "real multiplayer startup passed: transferred map, visible client frame, 180 cycles, hash ${host_hash}"

# Opt in to the public service proof for deployment and release gates. This is
# the same pair of real-data desktop processes as above, but room creation,
# map transfer and every lockstep packet cross the production HTTPS/WSS ingress.
matchmaker_url="${CHONKCRAFT_MATCHMAKER_URL:-}"
if [[ -z "${matchmaker_url}" ]]; then
  exit 0
fi

online_build="client-gate-$(date -u +%Y%m%d%H%M%S)-$$"
online_host_log="${work}/online-host.log"
online_client_log="${work}/online-client.log"
CHONKCRAFT_ASSET_PACK="${asset_pack}" \
  "${repo_root}/scripts/jbr/with-jbr-25.sh" java \
  -Djava.awt.headless=true \
  -Dchonkcraft.network.build="${online_build}" \
  -cp "${classpath}" net.chonkbase.chonkcraft.desktop.NetworkPeer \
  --online-host "${matchmaker_url}" --computer-player true --cycles 180 \
  --game-template top-vs-bottom \
  >"${online_host_log}" 2>&1 &
host_pid=$!

room_code=""
for _ in {1..100}; do
  room_code="$(sed -n 's/^online room \([A-Z0-9]*\)$/\1/p' "${online_host_log}" | tail -1)"
  [[ -z "${room_code}" ]] || break
  if ! kill -0 "${host_pid}" 2>/dev/null; then
    break
  fi
  sleep 0.1
done
if [[ -z "${room_code}" ]]; then
  echo "production multiplayer host did not publish a room code" >&2
  sed 's/^/host: /' "${online_host_log}" >&2
  exit 1
fi

CHONKCRAFT_ASSET_PACK="${asset_pack}" \
  "${repo_root}/scripts/jbr/with-jbr-25.sh" java \
  -Djava.awt.headless=true \
  -Dchonkcraft.network.build="${online_build}" \
  -cp "${classpath}" net.chonkbase.chonkcraft.desktop.NetworkPeer \
  --online-join "${room_code}" --matchmaker-url "${matchmaker_url}" \
  --without-map true --cycles 180 \
  >"${online_client_log}" 2>&1 &
client_pid=$!

(
  sleep 30
  kill "${host_pid}" "${client_pid}" 2>/dev/null || true
) &
watchdog_pid=$!

set +e
wait "${host_pid}"
host_status=$?
wait "${client_pid}"
client_status=$?
set -e
kill "${watchdog_pid}" 2>/dev/null || true
wait "${watchdog_pid}" 2>/dev/null || true
watchdog_pid=""
host_pid=""
client_pid=""

if [[ ${host_status} -ne 0 || ${client_status} -ne 0 ]]; then
  echo "production multiplayer client proof failed (host=${host_status}, client=${client_status})" >&2
  sed 's/^/host: /' "${online_host_log}" >&2
  sed 's/^/client: /' "${online_client_log}" >&2
  exit 1
fi
grep -Eq 'initial view: visible=[1-9][0-9]* start=\[[0-9]+, [0-9]+\]' "${online_host_log}"
grep -Eq 'initial view: visible=[1-9][0-9]* start=\[[0-9]+, [0-9]+\]' "${online_client_log}"
grep -Eq 'rendered frame: nonblack=[1-9][0-9]*' "${online_host_log}"
grep -Eq 'rendered frame: nonblack=[1-9][0-9]*' "${online_client_log}"
grep -q 'source=host-transfer' "${online_client_log}"
online_host_hash="$(sed -n 's/.*hash=\([0-9a-f]*\)$/\1/p' "${online_host_log}" | tail -1)"
online_client_hash="$(sed -n 's/.*hash=\([0-9a-f]*\)$/\1/p' "${online_client_log}" | tail -1)"
if [[ -z "${online_host_hash}" || "${online_host_hash}" != "${online_client_hash}" ]]; then
  echo "production multiplayer worlds disagree: host=${online_host_hash}, client=${online_client_hash}" >&2
  exit 1
fi
echo "production multiplayer clients passed: rendered both views, transferred the retail map, 180 cycles, hash ${online_host_hash}"
