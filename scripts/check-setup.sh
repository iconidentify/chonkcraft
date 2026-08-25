#!/usr/bin/env bash
#
# Reports what this machine has, and therefore what a test run would actually
# exercise.
#
# It exists because the suite skips rather than fails when its external inputs
# are missing, so an unconfigured machine gets BUILD SUCCESS out of a run that
# verified almost nothing. 1,153 of 2,751 tests skip that way. This prints the
# difference before you spend twenty-five seconds wondering why the run was
# fast.
#
# Read-only: it never downloads the JDK and never builds anything.
#
# Usage:
#   scripts/check-setup.sh

set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
problems=0
warnings=0

ok()   { printf '  ok    %s\n' "$*"; }
warn() { printf '  warn  %s\n' "$*"; warnings=$((warnings + 1)); }
bad()  { printf '  FAIL  %s\n' "$*"; problems=$((problems + 1)); }
head2() { printf '\n%s\n' "$*"; }

# ---------------------------------------------------------------- host tools

head2 "Host tools"

for tool in bash curl tar; do
    if command -v "$tool" >/dev/null 2>&1; then
        ok "$tool"
    else
        bad "$tool is not on PATH; the JDK bootstrap needs it"
    fi
done

if command -v shasum >/dev/null 2>&1; then
    ok "shasum (used to verify the JDK archive)"
elif command -v sha512sum >/dev/null 2>&1; then
    ok "sha512sum (used to verify the JDK archive)"
else
    bad "neither shasum nor sha512sum; the JDK archive cannot be verified"
fi

if command -v mvn >/dev/null 2>&1; then
    mvn_version="$(mvn -v 2>/dev/null | grep -o 'Apache Maven [0-9.]*' | head -n1)"
    ok "${mvn_version:-maven} at $(command -v mvn)"
else
    bad "mvn is not on PATH; install Maven 3.9 or newer"
fi

if command -v python3 >/dev/null 2>&1; then
    ok "python3 (needed by repository verification scripts)"
else
    warn "python3 not found; repository verification scripts will not run"
fi

# ---------------------------------------------------------------------- JDK

head2 "Pinned JetBrains Runtime 25"

# shellcheck source=scripts/jbr/lib-jbr-25.sh
if source "${repo_root}/scripts/jbr/lib-jbr-25.sh" 2>/dev/null \
        && jbr_select_bundle 2>/dev/null; then
    ok "platform ${JBR_SELECTED_PLATFORM}, expecting ${CHONK_JBR_RUNTIME_VERSION}"
    if java_home="$(jbr_find_existing_home 2>/dev/null)"; then
        ok "installed at ${java_home}"
    else
        warn "not installed yet; the first build downloads about 250 MB to ${CHONK_JBR_INSTALL_ROOT:-$HOME/.chonk/jdks}"
    fi
else
    bad "this platform has no pinned JBR 25 bundle; see scripts/jbr/jbr-25.env"
fi

# ------------------------------------------------------------- Warcraft data

head2 "Warcraft II installation  (-Dwc2.install.dir / WC2_INSTALL_DIR)"

wc2_ok=0
wc2_dir="${WC2_INSTALL_DIR:-}"
if [[ -z "$wc2_dir" ]]; then
    warn "WC2_INSTALL_DIR is not set"
elif [[ ! -d "$wc2_dir" ]]; then
    bad "WC2_INSTALL_DIR points at ${wc2_dir}, which is not a directory"
else
    # maindat.war is the marker Warcraft2Install uses. It looks in the root,
    # then DATA/, then data/, case-insensitively.
    maindat=""
    for dir in "$wc2_dir" "$wc2_dir/DATA" "$wc2_dir/data"; do
        [[ -d "$dir" ]] || continue
        found="$(find "$dir" -maxdepth 1 -iname 'maindat.war' -o -maxdepth 1 -iname 'War Data' 2>/dev/null | head -n1)"
        if [[ -n "$found" ]]; then
            maindat="$found"
            break
        fi
    done
    if [[ -n "$maindat" ]]; then
        ok "maindat.war at ${maindat}"
        wc2_ok=1
        for archive in strdat.war rezdat.war sfxdat.sud snddat.war muddat.cud; do
            found="$(find "$wc2_dir" -maxdepth 2 -iname "$archive" 2>/dev/null | head -n1)"
            if [[ -n "$found" ]]; then
                ok "${archive}"
            elif [[ "$archive" == "snddat.war" || "$archive" == "muddat.cud" ]]; then
                discs="$(find "$wc2_dir" -maxdepth 3 \( -iname '*.img' -o -iname '*.iso' -o -iname '*.bin' \) 2>/dev/null | head -n1)"
                if [[ -n "$discs" ]]; then
                    warn "${archive} not installed; it will be extracted from a disc image into ${wc2_dir}/chonkcraft-cache"
                else
                    warn "${archive} not installed and no disc image found; no $([[ $archive == snddat.war ]] && echo music || echo cutscenes)"
                fi
            else
                warn "${archive} not found"
            fi
        done
        if [[ -w "$wc2_dir" ]]; then
            ok "installation directory is writable (needed for the disc-image cache)"
        else
            warn "installation directory is not writable; music and cutscenes cannot be cached from a disc image"
        fi
        maps="$(find "$wc2_dir" -maxdepth 1 -iname '*.pud' 2>/dev/null | wc -l | tr -d ' ')"
        if [[ "$maps" -gt 0 ]]; then
            ok "${maps} .PUD maps in the install root"
        else
            warn "no .PUD maps in the install root"
        fi
    else
        bad "no maindat.war under ${wc2_dir} or its DATA/ subdirectory"
    fi
