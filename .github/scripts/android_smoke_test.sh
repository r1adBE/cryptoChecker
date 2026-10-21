#!/usr/bin/env bash
# Smoke test of the signed R8 release APK on a running emulator (called from the script of
# reactivecircus/android-emulator-runner in android-device-tests.yml).
#   install (-g: runtime permissions granted, no notification prompt) -> start the launcher entry
#   -> process alive? -> seed the watchlist (tap «starter_add») -> reinstall (MY_PACKAGE_REPLACED)
#   -> run the WorkManager jobs once (JobScheduler) -> worker check -> monkey (300 events, fixed seed)
#   -> crash buffer / ANR
# Why the seeding: on a fresh install the watchlist is empty and nothing schedules work, so the
# R8-shrunk worker path (WorkerFactory, Hilt/reflection) would never run. The starter button carries
# the Compose testTag «starter_add», exposed as resource-id (testTagsAsResourceId in WatchlistScreen),
# found with «uiautomator dump». The seeding runs BEFORE the monkey, which may navigate away.
# Writes findings for annotate.py (the step after the emulator prints them as annotations) and
# the logcat dump into <out dir>. Always exits 0: the annotate step decides.
#
# Errors: install fails, the app crashes (crash buffer / FATAL EXCEPTION / native crash of the
# app's process, monkey «CRASH»). Warnings: ANR (often the slow software-rendered emulator),
# process not running after the start without a crash, monkey aborted without a crash.
# Worker check (after forcing the jobs): «Could not create Worker» / «Could not instantiate» from
# WM-WorkerWrapper / WM-WorkerFactory -> error (missing keep rule for a worker). Jobs forced but no
# «Worker result» line -> warning. The release build logs WorkManager at INFO
# (setMinimumLoggingLevel in CryptoCheckerApp; WorkManager logs through android.util.Log, not the
# R8-stripped Timber.i), so both lines reach logcat. No job at all -> warning with the seed result.
# Evidence in <out dir>: ui-*.xml (last dump), jobscheduler-*.txt, logcat-jobs-*.txt.
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

# Centre and enabled state of the node with resource-id $1 in the UI dump on stdin: "x y true|false"
node_center() {
  python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
tag = sys.argv[1]
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    sys.exit(1)
for n in root.iter("node"):
    rid = n.get("resource-id", "")
    if rid == tag or rid.endswith(":id/" + tag):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2, n.get("enabled", "true"))
            sys.exit(0)
sys.exit(1)
' "$1"
}

ui_dump() {
  adb shell uiautomator dump /sdcard/cc-ui.xml >/dev/null 2>&1 || return 1
  adb shell cat /sdcard/cc-ui.xml 2>/dev/null | tr -d '\r'
}

# Fresh install: tap «Add selected» of the starter (enabled once the starter coins are loaded),
# then expect a watchlist row. Result in SEED_RESULT for the annotations.
seed_watchlist() {
  local i xml pos x y en
  SEED_RESULT="starter button not found"
  for i in 1 2 3 4 5 6 7 8; do
    xml=$(ui_dump || true)
    [ -n "$xml" ] && printf '%s\n' "$xml" > "$OUT/ui-api$API.xml"
    if [ -n "$xml" ] && printf '%s' "$xml" | node_center watch_row >/dev/null; then
      [ "$SEED_RESULT" = "tapped" ] && SEED_RESULT="seeded" || SEED_RESULT="watchlist already filled"
      return 0
    fi
    pos=$(printf '%s' "${xml:-<x/>}" | node_center starter_add || true)
    if [ -n "$pos" ]; then
      read -r x y en <<< "$pos"
      if [ "$en" = "true" ]; then
        adb shell input tap "$x" "$y" >/dev/null 2>&1 || true
        SEED_RESULT="tapped"
        sleep 6
        continue
      fi
      SEED_RESULT="starter button disabled (coins not loaded?)"
    elif [ -n "$xml" ]; then
      # Below the fold on small screens: scroll the starter up
      adb shell input swipe 540 1600 540 600 300 >/dev/null 2>&1 || true
    else
      SEED_RESULT="uiautomator dump failed"
    fi
    sleep 5
  done
  [ "$SEED_RESULT" = "tapped" ] && SEED_RESULT="tapped, but no watchlist row seen"
  return 1
}

adb wait-for-device
adb shell input keyevent 82 >/dev/null 2>&1 || true   # unlock screen
# Larger ring buffer: the monkey must not push the WorkManager lines out before the dump
adb logcat -G 4M >/dev/null 2>&1 || true
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

# Seed the watchlist, so the app schedules its WorkManager jobs
SEED_RESULT="skipped (process not running)"
if [ "$STARTED_OK" -eq 1 ]; then
  seed_watchlist || true
  sleep 10
  # Reinstall with data: MY_PACKAGE_REPLACED -> BootReceiver re-plans the work like after an update
  if adb install -r -g "$APK" >/dev/null 2>&1; then
    SEED_RESULT="$SEED_RESULT, reinstalled"
    sleep 10
  fi
fi
echo "Seed: $SEED_RESULT"

