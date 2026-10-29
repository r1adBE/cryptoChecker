#!/usr/bin/env bash
# Audit of the Android release build (after :app:assembleRelease :app:bundleRelease :wear:assembleRelease). Run in
# android/.
# Writes findings for annotate.py and collects the release evidence (merged manifest, R8 mapping,
# AAB, manifest summary) in <evidence dir>.
#
# Errors (release blockers): APK/AAB/R8 mapping missing, manifest policy (manifest_policy.py),
# debuggable APK (aapt2). Warnings: merged manifest not found, baseline profile missing,
# 16 KB alignment (zipalign -c -P 16, ELF segments). The Wear OS app (wear/, own Play bundle): release APK missing and
# debuggable are errors, 16 KB alignment a warning (its manifest has other components and permissions, so
# manifest_policy.py covers the phone app only). Tools that are missing on the runner
# (aapt2, zipalign >= 35, apkanalyzer) are skipped with a notice.
#
# Usage: android_release_audit.sh <findings file> <evidence dir>
set -uo pipefail

FINDINGS="$1"
EVIDENCE="$2"
SCRIPTS="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$EVIDENCE"
: > "$FINDINGS"

add() { # add <level> <message>; line breaks become a literal \n (decoded by annotate.py)
  local msg
  msg=$(printf '%s' "$2" | tr '\t' ' ' | awk 'BEGIN{ORS="\\n"} {print}' | sed 's/\\n$//')
  printf '%s\t\t\t%s\n' "$1" "$msg" >> "$FINDINGS"
}

# Newest build-tools directory (sorted by version), empty if there is no SDK
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BUILD_TOOLS=""
if [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
  BUILD_TOOLS=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
fi

# Debuggable (aapt2, error) and 16 KB zip alignment of uncompressed native libraries (zipalign -P needs build-tools
# 35+, warning) of one APK. Usage: apk_checks <label> <apk>
apk_checks() {
  local label="$1" apk="$2"
  local aapt2="$BUILD_TOOLS/aapt2" zipalign="$BUILD_TOOLS/zipalign" bt_major badging version_line za_out bad
  if [ -n "$BUILD_TOOLS" ] && [ -x "$aapt2" ]; then
    badging=$("$aapt2" dump badging "$apk" 2>/dev/null || true)
    if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
      add error "$label is debuggable (aapt2 dump badging: application-debuggable)"
    fi
    version_line=$(printf '%s\n' "$badging" | grep -m1 '^package:' || true)
    [ -n "$version_line" ] && add notice "aapt2 ($label): $version_line"
  else
    add notice "aapt2 not found in the Android SDK build-tools: debuggable check on the $label skipped"
  fi

  bt_major=$(basename "${BUILD_TOOLS:-0}" | cut -d. -f1)
  if [ -n "$BUILD_TOOLS" ] && [ -x "$zipalign" ] && [ "${bt_major:-0}" -ge 35 ] 2>/dev/null; then
    if za_out=$("$zipalign" -c -P 16 -v 4 "$apk" 2>&1); then
      add notice "zipalign -c -P 16: $label is 16 KB aligned"
    else
      bad=$(printf '%s\n' "$za_out" | grep -E 'BAD|ERROR|rror' | head -n 6)
      add warning "zipalign -c -P 16 failed for the $label (16 KB page alignment):
${bad:-$(printf '%s\n' "$za_out" | tail -n 4)}"
    fi
  else
    add notice "zipalign with -P (build-tools 35+) not found: 16 KB zip alignment check of the $label skipped"
  fi
}

# --- Outputs -------------------------------------------------------------------------------
APK=$(find app/build/outputs/apk/release -maxdepth 1 -name '*.apk' 2>/dev/null | sort | head -n 1)
WEAR_APK=$(find wear/build/outputs/apk/release -maxdepth 1 -name '*.apk' 2>/dev/null | sort | head -n 1)
AAB=$(find app/build/outputs/bundle/release -maxdepth 1 -name '*.aab' 2>/dev/null | sort | head -n 1)
MAPPING="app/build/outputs/mapping/release/mapping.txt"

[ -n "$APK" ] || add error "Release APK missing (app/build/outputs/apk/release/*.apk) after :app:assembleRelease"
[ -n "$AAB" ] || add error "Release AAB missing (app/build/outputs/bundle/release/*.aab) after :app:bundleRelease"
[ -n "$WEAR_APK" ] || add error "Wear OS release APK missing (wear/build/outputs/apk/release/*.apk) after :wear:assembleRelease"
if [ -s "$MAPPING" ]; then
  cp "$MAPPING" "$EVIDENCE/" || true
else
  add error "R8 mapping missing or empty ($MAPPING): isMinifyEnabled is on, so R8 did not run for release"
fi
[ -n "$AAB" ] && cp "$AAB" "$EVIDENCE/" || true
[ -f app/build/outputs/logs/manifest-merger-release-report.txt ] && \
  cp app/build/outputs/logs/manifest-merger-release-report.txt "$EVIDENCE/" || true

# --- Merged manifest ------------------------------------------------------------------------
# The packaged manifest is what ends up in the APK; the merged one is the fallback. Only the
# variant «release» (not nonMinifiedRelease/benchmarkRelease of the baseline profile plugin).
MANIFEST=""
for kind in packaged_manifests merged_manifest merged_manifests; do
  MANIFEST=$(find app/build/intermediates -path "*/$kind/release/*" -name AndroidManifest.xml 2>/dev/null | sort | head -n 1)
  [ -n "$MANIFEST" ] && break
done
if [ -n "$MANIFEST" ]; then
  cp "$MANIFEST" "$EVIDENCE/AndroidManifest-release-merged.xml" || true
  python3 -I "$SCRIPTS/manifest_policy.py" "$MANIFEST" "$FINDINGS" "$EVIDENCE/manifest-summary.txt"
else
  CANDIDATES=$(find app/build/intermediates -name AndroidManifest.xml -path '*elease*' 2>/dev/null | head -n 8)
  add warning "Merged release manifest not found under app/build/intermediates (manifest policy skipped). Candidates:
${CANDIDATES:-none}"
fi

# --- APK / AAB contents (baseline profile, native libraries, sizes) ----------------------------
if [ -n "$APK" ]; then
  python3 -I "$SCRIPTS/apk_contents.py" "$APK" "${AAB:--}" "$FINDINGS"

  apk_checks "Release APK" "$APK"

  # apkanalyzer (cmdline-tools) only if installed
  APKANALYZER=""
  for c in "$SDK/cmdline-tools/latest/bin/apkanalyzer" "$SDK/tools/bin/apkanalyzer"; do
    [ -n "$SDK" ] && [ -x "$c" ] && { APKANALYZER="$c"; break; }
  done
  if [ -n "$APKANALYZER" ]; then
    DL=$("$APKANALYZER" apk download-size "$APK" 2>/dev/null || true)
    FS=$("$APKANALYZER" apk file-size "$APK" 2>/dev/null || true)
    [ -n "$DL$FS" ] && add notice "apkanalyzer: file size ${FS:-?} bytes, estimated download size ${DL:-?} bytes"
  else
    add notice "apkanalyzer not installed: download size estimate skipped"
  fi
fi

# --- Wear OS app ------------------------------------------------------------------------------
[ -n "$WEAR_APK" ] && apk_checks "Wear OS release APK" "$WEAR_APK"

echo "Findings written to $FINDINGS"
exit 0
