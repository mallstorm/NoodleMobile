#!/usr/bin/env bash
set -euo pipefail
APK="${1:?usage: verify-apk.sh APK [AAPT2]}"
AAPT2="${2:-}"
[[ -f "$APK" ]] || { echo "ERROR: APK not found: $APK" >&2; exit 2; }
if [[ -z "$AAPT2" ]]; then
  SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  [[ -n "$SDK" ]] || { echo "ERROR: AAPT2 not supplied and Android SDK root is not set" >&2; exit 3; }
  AAPT2="$(find "$SDK/build-tools" -maxdepth 2 -type f -name aapt2 -print | sort -V | tail -1)"
fi
[[ -x "$AAPT2" ]] || { echo "ERROR: aapt2 not executable: $AAPT2" >&2; exit 4; }
BADGING="$($AAPT2 dump badging "$APK")"
OUT="${APK_BADGING_OUT:-$PWD/build-certification/APK_BADGING.txt}"
mkdir -p "$(dirname "$OUT")"
printf '%s\n' "$BADGING" | tee "$OUT"
printf '%s\n' "$BADGING" | grep -F "package: name='pro.noodle.mobile' versionCode='120000' versionName='0.12.0'" >/dev/null || { echo "ERROR: APK identity mismatch" >&2; exit 5; }
MIN_SDK="$(printf '%s\n' "$BADGING" | sed -n -e "s/^minSdkVersion:'\([^']*\)'.*/\1/p" -e "s/^sdkVersion:'\([^']*\)'.*/\1/p" | head -1)"
TARGET_SDK="$(printf '%s\n' "$BADGING" | sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" | head -1)"
echo "APK_BADGING_MIN_SDK=${MIN_SDK:-<missing>}"
echo "APK_BADGING_TARGET_SDK=${TARGET_SDK:-<missing>}"
[[ "$MIN_SDK" == "28" ]] || { echo "ERROR: APK minSdk mismatch: expected 28, actual ${MIN_SDK:-<missing>}" >&2; exit 6; }
[[ "$TARGET_SDK" == "37" ]] || { echo "ERROR: APK targetSdk mismatch: expected 37, actual ${TARGET_SDK:-<missing>}" >&2; exit 7; }
unzip -t "$APK" >/dev/null || { echo "ERROR: APK ZIP integrity failed" >&2; exit 8; }
unzip -l "$APK" | grep -E '(^|[[:space:]])classes[0-9]*\.dex$' >/dev/null || { echo "ERROR: APK contains no DEX" >&2; exit 9; }
unzip -l "$APK" | grep -F 'resources.arsc' >/dev/null || { echo "ERROR: APK contains no resources.arsc" >&2; exit 10; }
echo "APK=$APK"
sha256sum "$APK"
echo "APK_CERTIFICATION=PASS"
