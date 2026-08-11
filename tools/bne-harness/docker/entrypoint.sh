#!/bin/sh
set -eu

if [ ! -d /oracle/harness ] || [ ! -d /oracle/game ] || [ ! -d /oracle/cd ]; then
    echo "bne-headless: /oracle must contain harness, game, and cd" >&2
    exit 2
fi

mkdir -p "$WINEPREFIX"
Xvfb "$DISPLAY" -screen 0 640x480x8 -nolisten tcp -noreset >/tmp/xvfb.log 2>&1 &
xvfb_pid=$!

cleanup() {
    wineserver -k >/dev/null 2>&1 || true
    kill "$xvfb_pid" >/dev/null 2>&1 || true
    wait "$xvfb_pid" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

wineboot --init >/tmp/wineboot.log 2>&1

rm -f "$WINEPREFIX/dosdevices/i:" "$WINEPREFIX/dosdevices/i::"
ln -s /oracle/cd "$WINEPREFIX/dosdevices/i:"

wine reg add 'HKLM\Software\Wine\Drives' /v 'i:' /t REG_SZ /d cdrom /f >/dev/null
wine reg add 'HKCU\Software\Wine\Drives' /v 'i:' /t REG_SZ /d cdrom /f >/dev/null
wine reg add 'HKLM\Software\Blizzard Entertainment\Warcraft II BNE' \
    /v InstallPath /t REG_SZ /d 'Z:\oracle\game' /f /reg:32 >/dev/null
wine reg add 'HKLM\Software\Blizzard Entertainment\Warcraft II BNE' \
    /v Program /t REG_SZ /d 'Z:\oracle\game\Warcraft II BNE.exe' \
    /f /reg:32 >/dev/null
wine reg add 'HKLM\Software\Blizzard Entertainment\Warcraft II BNE' \
    /v War2CD /t REG_SZ /d 'I:\' /f /reg:32 >/dev/null

status=0
"$@" || status=$?
exit "$status"
