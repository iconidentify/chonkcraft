#!/bin/sh
# Build the micro-oracle's project-local backend, or report that it is current.
#
# The emulator is not installed into the machine's Python: a parity harness
# that needs a global install is one a fresh clone cannot run.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
venv="$here/.venv-micro-oracle"
requirements="$here/micro-oracle-requirements.txt"
if [ ! -x "$venv/bin/python" ]; then
    python3 -m venv "$venv"
fi
"$venv/bin/pip" install --quiet --disable-pip-version-check -r "$requirements"
"$venv/bin/python" - <<'PY'
import capstone, unicorn
print(f"micro-oracle backend ready: unicorn {unicorn.__version__}, "
      f"capstone {capstone.__version__}")
PY
