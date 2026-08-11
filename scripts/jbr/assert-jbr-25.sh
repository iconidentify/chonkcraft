#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/jbr/lib-jbr-25.sh
source "$SCRIPT_DIR/lib-jbr-25.sh"
jbr_select_bundle

command -v java >/dev/null 2>&1 || jbr_fail "java is not on PATH"
command -v jpackage >/dev/null 2>&1 || jbr_fail "jpackage is not on PATH"

props="$(java -XshowSettings:properties -version 2>&1)"
vendor="$(awk -F' = ' '/java.vendor = /{print $2}' <<<"$props" | tail -n1)"
version="$(awk -F' = ' '/java.version = /{print $2}' <<<"$props" | tail -n1)"
runtime_version="$(awk -F' = ' '/java.runtime.version = /{print $2}' <<<"$props" | tail -n1)"
java_home="$(awk -F' = ' '/java.home = /{print $2}' <<<"$props" | tail -n1)"

[[ "$vendor" == "$CHONK_JBR_VENDOR_EXPECTED" ]] || jbr_fail "expected java.vendor=$CHONK_JBR_VENDOR_EXPECTED, got ${vendor:-<unset>}"
[[ "$version" == "$CHONK_JBR_DISPLAY_VERSION" ]] || jbr_fail "expected java.version=$CHONK_JBR_DISPLAY_VERSION, got ${version:-<unset>}"
[[ "$runtime_version" == "$JBR_SELECTED_RUNTIME_VERSION" ]] || jbr_fail "expected java.runtime.version=$JBR_SELECTED_RUNTIME_VERSION, got ${runtime_version:-<unset>}"

echo "[jbr] release_tag=$CHONK_JBR_RELEASE_TAG"
echo "[jbr] vendor=$vendor"
echo "[jbr] java.version=$version"
echo "[jbr] java.runtime.version=$runtime_version"
echo "[jbr] java.home=$java_home"
echo "[jbr] jpackage.version=$(jpackage --version | head -n1)"
