#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
echo "Noodle Mobile alpha10 build preflight"
echo "Java:"; java -version 2>&1 | head -3
LOCAL_GRADLE="$ROOT/.toolchain/gradle-9.5.1/bin/gradle"
if command -v gradle >/dev/null; then echo "System Gradle:"; gradle -v | head -8
elif [[ -x "$LOCAL_GRADLE" ]]; then echo "Bootstrapped Gradle:"; "$LOCAL_GRADLE" -v | head -8
else echo "Gradle: NOT FOUND"; fi
if [[ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]]; then
  echo "Gradle wrapper JAR: PRESENT"; sha256sum "$ROOT/gradle/wrapper/gradle-wrapper.jar"
else
  echo "Gradle wrapper JAR: NOT FOUND (system/bootstrap Gradle is an accepted certification path)"
fi
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" && -f "$ROOT/.toolchain/android-sdk/platforms/android-37/android.jar" ]]; then SDK="$ROOT/.toolchain/android-sdk"; fi
if [[ -n "$SDK" ]]; then echo "Android SDK root: $SDK"; else echo "Android SDK root: NOT SET"; fi
if [[ -n "$SDK" && -f "$SDK/platforms/android-37/android.jar" ]]; then echo "Android SDK 37: OK"; else echo "Android SDK 37: NOT FOUND"; fi
if [[ -n "$SDK" && -x "$SDK/build-tools/36.0.0/aapt2" ]]; then echo "Android Build Tools 36.0.0/aapt2: OK"; else echo "Android Build Tools 36.0.0/aapt2: NOT FOUND"; fi
printf '\nPortable verification:\n'
NOODLE_SKIP_SYNTAX_SMOKE=1 "$ROOT/tools/verify-stage10.sh"
printf '\nAndroid compile attempt:\n'
export ANDROID_SDK_ROOT="${SDK:-}" ANDROID_HOME="${SDK:-}"
if [[ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" && -n "$SDK" && -f "$SDK/platforms/android-37/android.jar" ]]; then
  (cd "$ROOT" && ./gradlew --no-daemon :app:assembleDebug)
elif command -v gradle >/dev/null && [[ -n "$SDK" && -f "$SDK/platforms/android-37/android.jar" ]]; then
  (cd "$ROOT" && gradle --no-daemon :app:assembleDebug)
elif [[ -x "$LOCAL_GRADLE" && -n "$SDK" && -f "$SDK/platforms/android-37/android.jar" ]]; then
  (cd "$ROOT" && "$LOCAL_GRADLE" --no-daemon :app:assembleDebug)
else
  echo "BLOCKED: Android compile prerequisites are not present. Run tools/bootstrap-android-toolchain.sh on a networked Linux host or use GitHub Actions."
  exit 3
fi
