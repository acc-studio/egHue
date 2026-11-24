#!/usr/bin/env bash
# Tiny adb UI helper for testing: ui.sh tap "Text" | ui.sh shot name | ui.sh text
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
SP="${SHOTS:-.}"
case "$1" in
  tap)
    xml=$("$ADB" exec-out uiautomator dump /dev/tty)
    b=$(echo "$xml" | tr '>' '\n' | grep -F "text=\"$2\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+')
    [ -z "$b" ] && b=$(echo "$xml" | tr '>' '\n' | grep -F "content-desc=\"$2\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+')
    set -- $b
    [ -z "$1" ] && { echo "not found"; exit 1; }
    "$ADB" shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ));;
  shot) "$ADB" exec-out screencap -p > "$SP/$2.png";;
  text) "$ADB" exec-out uiautomator dump /dev/tty | grep -o 'text="[^"]\+"\|content-desc="[^"]\+"';;
esac
