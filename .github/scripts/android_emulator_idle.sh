#!/usr/bin/env bash
# Keeps system error dialogs («Pixel Launcher isn't responding», crash dialogs) out of the screenshots of the
# Android emulator. A slow software-rendered emulator can show such a dialog over the app: every screenshot is then
# dimmed and covered, while the tests under it still pass.
# Usage (adb must reach exactly one device):
#   android_emulator_idle.sh prepare   before the tour: wait for the end of the boot, switch the error dialogs off
#                                      (settings global hide_error_dialogs=1), close open system dialogs and wait
#                                      until a window has the focus and no error dialog is left
#   android_emulator_idle.sh check     after the tour: exit 1 if an error dialog is open (a screenshot may be covered)
# Both print the focused window. Exit 1 with a GitHub annotation when the device does not get idle in time.
set -u
MODE="${1:-}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
IDLE_TIMEOUT="${IDLE_TIMEOUT:-180}"
# Window titles of the system dialogs «… isn't responding» and «… keeps stopping» in «dumpsys window»
DIALOGS='Application Not Responding|Application Error'

windows() { adb shell dumpsys window 2>/dev/null | tr -d '\r'; }
focus() { printf '%s\n' "$1" | grep -m1 'mCurrentFocus' | sed 's/^ *//'; }
dialogs() { printf '%s\n' "$1" | grep -oE "($DIALOGS)[^}]*" | sort -u | paste -sd';' -; }

case "$MODE" in
  prepare)
    end=$((SECONDS + BOOT_TIMEOUT))
    until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
      if [ $SECONDS -ge $end ]; then
        echo "::error title=Android emulator::boot not completed after ${BOOT_TIMEOUT}s"
        exit 1
      fi
      sleep 2
    done
    adb shell settings put global hide_error_dialogs 1 || { echo "::error title=Android emulator::hide_error_dialogs not set"; exit 1; }
    # Three quiet looks in a row, two seconds apart: a dialog that comes back (launcher still starting) starts over
    end=$((SECONDS + IDLE_TIMEOUT))
    quiet=0
    while [ $quiet -lt 3 ]; do
      dump="$(windows)"
      open="$(dialogs "$dump")"
      current="$(focus "$dump")"
      if [ -n "$open" ]; then
        echo "closing: $open"
        adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
        quiet=0
      elif [ -z "$current" ] || printf '%s' "$current" | grep -q 'mCurrentFocus=null'; then
        quiet=0
      else
        quiet=$((quiet + 1))
      fi
      if [ $quiet -lt 3 ]; then
        if [ $SECONDS -ge $end ]; then
          echo "::error title=Android emulator::not idle after ${IDLE_TIMEOUT}s: ${open:-no error dialog}; ${current:-no focused window}"
          exit 1
        fi
        sleep 2
      fi
    done
    echo "idle: $current"
    ;;
  check)
    dump="$(windows)"
    current="$(focus "$dump")"
    if [ -z "$current" ]; then
      echo "::error title=Android screenshots::no window state from the device (adb shell dumpsys window)"
      exit 1
    fi
    open="$(dialogs "$dump")"
    if [ -n "$open" ]; then
      echo "::error title=Android screenshots::system dialog open after the tour, screenshots may be covered: $open"
      exit 1
    fi
    echo "no system dialog: $current"
    ;;
  *)
    echo "usage: $0 prepare|check" >&2
    exit 2
    ;;
esac
