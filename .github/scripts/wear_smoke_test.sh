#!/usr/bin/env bash
# Smoke test of the signed R8 release APK of the Wear OS app on a running Wear OS emulator (called from the script of
# reactivecircus/android-emulator-runner in android-device-tests.yml, job «wear-smoke»; audit C-10).
#   install -> start the launcher entry -> process alive? -> add the price tile (debug surface of the Wear OS
#   emulator, renders the tile through the tile service) -> monkey (200 events, fixed seed) -> crash buffer / ANR
# Writes findings for annotate.py (the step after the emulator prints them as annotations) and the logcat dump into
# <out dir>. Always exits 0: the annotate step decides.
#
# Errors: install fails, the launcher entry is missing, the app crashes (crash buffer / FATAL EXCEPTION / native crash
# of the app's process, monkey «CRASH»), the tile service throws while rendering. Warnings: ANR (slow software-rendered
# emulator), process not running after the start without a crash, tile could not be added (debug surface missing on
# this image — then the tile is not checked, said so in the warning).
#
# Usage: wear_smoke_test.sh <apk> <findings file> <out dir>
set -uo pipefail

APK="$1"
FINDINGS="$2"
OUT="$3"
PKG="com.cryptochecker.app"
TILE=".PriceTileService"
SEED=4242
EVENTS=200
mkdir -p "$OUT"
: > "$FINDINGS"

add() {
  local msg
  msg=$(printf '%s' "$2" | tr '\t' ' ' | awk 'BEGIN{ORS="\\n"} {print}' | sed 's/\\n$//')
  printf '%s\t\t\t%s\n' "$1" "$msg" >> "$FINDINGS"
}

alive() {
  local pid
  pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
  [ -n "$pid" ]
}

adb logcat -G 4M >/dev/null 2>&1 || true
adb logcat -c >/dev/null 2>&1 || true
adb logcat -b crash -c >/dev/null 2>&1 || true

INSTALL=$(adb install -r -g "$APK" 2>&1 | tr -d '\r')
if ! printf '%s' "$INSTALL" | grep -q Success; then
  add error "Wear OS: install failed: $(printf '%s' "$INSTALL" | tail -n 3)"
  exit 0
fi

# Launcher entry of the package (namespace of the activity does not matter)
COMPONENT=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tr -d '\r' | tail -n 1)
if [[ "$COMPONENT" != "$PKG/"* ]]; then
  add error "Wear OS: no launcher activity found for $PKG ($COMPONENT)"
  exit 0
fi
START=$(date +%s%3N)
START_OUT=$(adb shell am start -W -n "$COMPONENT" 2>&1 | tr -d '\r' || true)
ELAPSED=$(( $(date +%s%3N) - START ))
sleep 5
if ! alive; then
  add warning "Wear OS: process not running 5 s after the start ($(printf '%s' "$START_OUT" | grep -m1 -E 'Status|Error'))"
fi

# Price tile through the debug surface (Wear OS emulator): the system binds the tile service and renders the tile
TILE_COMPONENT="$PKG/$(printf '%s' "$COMPONENT" | cut -d/ -f2 | sed 's/\.[A-Za-z0-9_]*$//')$TILE"
TILE_OUT=$(adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile \
  --ecn component "$TILE_COMPONENT" 2>&1 | tr -d '\r' || true)
printf '%s\n' "$TILE_OUT" > "$OUT/tile-add.txt"
if printf '%s' "$TILE_OUT" | grep -qE 'result=1|Broadcast completed: result=-1'; then
  sleep 8
  adb shell dumpsys activity services "$PKG" > "$OUT/services.txt" 2>&1 || true
else
  add warning "Wear OS: tile $TILE_COMPONENT could not be added through the debug surface (not checked on this image): $(printf '%s' "$TILE_OUT" | tail -n 1)"
fi

MONKEY=$(adb shell monkey -p "$PKG" -s "$SEED" --throttle 150 --pct-syskeys 0 -v "$EVENTS" 2>&1 | tr -d '\r' || true)
printf '%s\n' "$MONKEY" > "$OUT/monkey.txt"
sleep 3

adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
CRASHES=$(adb logcat -b crash -d 2>/dev/null | tr -d '\r' | grep -A 14 -E "FATAL EXCEPTION|>>> $PKG <<<|Process: $PKG" | head -n 40 || true)
if [ -n "$CRASHES" ]; then
  add error "Wear OS: crash of $PKG:
$CRASHES"
elif printf '%s' "$MONKEY" | grep -q "CRASH"; then
  add error "Wear OS: monkey reported a crash: $(printf '%s' "$MONKEY" | grep -m3 -A5 CRASH)"
fi
if grep -qE "Exception.*(TileService|PriceTileService)|PriceTileService.*Exception" "$OUT/logcat.txt"; then
  add error "Wear OS: tile service threw: $(grep -m3 -E "Exception.*(TileService|PriceTileService)|PriceTileService.*Exception" "$OUT/logcat.txt")"
fi
if grep -qE "ANR in $PKG" "$OUT/logcat.txt"; then
  add warning "Wear OS: ANR in $PKG (slow software-rendered emulator?)"
fi
if ! grep -qE '^error' "$FINDINGS"; then
  add notice "Wear OS: started in ${ELAPSED} ms, $EVENTS monkey events without a crash"
fi
exit 0
