#!/usr/bin/env bash
set -euo pipefail
APK="${1:?usage: verify-test-apk.sh TEST_APK [AAPT2]}"
AAPT2="${2:-}"
[[ -f "$APK" ]] || { echo "ERROR: androidTest APK not found: $APK" >&2; exit 2; }
if [[ -z "$AAPT2" ]]; then
  SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  [[ -n "$SDK" ]] || { echo "ERROR: AAPT2 not supplied and Android SDK root is not set" >&2; exit 3; }
  AAPT2="$(find "$SDK/build-tools" -maxdepth 2 -type f -name aapt2 -print | sort -V | tail -1)"
fi
[[ -x "$AAPT2" ]] || { echo "ERROR: aapt2 not executable: $AAPT2" >&2; exit 4; }
BADGING="$($AAPT2 dump badging "$APK")"
OUT="${TEST_APK_BADGING_OUT:-$PWD/build-certification/ANDROID_TEST_APK_BADGING.txt}"
mkdir -p "$(dirname "$OUT")"
printf '%s\n' "$BADGING" | tee "$OUT"
printf '%s\n' "$BADGING" | grep -F "package: name='pro.noodle.mobile.test'" >/dev/null || { echo "ERROR: androidTest package mismatch" >&2; exit 5; }
TARGET_SDK="$(printf '%s\n' "$BADGING" | sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" | head -1)"
[[ "$TARGET_SDK" == "37" ]] || { echo "ERROR: androidTest targetSdk mismatch: expected 37, actual ${TARGET_SDK:-<missing>}" >&2; exit 6; }
unzip -t "$APK" >/dev/null || { echo "ERROR: androidTest APK ZIP integrity failed" >&2; exit 7; }
echo "ANDROID_TEST_APK=$APK"
sha256sum "$APK"
echo "ANDROID_TEST_APK_CERTIFICATION=PASS"
