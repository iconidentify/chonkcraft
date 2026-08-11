#!/usr/bin/env bash
# Certifies the shipped game in a directory containing only its JAR and BNE pack.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pack="${1:-${CHONKCRAFT_ASSET_PACK:-}}"
game="${2:-${root}/desktop/target/chonkcraft-desktop-0.1.0-SNAPSHOT-app.jar}"
receipt="${3:-${root}/target/bne-native-pack/hermetic-runtime.json}"

if [[ -z "${pack}" || ! -f "${pack}" ]]; then
  echo "usage: $0 <authenticated-bne.chonkpack> [game.jar] [receipt.json]" >&2
  exit 2
fi
if [[ ! -f "${game}" ]]; then
  echo "self-contained game JAR not found: ${game}" >&2
  exit 2
fi

laboratory="$(mktemp -d -t chonkcraft-hermetic)"
trap 'rm -rf "${laboratory}"' EXIT
mkdir -p "${laboratory}/home"
cp "${game}" "${laboratory}/game.jar"
cp "${pack}" "${laboratory}/game.chonkpack"

# No inherited source/content configuration is allowed into this process.
java_bin="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}/bin/java"
if [[ ! -x "${java_bin}" ]]; then
  java_bin="$(command -v java)"
fi

output="${laboratory}/certification.txt"
(
  cd "${laboratory}"
  env -i \
    HOME="${laboratory}/home" \
    PATH="/usr/bin:/bin" \
    "${java_bin}" \
    -Djava.awt.headless=true \
    -Duser.home="${laboratory}/home" \
    -Dchonkcraft.pack="${laboratory}/game.chonkpack" \
    -cp "${laboratory}/game.jar" \
    net.chonkbase.chonkcraft.desktop.HermeticCertification
) | tee "${output}"

grep -qx 'HERMETIC_CERTIFICATION=PASS' "${output}"
if find "${laboratory}" -mindepth 1 -maxdepth 1 -type f \
    ! -name game.jar ! -name game.chonkpack ! -name certification.txt | grep -q .; then
  echo "certification created an undeclared runtime input" >&2
  exit 1
fi

mkdir -p "$(dirname "${receipt}")"
GAME_JAR="${game}" GAME_PACK="${pack}" CERT_OUTPUT="${output}" RECEIPT="${receipt}" \
ROOT="${root}" python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess
from datetime import datetime, timezone

def sha(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

root = pathlib.Path(os.environ["ROOT"])
values = {}
for line in pathlib.Path(os.environ["CERT_OUTPUT"]).read_text().splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        values[key] = value
receipt = {
    "schema": "chonkcraft-hermetic-runtime-v1",
    "status": "PASS",
    "timestamp": datetime.now(timezone.utc).isoformat(),
    "git_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root,
                                            text=True).strip(),
    "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root,
                                           text=True)),
    "game_jar": {"path": os.environ["GAME_JAR"], "sha256": sha(os.environ["GAME_JAR"])},
    "asset_pack": {"path": os.environ["GAME_PACK"], "sha256": sha(os.environ["GAME_PACK"])},
    "environment": {"files": ["game.jar", "game.chonkpack"],
                    "chonkcraft_source_dir": False, "runtime_scripts": 0},
    "certification": values,
}
path = pathlib.Path(os.environ["RECEIPT"])
path.write_text(json.dumps(receipt, indent=2) + "\n")
print(path)
PY
