#!/usr/bin/env bash
# Replay every fixed command case; missing private evidence is a failure.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "${repo_root}/tools/bne-harness/scripts/bne_command_campaign.py" run "$@"
