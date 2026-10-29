#!/bin/bash
# Runs the instrumented test classes one by one (comma list in $1) and keeps each class's result XML under
# app/build/device-class-results/<class>/: one Gradle call with the whole list ran only the first class,
# and a crash in one class can no longer hide the others. Exit 1 if any class failed. Called from android/.
set -uo pipefail
status=0
OUT=app/build/device-class-results
rm -rf "$OUT"
IFS=',' read -r -a classes <<< "$1"
for c in "${classes[@]}"; do
  [ -n "$c" ] || continue
  echo "::group::$c"
  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="$c" --stacktrace || status=1
  mkdir -p "$OUT/$c"
  cp -R app/build/outputs/androidTest-results/connected/. "$OUT/$c/" 2>/dev/null || true
  echo "::endgroup::"
done
exit $status
