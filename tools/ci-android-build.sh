#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGDIR="$ROOT/build-certification"
mkdir -p "$LOGDIR"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" ]]; then
  echo "ERROR: ANDROID_SDK_ROOT/ANDROID_HOME is not set" >&2
  exit 2
fi
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
if command -v sdkmanager >/dev/null 2>&1; then
  yes | sdkmanager --licenses >/dev/null 2>&1 || true
  sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
fi
[[ -f "$SDK/platforms/android-37.0/android.jar" ]] || { echo "ERROR: Android Platform 37 missing" >&2; exit 3; }
AAPT2="$SDK/build-tools/36.0.0/aapt2"
[[ -x "$AAPT2" ]] || { echo "ERROR: aapt2 36.0.0 missing" >&2; exit 4; }
cd "$ROOT"
if [[ -f gradle/wrapper/gradle-wrapper.jar ]]; then GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then GRADLE=(gradle)
elif [[ -x "$ROOT/.toolchain/gradle-9.5.1/bin/gradle" ]]; then GRADLE=("$ROOT/.toolchain/gradle-9.5.1/bin/gradle")
else echo "ERROR: no usable Gradle 9.5.1 runner" >&2; exit 5; fi
run_phase(){
  local name="$1"; shift
  echo "===== $name =====" | tee "$LOGDIR/$name.log"
  "${GRADLE[@]}" --no-daemon --stacktrace --warning-mode all "$@" 2>&1 | tee -a "$LOGDIR/$name.log"
}
# Requested certification order. Each phase must complete before the next one starts.
run_phase 01-testDebugUnitTest :app:testDebugUnitTest
run_phase 02-lintDebug :app:lintDebug
run_phase 03-assembleDebug :app:assembleDebug
APK="$(find app/build/outputs/apk/debug -type f -name '*.apk' -print | sort | head -1)"
[[ -n "$APK" && -f "$APK" ]] || { echo "ERROR: assembleDebug completed without APK" >&2; exit 6; }
"$ROOT/tools/verify-apk.sh" "$APK" "$AAPT2" | tee "$LOGDIR/04-verify-apk.log"
run_phase 05-assembleDebugAndroidTest :app:assembleDebugAndroidTest
TEST_APK="$(find app/build/outputs/apk/androidTest -type f -name '*.apk' -print | sort | head -1)"
[[ -n "$TEST_APK" && -f "$TEST_APK" ]] || { echo "ERROR: assembleDebugAndroidTest completed without test APK" >&2; exit 9; }
"$ROOT/tools/verify-test-apk.sh" "$TEST_APK" "$AAPT2" | tee "$LOGDIR/06-verify-androidTest-apk.log"
# Run instrumentation only when a booted/connected Android target is actually present.
if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
  run_phase 07-connectedDebugAndroidTest :app:connectedDebugAndroidTest
else
  echo "SKIP: connectedDebugAndroidTest — no connected Android device/emulator" | tee "$LOGDIR/07-connectedDebugAndroidTest.log"
fi
printf 'FIRST_TEST_APK=%s\nANDROID_TEST_APK=%s\n' "$APK" "$TEST_APK" | tee "$LOGDIR/BUILD_RESULT.txt"
sha256sum "$APK" "$TEST_APK" | tee "$LOGDIR/SHA256.txt"
echo "FIRST_TEST_APK_CERTIFICATION=PASS"
