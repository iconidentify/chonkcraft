#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/jbr/jbr-25.env
source "$SCRIPT_DIR/jbr-25.env"

jbr_fail() {
  echo "[jbr] $*" >&2
  exit 1
}

jbr_strip_archive_suffix() {
  local name="$1"
  name="${name%.tar.gz}"
  name="${name%.zip}"
  printf '%s\n' "$name"
}

jbr_detect_platform() {
  local raw_os="${RUNNER_OS:-$(uname -s)}"
  local raw_arch="${RUNNER_ARCH:-$(uname -m)}"
  local os=""
  local arch=""

  case "$raw_os" in
    Linux|linux|GNU/Linux) os="linux" ;;
    Darwin|darwin|macOS) os="macos" ;;
    Windows|windows|MINGW*|MSYS*|CYGWIN*|*_NT-*) os="windows" ;;
    *) jbr_fail "unsupported operating system for pinned JBR 25: $raw_os" ;;
  esac

  case "$raw_arch" in
    x86_64|amd64|AMD64|X64|x64) arch="x64" ;;
    arm64|aarch64|AARCH64|ARM64) arch="aarch64" ;;
    *) jbr_fail "unsupported architecture for pinned JBR 25: $raw_arch" ;;
  esac

  if [[ "$os" == "windows" && "$arch" != "x64" ]]; then
    jbr_fail "the pinned Windows JBR 25 build currently supports x64 only (got $raw_arch)"
  fi

  printf '%s:%s\n' "$os" "$arch"
}

jbr_select_bundle() {
  local platform="${1:-$(jbr_detect_platform)}"

  case "$platform" in
    linux:x64)
      export JBR_SELECTED_PLATFORM="$platform"
      export JBR_SELECTED_ARCHIVE="$CHONK_JBR_LINUX_X64_ARCHIVE"
      export JBR_SELECTED_URL="$CHONK_JBR_LINUX_X64_URL"
      export JBR_SELECTED_SHA512="$CHONK_JBR_LINUX_X64_SHA512"
      export JBR_SETUP_JAVA_ARCH="x64"
      export JBR_SELECTED_SEMVER="$CHONK_JBR_SEMVER"
      export JBR_SELECTED_RUNTIME_VERSION="$CHONK_JBR_RUNTIME_VERSION"
      ;;
    linux:aarch64)
      export JBR_SELECTED_PLATFORM="$platform"
      export JBR_SELECTED_ARCHIVE="$CHONK_JBR_LINUX_AARCH64_ARCHIVE"
      export JBR_SELECTED_URL="$CHONK_JBR_LINUX_AARCH64_URL"
      export JBR_SELECTED_SHA512="$CHONK_JBR_LINUX_AARCH64_SHA512"
      export JBR_SETUP_JAVA_ARCH="aarch64"
      export JBR_SELECTED_SEMVER="$CHONK_JBR_SEMVER"
      export JBR_SELECTED_RUNTIME_VERSION="$CHONK_JBR_RUNTIME_VERSION"
      ;;
    macos:aarch64)
      export JBR_SELECTED_PLATFORM="$platform"
      export JBR_SELECTED_ARCHIVE="$CHONK_JBR_MACOS_ARM64_ARCHIVE"
      export JBR_SELECTED_URL="$CHONK_JBR_MACOS_ARM64_URL"
      export JBR_SELECTED_SHA512="$CHONK_JBR_MACOS_ARM64_SHA512"
      export JBR_SETUP_JAVA_ARCH="aarch64"
      export JBR_SELECTED_SEMVER="$CHONK_JBR_SEMVER"
      export JBR_SELECTED_RUNTIME_VERSION="$CHONK_JBR_RUNTIME_VERSION"
      ;;
    macos:x64)
      export JBR_SELECTED_PLATFORM="$platform"
      export JBR_SELECTED_ARCHIVE="$CHONK_JBR_MACOS_X64_ARCHIVE"
      export JBR_SELECTED_URL="$CHONK_JBR_MACOS_X64_URL"
      export JBR_SELECTED_SHA512="$CHONK_JBR_MACOS_X64_SHA512"
      export JBR_SETUP_JAVA_ARCH="x64"
      export JBR_SELECTED_SEMVER="$CHONK_JBR_SEMVER"
      export JBR_SELECTED_RUNTIME_VERSION="$CHONK_JBR_RUNTIME_VERSION"
      ;;
    windows:x64)
      export JBR_SELECTED_PLATFORM="$platform"
      export JBR_SELECTED_ARCHIVE="$CHONK_JBR_WINDOWS_X64_ARCHIVE"
      export JBR_SELECTED_URL="$CHONK_JBR_WINDOWS_X64_URL"
      export JBR_SELECTED_SHA512="$CHONK_JBR_WINDOWS_X64_SHA512"
      export JBR_SETUP_JAVA_ARCH="x64"
      export JBR_SELECTED_SEMVER="$CHONK_JBR_WINDOWS_X64_SEMVER"
      export JBR_SELECTED_RUNTIME_VERSION="$CHONK_JBR_WINDOWS_X64_RUNTIME_VERSION"
      ;;
    *)
      jbr_fail "no pinned JBR 25 bundle configured for platform $platform"
      ;;
  esac
}

