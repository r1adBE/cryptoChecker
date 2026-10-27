#!/usr/bin/env bash
# Turns the failure reasons in a Gradle console log into GitHub annotations, so a failed step shows
# them on the run page (job logs need a sign-in): Kotlin compile errors («e: file:///…:line:col …»),
# failed tests («… > test() FAILED» plus the next line) and the «What went wrong» blocks.
# Usage: gradle_errors.sh <gradle log>   (always exits 0; call it only after the Gradle step failed)
LOG="$1"
[ -f "$LOG" ] || { echo "No Gradle log at $LOG"; exit 0; }
esc() { local s="${1//%/%25}"; s="${s//$'\r'/}"; printf '%s' "${s//$'\n'/%0A}"; }
n=0
while IFS= read -r line && [ $n -lt 20 ]; do
  if [[ "$line" =~ ^e:\ (file://)?(/[^:]+):([0-9]+):([0-9]+)\ (.*)$ ]]; then
    file="${BASH_REMATCH[2]#"$GITHUB_WORKSPACE"/}"
    echo "::error file=$file,line=${BASH_REMATCH[3]},col=${BASH_REMATCH[4]},title=Kotlin::$(esc "${BASH_REMATCH[5]}")"
  else
    echo "::error title=Kotlin::$(esc "${line#e: }")"
  fi
  n=$((n + 1))
done < <(grep -E '^e: ' "$LOG" | sort -u)
grep -E -A1 ' FAILED$' "$LOG" | grep -v '^--$' | paste -d' ' - - | sort -u | head -n 15 | while IFS= read -r t; do
  echo "::error title=Test failed::$(esc "$t")"
done
awk '/^\* What went wrong:/{on=1; buf=""; next} on && /^\* (Try|Exception is):/{print buf; on=0; next} on && NF{buf = buf (buf ? "\n" : "") $0}' "$LOG" \
  | head -c 6000 | awk 'BEGIN{RS=""} {print}' | head -n 40 > "${RUNNER_TEMP:-/tmp}/gradle-wrong.txt"
if [ -s "${RUNNER_TEMP:-/tmp}/gradle-wrong.txt" ]; then
  echo "::error title=What went wrong::$(esc "$(cat "${RUNNER_TEMP:-/tmp}/gradle-wrong.txt")")"
fi
exit 0
