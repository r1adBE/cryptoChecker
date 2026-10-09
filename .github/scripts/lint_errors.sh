#!/usr/bin/env bash
# Prints the errors of the Android lint text report and turns them into GitHub annotations
# (visible on the run page without signing in; GitHub shows up to 10 per step).
# Usage: lint_errors.sh <lint-results-*.txt>
REPORT="$1"
[ -f "$REPORT" ] || { echo "No lint report at $REPORT"; exit 0; }
ERRORS=$(grep -E ': Error: ' "$REPORT" | sort -u | head -n 60)
[ -n "$ERRORS" ] || exit 0
echo "=== Lint errors ==="
echo "$ERRORS"
while IFS= read -r line; do
  if [[ "$line" =~ ^(/[^:]+):([0-9]+):\ Error:\ (.*)$ ]]; then
    file="${BASH_REMATCH[1]#"$GITHUB_WORKSPACE"/}"
    echo "::error file=$file,line=${BASH_REMATCH[2]}::${BASH_REMATCH[3]}"
  elif [[ "$line" =~ ^(/[^:]+):\ Error:\ (.*)$ ]]; then
    file="${BASH_REMATCH[1]#"$GITHUB_WORKSPACE"/}"
    echo "::error file=$file::${BASH_REMATCH[2]}"
  else
    echo "::error::$line"
  fi
done <<< "$ERRORS"
exit 0