fi

# -------------------------------------------------------------- asset pack

head2 "Asset pack  (-Dchonkcraft.pack / CHONKCRAFT_ASSET_PACK)"

pack_ok=0
pack="${CHONKCRAFT_ASSET_PACK:-}"
if [[ -z "$pack" ]]; then
    warn "CHONKCRAFT_ASSET_PACK is not set; PackParityTest's 8 tests skip"
    warn "build one with scripts/build-asset-pack.sh"
elif [[ ! -f "$pack" ]]; then
    bad "CHONKCRAFT_ASSET_PACK points at ${pack}, which is not a file"
else
    ok "$(du -h "$pack" | cut -f1) at ${pack}"
    pack_ok=1
fi

# ---------------------------------------------------------- opus test vectors

head2 "Opus test vectors  (-Dopus.testvectors / OPUS_TESTVECTORS)"

# The assetpack module carries a pure-Java Opus codec, and the claim it rests
# on is bit-exactness against the official vectors, not "it sounds right". No
# vectors, no claim: 21 conformance tests skip and say nothing about it.
opus_ok=0
opus_dir="${OPUS_TESTVECTORS:-}"
if [[ -z "$opus_dir" ]]; then
    warn "OPUS_TESTVECTORS is not set; 21 Opus conformance tests skip"
    warn "curl -O https://opus-codec.org/static/testvectors/opus_testvectors.tar.gz"
elif [[ ! -f "${opus_dir}/testvector01.bit" ]]; then
    bad "no testvector01.bit under ${opus_dir}"
else
    ok "testvector01.bit under ${opus_dir}"
    opus_rfc="${OPUS_RFC:-}"
    if [[ -z "$opus_rfc" ]]; then
        opus_rfc="$(dirname "$opus_dir")/rfc6716.txt"
    fi
    if [[ ! -f "$opus_rfc" ]]; then
        warn "rfc6716.txt is missing; 10 allocation-table tests skip"
        warn "download https://www.rfc-editor.org/rfc/rfc6716.txt beside the vectors"
    else
        ok "RFC 6716 text at ${opus_rfc}"
        opus_ok=1
    fi
fi

# ------------------------------------------------------------------- display

head2 "Display"

case "$(uname -s)" in
    Darwin)
        ok "macOS; a window server is present for the display-only tests"
        ;;
    Linux)
        if [[ -n "${DISPLAY:-}" || -n "${WAYLAND_DISPLAY:-}" ]]; then
            ok "DISPLAY/WAYLAND_DISPLAY set; the game can open a window"
        else
            warn "no DISPLAY or WAYLAND_DISPLAY; the game cannot open a window"
            warn "the test suite still runs: it is forced headless by the root pom"
        fi
        if command -v xvfb-run >/dev/null 2>&1; then
            ok "xvfb-run available for the seven display-only tests"
        else
            warn "xvfb-run not found; the seven display-only tests will skip"
        fi
        ;;
    *)
        warn "unrecognised platform $(uname -s)"
        ;;
esac

# ------------------------------------------------------------------- summary

head2 "What a test run would exercise"

if [[ "$wc2_ok" == 1 && "$pack_ok" == 1 && "$opus_ok" == 1 ]]; then
    printf '  All three external inputs are configured.\n'
    printf '  Expect 2751 tests, 26 skipped, and about four minutes of wall time.\n'
    printf '  The expected skips cover display-only checks, an optional music\n'
    printf '  fixture, fixture-sensitive and custom-map checks, local playtest\n'
    printf '  saves, the opt-in production smoke and one release-sensitive test.\n'
elif [[ "$wc2_ok" == 1 ]]; then
    printf '  The game data is configured; one or more optional test inputs are not,\n'
    printf '  so more than the minimum number of tests will skip.\n'
    if [[ "$pack_ok" != 1 ]]; then
        printf '  Without an asset pack, 8 tests in PackParityTest skip and the pack\n'
        printf '  path -- which is what a player uses -- goes unexercised.\n'
    fi
    if [[ "$opus_ok" != 1 ]]; then
        printf '  Without the Opus test vectors, 21 conformance tests skip and the\n'
        printf '  pure-Java codec ships on nobody having checked it.\n'
    fi
else
    printf '  At least one external input is missing.\n'
    printf '  The suite will still report BUILD SUCCESS, having skipped most of itself:\n'
    printf '  with no input at all, 1153 of 2751 tests skip.\n'
    printf '  That is not a passing run. See docs/development-setup.md.\n'
fi

printf '\n  %d failure(s), %d warning(s)\n\n' "$problems" "$warnings"
[[ "$problems" -eq 0 ]]
