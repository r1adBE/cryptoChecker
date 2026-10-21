#!/usr/bin/env bash
# Smoke test of the signed R8 release APK on a running emulator (called from the script of
# reactivecircus/android-emulator-runner in android-device-tests.yml).
#   install (-g: runtime permissions granted, no notification prompt) -> start the launcher entry
#   -> process alive? -> monkey (300 events, fixed seed)
#   -> run the WorkManager jobs once (JobScheduler, best effort) -> crash buffer / ANR
# Writes findings for annotate.py (the step after the emulator prints them as annotations) and
# the logcat dump into <out dir>. Always exits 0: the annotate step decides.
#
# Errors: install fails, the app crashes (crash buffer / FATAL EXCEPTION / native crash of the
# app's process, monkey «CRASH»). Warnings: ANR (often the slow software-rendered emulator),
# process not running after the start without a crash, monkey aborted without a crash.
#
# Usage: android_smoke_test.sh <apk> <api level> <findings file> <out dir>
set -uo pipefail

APK="$1"
API="$2"
FINDINGS="$3"
OUT="$4"
PKG="com.cryptochecker.app"
SEED=4242
EVENTS=300
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
  if [ -z "$pid" ]; then
    pid=$(adb shell ps -A 2>/dev/null | tr -d '\r' | awk -v p="$PKG" '$NF==p {print $2}' | head -n 1 || true)
  fi
  [ -n "$pid" ]
}

adb wait-for-device
adb shell input keyevent 82 >/dev/null 2>&1 || true   # unlock screen
adb logcat -b all -c >/dev/null 2>&1 || adb logcat -c >/dev/null 2>&1 || true

if ! INSTALL_OUT=$(adb install -r -g "$APK" 2>&1); then
  add error "API $API: release APK install failed: $(printf '%s' "$INSTALL_OUT" | tail -n 3)"
  exit 0
fi

# Launcher entry: resolved on the device (the active activity-alias, today .LauncherOrange);
# fallback the alias from AndroidManifest.xml
COMPONENT=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER "$PKG" 2>/dev/null | tr -d '\r' | grep "^$PKG/" | tail -n 1 || true)
[ -n "$COMPONENT" ] || COMPONENT="$PKG/$PKG.LauncherOrange"
START_OUT=$(adb shell am start -W -n "$COMPONENT" 2>&1 | tr -d '\r' || true)
echo "$START_OUT"
TOTAL=$(printf '%s\n' "$START_OUT" | sed -n 's/^TotalTime: *\([0-9]*\).*/\1/p' | head -n 1)
if printf '%s\n' "$START_OUT" | grep -qE '^Error|Exception'; then
  add warning "API $API: am start $COMPONENT reported: $(printf '%s\n' "$START_OUT" | grep -m2 -E 'Error|Exception')"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
fi
sleep 10
STARTED_OK=1
if ! alive; then
  STARTED_OK=0
fi

# Monkey: fixed seed, no system keys (home/back/volume would only leave the app), restricted
# to the app's package
MONKEY_OUT=$(adb shell monkey -p "$PKG" -s "$SEED" --throttle 120 --pct-syskeys 0 \
  --ignore-security-exceptions -v "$EVENTS" 2>&1 | tr -d '\r' || true)
printf '%s\n' "$MONKEY_OUT" > "$OUT/monkey-api$API.txt"
printf '%s\n' "$MONKEY_OUT" | tail -n 15

# WorkManager jobs once (price update, alarms, widgets): run every job of the app now. Best effort,
# «cmd jobscheduler run -f» exists from Android 8.0; the ids come from dumpsys.
JOBS=$(adb shell dumpsys jobscheduler 2>/dev/null | tr -d '\r' \
  | sed -n "s/.*JOB #[^/]*\/\([0-9-]*\): [0-9a-f]* $PKG\/.*/\1/p" | sort -u | head -n 8 || true)
RAN=0
for id in $JOBS; do
  adb shell cmd jobscheduler run -f "$PKG" "$id" >/dev/null 2>&1 && RAN=$((RAN + 1))
done
[ "$RAN" -gt 0 ] && sleep 20
ALIVE_END="no"; alive && ALIVE_END="yes"

# --- Evaluation --------------------------------------------------------------------------------
adb logcat -d -b crash > "$OUT/logcat-crash-api$API.txt" 2>/dev/null || true
adb logcat -d -b main,system,events > "$OUT/logcat-api$API.txt" 2>/dev/null || adb logcat -d > "$OUT/logcat-api$API.txt" 2>/dev/null || true

CRASH=$( { cat "$OUT/logcat-crash-api$API.txt"; cat "$OUT/logcat-api$API.txt"; } 2>/dev/null \
  | grep -A 14 -E "FATAL EXCEPTION|>>> $PKG <<<" \
  | grep -B 2 -A 12 -E "Process: $PKG([^.:a-zA-Z]|$)|>>> $PKG <<<" | head -n 14 || true)
MONKEY_CRASH=$(printf '%s\n' "$MONKEY_OUT" | grep -A 8 '// CRASH' | head -n 10 || true)
ANR=$( { grep -h -E "ANR in $PKG" "$OUT/logcat-api$API.txt"; printf '%s\n' "$MONKEY_OUT" | grep -A 3 'NOT RESPONDING'; } 2>/dev/null | head -n 6 || true)

if [ -n "$CRASH" ] || [ -n "$MONKEY_CRASH" ]; then
  add error "API $API: release build crashed (R8 keep rules? see logcat artifact):
$(printf '%s\n' "${CRASH:-$MONKEY_CRASH}" | sed 's/^[0-9-]* [0-9:.]* *//' | head -n 12)"
fi
if [ -n "$ANR" ]; then
  add warning "API $API: ANR during the smoke test (can be the slow emulator):
$ANR"
fi
if [ "$STARTED_OK" -eq 0 ] && [ -z "$CRASH" ]; then
  add warning "API $API: process $PKG not running 10 s after starting $COMPONENT (no crash logged)"
fi
if [ -z "$MONKEY_CRASH" ] && ! printf '%s\n' "$MONKEY_OUT" | grep -q 'Monkey finished'; then
  add warning "API $API: monkey did not finish: $(printf '%s\n' "$MONKEY_OUT" | tail -n 3)"
fi
if [ -z "$CRASH" ] && [ -z "$MONKEY_CRASH" ]; then
  add notice "API $API: release smoke test passed: start ${TOTAL:-?} ms ($COMPONENT), monkey $EVENTS events (seed $SEED), $RAN WorkManager job(s) run, process alive at end: $ALIVE_END"
fi
exit 0