jbr_default_install_root() {
  printf '%s\n' "${CHONK_JBR_INSTALL_ROOT:-$HOME/.chonk/jdks}"
}

jbr_default_download_root() {
  printf '%s\n' "${CHONK_JBR_DOWNLOAD_ROOT:-$(jbr_default_install_root)/downloads}"
}

jbr_install_dir_name() {
  printf '%s\n' "$(jbr_strip_archive_suffix "$JBR_SELECTED_ARCHIVE")"
}

jbr_java_home_for_install_dir() {
  local install_dir="$1"
  case "$JBR_SELECTED_PLATFORM" in
    macos:*) printf '%s/Contents/Home\n' "$install_dir" ;;
    *) printf '%s\n' "$install_dir" ;;
  esac
}

jbr_hash_file() {
  local file="$1"
  if [[ "${JBR_SELECTED_PLATFORM:-}" == windows:* ]] && command -v powershell.exe >/dev/null 2>&1; then
    local ps_file="$file"
    if command -v cygpath >/dev/null 2>&1; then
      ps_file="$(cygpath -w "$file")"
    fi
    powershell.exe -NoLogo -NoProfile -Command \
      "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; (Get-FileHash -Algorithm SHA512 -LiteralPath '$ps_file').Hash.ToLowerInvariant()" \
      | tr -d '\r'
    return 0
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 512 "$file" | awk '{print $1}'
  else
    sha512sum "$file" | awk '{print $1}'
  fi
}

jbr_java_binary_for_home() {
  local java_home="$1"
  if [[ -x "$java_home/bin/java" ]]; then
    printf '%s/bin/java\n' "$java_home"
    return 0
  fi
  if [[ -x "$java_home/bin/java.exe" ]]; then
    printf '%s/bin/java.exe\n' "$java_home"
    return 0
  fi
  return 1
}

jbr_verify_archive() {
  local file="$1"
  [[ -f "$file" ]] || return 1
  local actual
  actual="$(jbr_hash_file "$file")"
  [[ "$actual" == "$JBR_SELECTED_SHA512" ]]
}

