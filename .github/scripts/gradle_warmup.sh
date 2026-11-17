#!/usr/bin/env bash
# Gradle warm-up: configures the build once (`./gradlew help`) so the plugins (Kotlin, Compose, KSP, Hilt, AGP …) are
# resolved and in the local Gradle cache before the real steps run. A plugin repository that does not answer for a
# moment («Plugin [id: '…'] was not found in any of the following sources», «Could not resolve», connection reset or
# timeout) failed whole jobs with no code reason; here only such network failures are retried (5 attempts, 30 s to
# 5 min apart). Any other failure — a script error, a dependency verification failure — stops at once with the
# «What went wrong» lines as annotations (gradle_errors.sh), so a real problem is never retried away.
# Usage (working directory android/): bash gradle_warmup.sh
set -uo pipefail
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
LOG="${RUNNER_TEMP:-/tmp}/gradle-warmup.log"
NETWORK='was not found in any of the following sources|Could not resolve|Could not GET|Could not HEAD|Connection reset|Connect timed out|Read timed out|Remote host terminated the handshake|status code 5[0-9][0-9]|Received status code 429'
chmod +x gradlew
for attempt in 1 2 3 4 5; do
  # From the second attempt on, bypass cached repository answers: a missing or partial entry restored from the cache
  # (the same plugin «not found» in every attempt of one job while the other jobs resolved it) is otherwise repeated
  # on every attempt instead of being fetched again
  refresh=$([ "$attempt" -gt 1 ] && echo --refresh-dependencies || true)
  if ./gradlew --no-daemon help --quiet $refresh >"$LOG" 2>&1; then
    echo "Gradle warm-up ok (attempt $attempt)"
    exit 0
  fi
  if grep -q 'Dependency verification failed' "$LOG" || ! grep -Eq "$NETWORK" "$LOG"; then
    echo "::error title=Gradle warm-up::the build could not be configured (not a network failure, not retried)"
    bash "$SCRIPTS/gradle_errors.sh" "$LOG"
    tail -n 40 "$LOG"
    exit 1
  fi
  if [ "$attempt" -lt 5 ]; then
    # Up to about ten minutes: an outage of the plugin portal for one runner lasted longer than two minutes
    wait=$(case "$attempt" in 1) echo 30 ;; 2) echo 90 ;; 3) echo 180 ;; *) echo 300 ;; esac)
    echo "::warning title=Gradle warm-up::plugin or dependency repository did not answer (attempt $attempt of 5), retrying in ${wait}s"
    grep -E -m 3 "$NETWORK" "$LOG"
    sleep "$wait"
  fi
done
echo "::error title=Gradle warm-up::plugin or dependency repository did not answer in 5 attempts (network, not the code): re-run the job"
bash "$SCRIPTS/gradle_errors.sh" "$LOG"
tail -n 40 "$LOG"
exit 1
