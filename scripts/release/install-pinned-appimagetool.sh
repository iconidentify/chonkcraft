#!/usr/bin/env bash
# Downloads the exact appimagetool release used by the sister project and
# verifies it before it can participate in a ChonkCraft release.

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: install-pinned-appimagetool.sh <output-path>" >&2
  exit 64
fi

output_path="$1"
tag="${CHONK_APPIMAGETOOL_TAG:-1.9.0}"
case "$(uname -m)" in
  x86_64)
    arch=x86_64
    pinned=46fdd785094c7f6e545b61afcfb0f3d98d8eab243f644b4b17698c01d06083d1
    ;;
  aarch64|arm64)
    arch=aarch64
    pinned=04f45ea45b5aa07bb2b071aed9dbf7a5185d3953b11b47358c1311f11ea94a96
    ;;
  *) echo "Unsupported appimagetool architecture: $(uname -m)" >&2; exit 1 ;;
esac
url="${CHONK_APPIMAGETOOL_URL:-https://github.com/AppImage/appimagetool/releases/download/${tag}/appimagetool-${arch}.AppImage}"
expected="${CHONK_APPIMAGETOOL_SHA256:-$pinned}"

mkdir -p "$(dirname "${output_path}")"
curl -fsSL --retry 3 --retry-delay 5 "${url}" -o "${output_path}"

if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "${output_path}" | awk '{print $1}')"
else
  actual="$(shasum -a 256 "${output_path}" | awk '{print $1}')"
fi

if [[ "${actual}" != "${expected}" ]]; then
  echo "Pinned appimagetool checksum mismatch." >&2
  echo "expected: ${expected}" >&2
  echo "actual:   ${actual}" >&2
  exit 1
fi

chmod +x "${output_path}"
printf 'Verified appimagetool %s at %s\n' "${tag}" "${output_path}"
