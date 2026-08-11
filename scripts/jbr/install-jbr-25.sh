#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/jbr/lib-jbr-25.sh
source "$SCRIPT_DIR/lib-jbr-25.sh"

jbr_select_bundle

if existing_home="$(jbr_find_existing_home 2>/dev/null)"; then
  printf '%s\n' "$existing_home"
  exit 0
fi

download_root="$(jbr_default_download_root)"
archive_path="$download_root/$JBR_SELECTED_ARCHIVE"

if ! jbr_verify_archive "$archive_path"; then
  rm -f "$archive_path"
  jbr_download_archive "$archive_path"
fi

java_home="$(jbr_extract_archive "$archive_path" "$(jbr_default_install_root)")"
printf '%s\n' "$java_home"
