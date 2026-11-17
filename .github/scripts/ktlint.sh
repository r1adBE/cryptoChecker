#!/usr/bin/env bash
# Kotlin style: ktlint (pinned release, checked by SHA-256) over all Kotlin sources and Gradle scripts under android/,
# with the rules of android/.editorconfig (Android Studio style, 140 columns). Each finding becomes an error
# annotation; the job fails on any finding. Not part of the app or its build — only this check downloads it.
# Usage: bash ktlint.sh [repository root, default: current directory]
set -euo pipefail
ROOT="${1:-.}"
VERSION="1.7.1"
SHA256="b596a05f09364e1997b644152683af12fef58944f3acfabdf78071f5a490eb98"
BIN="${RUNNER_TEMP:-/tmp}/ktlint-$VERSION"
if [ ! -x "$BIN" ]; then
  curl -sSfL --retry 4 --retry-delay 5 -o "$BIN" "https://github.com/pinterest/ktlint/releases/download/$VERSION/ktlint"
  echo "$SHA256  $BIN" | sha256sum -c --quiet -
  chmod +x "$BIN"
fi
cd "$ROOT/android"
OUT="$(mktemp)"
if "$BIN" --relative "**/src/**/*.kt" "**/*.kts" "!**/build/**" >"$OUT" 2>&1; then
  echo "ktlint: no findings"
  exit 0
fi
# «path:line:col: message (rule)» → annotation; other lines (summary) as they are
while IFS= read -r line; do
  if [[ "$line" =~ ^([^:]+\.kts?):([0-9]+):([0-9]+):\ (.*)$ ]]; then
    msg="${BASH_REMATCH[4]//%/%25}"
    echo "::error file=android/${BASH_REMATCH[1]},line=${BASH_REMATCH[2]},col=${BASH_REMATCH[3]},title=ktlint::$msg"
  fi
done <"$OUT"
grep -c -E '^[^:]+\.kts?:[0-9]+:' "$OUT" | xargs -I{} echo "ktlint: {} finding(s) - run «ktlint -F» in android/ to fix the formatting"
exit 1