jbr_download_archive() {
  local archive_path="$1"
  local tmp_path="${archive_path}.part"
  mkdir -p "$(dirname "$archive_path")"
  local attempt
  for attempt in 1 2 3; do
    rm -f "$tmp_path" "$archive_path"
    if [[ "${JBR_SELECTED_PLATFORM:-}" == windows:* ]] && command -v powershell.exe >/dev/null 2>&1; then
      local ps_tmp="$tmp_path"
      if command -v cygpath >/dev/null 2>&1; then
        ps_tmp="$(cygpath -w "$tmp_path")"
      fi
      powershell.exe -NoLogo -NoProfile -Command \
        "\$ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest -Uri '$JBR_SELECTED_URL' -OutFile '$ps_tmp'"
    else
      curl -fsSL --retry 3 --retry-delay 2 --retry-all-errors "$JBR_SELECTED_URL" -o "$tmp_path"
    fi
    mv "$tmp_path" "$archive_path"
    if jbr_verify_archive "$archive_path"; then
      return 0
    fi
    local actual_hash
    actual_hash="$(jbr_hash_file "$archive_path" || true)"
    echo "[jbr] sha512 mismatch on attempt $attempt for $archive_path" >&2
    echo "[jbr] expected: $JBR_SELECTED_SHA512" >&2
    echo "[jbr] actual:   ${actual_hash:-unknown}" >&2
  done
  rm -f "$archive_path"
  jbr_fail "sha512 mismatch for $archive_path from $JBR_SELECTED_URL"
}

jbr_verify_home() {
  local java_home="$1"
  local java_bin
  java_bin="$(jbr_java_binary_for_home "$java_home")" || return 1
  local props
  props="$("$java_bin" -XshowSettings:properties -version 2>&1)" || return 1
  grep -q "java.vendor = $CHONK_JBR_VENDOR_EXPECTED" <<<"$props" || return 1
  grep -q "java.version = $CHONK_JBR_DISPLAY_VERSION" <<<"$props" || return 1
  grep -q "java.runtime.version = $JBR_SELECTED_RUNTIME_VERSION" <<<"$props" || return 1
}

jbr_find_existing_home() {
  local dir_name
  dir_name="$(jbr_install_dir_name)"
  local install_root
  install_root="$(jbr_default_install_root)"
  local candidates=()

  if [[ -n "${CHONK_JBR_HOME:-}" ]]; then
    candidates+=("${CHONK_JBR_HOME}")
  fi

  case "$JBR_SELECTED_PLATFORM" in
    macos:*)
      candidates+=(
        "$HOME/Documents/jdks/$dir_name/Contents/Home"
        "$HOME/Documents/jdk/$dir_name/Contents/Home"
        "$install_root/$dir_name/Contents/Home"
      )
      ;;
    *)
      candidates+=(
        "$HOME/Documents/jdks/$dir_name"
        "$HOME/Documents/jdk/$dir_name"
        "$install_root/$dir_name"
      )
      ;;
  esac

  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -n "$candidate" ]] && jbr_verify_home "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

jbr_extract_archive() {
  local archive_path="$1"
  local install_root="$2"
  local dir_name
  dir_name="$(jbr_install_dir_name)"
  local install_dir="$install_root/$dir_name"
  local java_home
  java_home="$(jbr_java_home_for_install_dir "$install_dir")"

  if jbr_verify_home "$java_home"; then
    printf '%s\n' "$java_home"
    return 0
  fi

  rm -rf "$install_dir"
  mkdir -p "$install_root"

  local tmp_root
  tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/chonk-jbr-25.XXXXXX")"
  trap 'rm -rf "$tmp_root"' RETURN

  if [[ "$archive_path" == *.zip ]]; then
    unzip -q "$archive_path" -d "$tmp_root"
  else
    tar -xzf "$archive_path" -C "$tmp_root"
  fi

  local extracted_dir
  extracted_dir="$(find "$tmp_root" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  [[ -n "$extracted_dir" ]] || jbr_fail "unable to locate extracted JBR directory in $archive_path"

  mv "$extracted_dir" "$install_dir"

  java_home="$(jbr_java_home_for_install_dir "$install_dir")"
  jbr_verify_home "$java_home" || jbr_fail "extracted JBR home did not validate at $java_home"
  printf '%s\n' "$java_home"
}
