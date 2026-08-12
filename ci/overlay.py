#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "work/NoodleMobile_0.10.0-alpha10_SOURCE")
if not root.is_dir():
    raise SystemExit(f"project root missing: {root}")

# Android SDK 37 is published as platforms;android-37.0. AGP represents this as compileSdk 37 + minor 0.
app = root / "app/build.gradle.kts"
text = app.read_text(encoding="utf-8")
if "compileSdkMinor = 0" not in text:
    anchor = "    compileSdk = 37\n"
    if anchor not in text:
        raise SystemExit("compileSdk 37 anchor missing")
    text = text.replace(anchor, anchor + "    compileSdkMinor = 0\n", 1)
app.write_text(text, encoding="utf-8")

ci = root / "tools/ci-android-build.sh"
text = ci.read_text(encoding="utf-8")
text = text.replace('platforms;android-37', 'platforms;android-37.0')
text = text.replace('platforms/android-37/android.jar', 'platforms/android-37.0/android.jar')
ci.write_text(text, encoding="utf-8")

# Real Kotlin compiler fix #1: Material3 TopAppBar experimental annotation.
main = root / "app/src/main/java/pro/noodle/mobile/MainActivity.kt"
text = main.read_text(encoding="utf-8")
old = "@Composable private fun NoodleApp(settingsVm:SettingsViewModel){"
if old in text:
    text = text.replace(old, "@OptIn(ExperimentalMaterial3Api::class)\n" + old, 1)
main.write_text(text, encoding="utf-8")

# Real Kotlin compiler fix #2: explicit return from suspend searchOne().
search = root / "app/src/main/java/pro/noodle/mobile/core/search/BundledSearchRepository.kt"
text = search.read_text(encoding="utf-8")
old = "        items to null\n    }\n\n    private fun templateSearchUrl"
if old in text:
    text = text.replace(old, "        return items to null\n    }\n\n    private fun templateSearchUrl", 1)
search.write_text(text, encoding="utf-8")

# Real unit-test fix: valid Video.js may use data-setup without class=video-js.
videojs = root / "app/src/main/java/pro/noodle/mobile/core/engine/impl/VideoJsEngine.kt"
lines = videojs.read_text(encoding="utf-8").splitlines()
replacement = '        val videoTag=Regex("<video\\\\b[^>]*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).findAll(text).map{it.value}.firstOrNull{tag->EngineParsing.attr(tag,"data-setup").isNotBlank()||EngineParsing.attr(tag,"class").contains("video-js",true)}.orEmpty()'
for i, line in enumerate(lines):
    if "val videoTag=Regex(" in line:
        lines[i] = replacement
        break
else:
    raise SystemExit("VideoJsEngine videoTag anchor missing")
videojs.write_text("\n".join(lines) + "\n", encoding="utf-8")

# Lint fixes: WorkManager dataSync foreground service + Media3 UnstableApi project opt-in.
manifest = root / "app/src/main/AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
if 'xmlns:tools="http://schemas.android.com/tools"' not in text:
    text = text.replace(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:tools="http://schemas.android.com/tools">',
        1,
    )
if 'android:name="androidx.work.impl.foreground.SystemForegroundService"' not in text:
    anchor = '        <service\n            android:name=".core.download.NoodleDownloadService"'
    if anchor not in text:
        raise SystemExit("NoodleDownloadService manifest anchor missing")
    wm = (
        '        <service\n'
        '            android:name="androidx.work.impl.foreground.SystemForegroundService"\n'
        '            android:foregroundServiceType="dataSync"\n'
        '            tools:node="merge" />\n\n'
    )
    text = text.replace(anchor, wm + anchor, 1)
manifest.write_text(text, encoding="utf-8")

(root / "app/lint.xml").write_text(
    '''<?xml version="1.0" encoding="utf-8"?>
<lint>
    <issue id="UnsafeOptInUsageError">
        <option name="opt-in" value="androidx.media3.common.util.UnstableApi" />
    </issue>
</lint>
''',
    encoding="utf-8",
)

# Correct APK certification. aapt2 36 emits minSdkVersion, not sdkVersion, for this APK.
(root / "tools/verify-apk.sh").write_text(
    r'''#!/usr/bin/env bash
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
printf '%s\n' "$BADGING" | grep -F "package: name='pro.noodle.mobile' versionCode='100010' versionName='0.10.0-alpha10'" >/dev/null || { echo "ERROR: APK identity mismatch" >&2; exit 5; }
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
''', encoding="utf-8")

(root / "tools/verify-test-apk.sh").write_text(
    r'''#!/usr/bin/env bash
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
''', encoding="utf-8")

for p in (root / "tools/verify-apk.sh", root / "tools/verify-test-apk.sh", root / "tools/ci-android-build.sh"):
    p.chmod(p.stat().st_mode | 0o111)

print("overlay applied: Android 37.0, compiler fixes, parser fix, lint fixes, APK verifiers")