# WorkManager jobs once (price update, alarms, widgets): run every job of the app now. «cmd
# jobscheduler run -f» exists from Android 8.0; the ids come from dumpsys. Formats differ by
# release: «JOB #u0a190/12: 1a2b com.cryptochecker.app/…» (up to 13 and without namespace),
# with a namespace from Android 14 «JOB ns:u0a190/12: …» (printUniqueId) and variants such as
# «JOB #u0a190/@ns@/12: …». parse_jobs prints «<namespace or -> <id>» per job of $PKG; a job in a
# namespace needs «run -n <namespace>» (Android 14+).
parse_jobs() {
  sed -n -E "s#^.*JOB ([^ ]*)/(-?[0-9]+): [0-9a-f]+ $PKG/.*#\1 \2#p" | while read -r prefix id; do
    ns="-"
    case "$prefix" in
      *@*@*) ns=$(printf '%s' "$prefix" | sed -E 's#^[^@]*@([^@]*)@.*#\1#') ;;
      \#*) ns="-" ;;
      *:*) ns=${prefix%%:*} ;;
    esac
    printf '%s %s\n' "${ns:--}" "$id"
  done
}
JOBDUMP=$(adb shell dumpsys jobscheduler 2>/dev/null | tr -d '\r' || true)
printf '%s\n' "$JOBDUMP" | grep -A 25 -E "JOB [^ ]*: [0-9a-f]+ $PKG/" > "$OUT/jobscheduler-api$API.txt" 2>/dev/null || true
JOBS=$(printf '%s\n' "$JOBDUMP" | parse_jobs | sort -u | head -n 8 || true)
if [ -z "$JOBS" ]; then
  # Fallback 1: dump filtered to the package (shorter, «Registered N jobs» section of the app only)
  PKGDUMP=$(adb shell dumpsys jobscheduler "$PKG" 2>/dev/null | tr -d '\r' || true)
  printf '%s\n' "$PKGDUMP" > "$OUT/jobscheduler-pkg-api$API.txt" 2>/dev/null || true
  JOBS=$(printf '%s\n' "$PKGDUMP" | parse_jobs | sort -u | head -n 8 || true)
  if [ -z "$JOBS" ]; then
    # Fallback 2: lenient — any «/<id>:» right after a uid (u0a190) on a line that names the
    # package's SystemJobService, inside the package-filtered dump only
    JOBS=$(printf '%s\n' "$PKGDUMP" | grep -E "$PKG/[^ ]*SystemJobService" \
      | sed -n -E 's#.*u[0-9]+a[0-9]+/(@[^@]*@/)?(-?[0-9]+):.*#- \2#p' | sort -u | head -n 8 || true)
  fi
fi
echo "Jobs found: $(printf '%s' "$JOBS" | tr '\n' ',' )"
RAN=0
# </dev/null: adb shell would otherwise swallow the remaining lines of the loop's input
while read -r ns id; do
  [ -n "${id:-}" ] || continue
  if [ "$ns" = "-" ]; then
    adb shell cmd jobscheduler run -f "$PKG" "$id" </dev/null >/dev/null 2>&1 && RAN=$((RAN + 1))
  else
    adb shell cmd jobscheduler run -f -n "$ns" "$PKG" "$id" </dev/null >/dev/null 2>&1 && RAN=$((RAN + 1))
  fi
done <<< "$JOBS"
[ "$RAN" -gt 0 ] && sleep 20
adb logcat -d -b main,system > "$OUT/logcat-jobs-api$API.txt" 2>/dev/null || adb logcat -d > "$OUT/logcat-jobs-api$API.txt" 2>/dev/null || true
WORKER_FAIL=$(grep -h -E "WM-Worker(Wrapper|Factory).*(Could not create Worker|Could not instantiate)" \
  "$OUT/logcat-jobs-api$API.txt" 2>/dev/null | head -n 6 || true)
WORKER_RESULTS=$(grep -c -E "WM-WorkerWrapper.*Worker result" "$OUT/logcat-jobs-api$API.txt" 2>/dev/null || true)
WORKER_RESULTS=${WORKER_RESULTS:-0}

# The reinstall killed the process: start the app again before the monkey
adb shell am start -W -n "$COMPONENT" >/dev/null 2>&1 || true
sleep 3

# Monkey: fixed seed, no system keys (home/back/volume would only leave the app), restricted
# to the app's package
MONKEY_OUT=$(adb shell monkey -p "$PKG" -s "$SEED" --throttle 120 --pct-syskeys 0 \
  --ignore-security-exceptions -v "$EVENTS" 2>&1 | tr -d '\r' || true)
printf '%s\n' "$MONKEY_OUT" > "$OUT/monkey-api$API.txt"
printf '%s\n' "$MONKEY_OUT" | tail -n 15
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
if [ -n "$WORKER_FAIL" ]; then
  add error "API $API: WorkManager could not create a worker (R8 keep rule for the worker class or its factory?):
$(printf '%s\n' "$WORKER_FAIL" | sed 's/^[0-9-]* [0-9:.]* *//')"
fi
if [ "$RAN" -eq 0 ] && [ "$WORKER_RESULTS" -gt 0 ] && [ -z "$WORKER_FAIL" ]; then
  # Newer dumpsys formats (namespaces) can hide the job ids; the workers ran on their own schedule anyway
  add notice "API $API: no job id parsed from dumpsys jobscheduler, but $WORKER_RESULTS worker result(s) in logcat without creation errors"
elif [ "$RAN" -eq 0 ]; then
  add warning "API $API: no WorkManager job found in dumpsys jobscheduler after seeding (seed: $SEED_RESULT); worker path not exercised"
elif [ "$WORKER_RESULTS" -eq 0 ] && [ -z "$WORKER_FAIL" ]; then
  add warning "API $API: $RAN job(s) forced, but no «Worker result» line from WM-WorkerWrapper in logcat (periodic work run before its schedule is skipped; see jobscheduler-api$API.txt)"
fi
if [ -z "$CRASH" ] && [ -z "$MONKEY_CRASH" ] && [ -z "$WORKER_FAIL" ]; then
  add notice "API $API: release smoke test passed: start ${TOTAL:-?} ms ($COMPONENT), monkey $EVENTS events (seed $SEED), seed: $SEED_RESULT, $RAN WorkManager job(s) run ($WORKER_RESULTS worker result(s)), process alive at end: $ALIVE_END"
fi
exit 0
