#!/usr/bin/env bash
# Signs a jpackage application from the innermost native code outward.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <ChonkCraft.app>" >&2
  exit 2
fi

app="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
identity="${MAC_SIGNING_IDENTITY:-}"
keychain="${MAC_KEYCHAIN_PATH:-}"
entitlements="${MAC_ENTITLEMENTS:-${root}/packaging/macos/entitlements.plist}"

[[ -d "${app}" ]] || { echo "application bundle not found: ${app}" >&2; exit 1; }
[[ -n "${identity}" ]] || { echo "MAC_SIGNING_IDENTITY is required" >&2; exit 1; }
[[ -f "${entitlements}" ]] || { echo "entitlements not found: ${entitlements}" >&2; exit 1; }

common=(--force --timestamp --options runtime --sign "${identity}")
if [[ -n "${keychain}" ]]; then
  common+=(--keychain "${keychain}")
fi

xattr -cr "${app}" 2>/dev/null || true
find "${app}" -name _CodeSignature -type d -prune -exec rm -rf {} +
find "${app}" -name CodeResources -type f -delete

# Java's runtime contains native launchers and libraries. Sign every nested
# code object before sealing the runtime bundle and finally the application.
while IFS= read -r code; do
  [[ -n "${code}" && ! -L "${code}" ]] || continue
  chmod 755 "${code}" 2>/dev/null || true
  codesign "${common[@]}" --entitlements "${entitlements}" "${code}"
done < <(find "${app}" -type f \( \
  -name '*.dylib' -o \
  -name '*.jnilib' -o \
  -path '*/Contents/MacOS/*' -o \
  -path '*/Contents/runtime/Contents/Home/bin/*' -o \
  -name jspawnhelper \
\) | sort)

if [[ -d "${app}/Contents/runtime" ]]; then
  codesign "${common[@]}" --entitlements "${entitlements}" \
    "${app}/Contents/runtime"
fi
codesign "${common[@]}" --entitlements "${entitlements}" "${app}"
codesign --verify --deep --strict=all --verbose=2 "${app}"

authority="$(codesign -dvv "${app}" 2>&1 | awk -F= \
  '/^Authority=Developer ID Application:/ && !found {print $2; found=1}')"
if [[ "${authority}" != Developer\ ID\ Application:* ]]; then
  echo "unexpected top-level signing authority: ${authority:-none}" >&2
  exit 1
fi
