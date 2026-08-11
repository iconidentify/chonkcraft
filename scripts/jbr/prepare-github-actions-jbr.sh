#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/jbr/lib-jbr-25.sh
source "$SCRIPT_DIR/lib-jbr-25.sh"

[[ -n "${GITHUB_OUTPUT:-}" ]] || jbr_fail "GITHUB_OUTPUT must be set for GitHub Actions"

jbr_select_bundle

download_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/chonk-jbr-25"
archive_path="$download_root/$JBR_SELECTED_ARCHIVE"

if ! jbr_verify_archive "$archive_path"; then
  rm -f "$archive_path"
  jbr_download_archive "$archive_path"
fi

{
  echo "jdk_file=$archive_path"
  echo "java_version=$JBR_SELECTED_SEMVER"
  echo "architecture=$JBR_SETUP_JAVA_ARCH"
  echo "release_tag=$CHONK_JBR_RELEASE_TAG"
  echo "download_url=$JBR_SELECTED_URL"
} >> "$GITHUB_OUTPUT"
