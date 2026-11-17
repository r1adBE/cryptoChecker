#!/usr/bin/env bash
# Swift style: SwiftLint (pinned portable macOS release, checked by SHA-256) over the iOS and watch sources and tests,
# with the rules of ios/.swiftlint.yml (140 columns, ceilings for complexity and parameters). Each finding becomes an
# error annotation; the job fails on any finding (--strict). Not part of the app or its build — only this check
# downloads it. Needs the selected Xcode (SourceKit).
# Usage: bash swiftlint.sh [repository root, default: current directory]
set -euo pipefail
ROOT="${1:-.}"
VERSION="0.59.1"
SHA256="58f9be8a4677900c945e2c618168223f4dd620a0cc65c9ccc5ea0f70433e89c1"
DIR="${RUNNER_TEMP:-/tmp}/swiftlint-$VERSION"
BIN="$DIR/swiftlint"
if [ ! -x "$BIN" ]; then
  mkdir -p "$DIR"
  curl -sSfL --retry 4 --retry-delay 5 -o "$DIR/swiftlint.zip" \
    "https://github.com/realm/SwiftLint/releases/download/$VERSION/portable_swiftlint.zip"
  echo "$SHA256  $DIR/swiftlint.zip" | shasum -a 256 -c --quiet -
  unzip -q -o "$DIR/swiftlint.zip" swiftlint -d "$DIR"
  chmod +x "$BIN"
fi
cd "$ROOT/ios"
OUT="$(mktemp)"
if "$BIN" lint --quiet --strict --reporter github-actions-logging >"$OUT" 2>&1; then
  echo "SwiftLint: no findings"
  exit 0
fi
# Paths are relative to ios/; annotations need them from the repository root
sed -E 's#file=([^/][^,]*)#file=ios/\1#' "$OUT"
echo "SwiftLint: $(grep -c '^::' "$OUT" || true) finding(s) - run «swiftlint --fix» in ios/ for the correctable ones"
exit 1
