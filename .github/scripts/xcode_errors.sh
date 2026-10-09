#!/usr/bin/env bash
# Prints the compiler and test errors of an xcodebuild log and turns them into GitHub
# annotations (visible on the run page without signing in; GitHub shows up to 10 per step).
# Usage: xcode_errors.sh <log file>
LOG="$1"
[ -f "$LOG" ] || exit 0
ERRORS=$(grep -E '^/.+:[0-9]+:[0-9]+: error: |: error: -\[' "$LOG" | sort -u | head -n 40)
[ -n "$ERRORS" ] || exit 0
echo "=== Errors ==="
echo "$ERRORS"
while IFS= read -r line; do
  if [[ "$line" =~ ^(/[^:]+):([0-9]+):([0-9]+):\ error:\ (.*)$ ]]; then
    file="${BASH_REMATCH[1]#"$GITHUB_WORKSPACE"/}"
    echo "::error file=$file,line=${BASH_REMATCH[2]},col=${BASH_REMATCH[3]}::${BASH_REMATCH[4]}"
  else
    echo "::error::$line"
  fi
done <<< "$ERRORS"
exit 0
