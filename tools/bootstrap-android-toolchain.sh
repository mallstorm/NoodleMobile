#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS_ROOT="${NOODLE_TOOLCHAIN_ROOT:-$ROOT/.toolchain}"
SDK="$TOOLS_ROOT/android-sdk"
DL="$TOOLS_ROOT/downloads"
GRADLE_VERSION=9.5.1
GRADLE_SHA256=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f
GRADLE_URL=https://services.gradle.org/distributions/gradle-9.5.1-bin.zip
CMDLINE_BUILD=15859902
CMDLINE_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
CMDLINE_URL=https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
mkdir -p "$DL" "$SDK"
fetch() {
  local url="$1" out="$2" sha="$3"
  if [[ ! -f "$out" ]]; then
    if command -v curl >/dev/null 2>&1; then curl --fail --location --retry 3 --output "$out" "$url"
    elif command -v wget >/dev/null 2>&1; then wget -O "$out" "$url"
    else echo "ERROR: curl/wget required to bootstrap toolchain" >&2; exit 10; fi
  fi
  echo "$sha  $out" | sha256sum -c -
}
fetch "$GRADLE_URL" "$DL/gradle-$GRADLE_VERSION-bin.zip" "$GRADLE_SHA256"
fetch "$CMDLINE_URL" "$DL/commandlinetools-linux-${CMDLINE_BUILD}_latest.zip" "$CMDLINE_SHA256"
rm -rf "$TOOLS_ROOT/gradle-$GRADLE_VERSION" "$SDK/cmdline-tools/latest" "$TOOLS_ROOT/cmdline-unpack"
unzip -q "$DL/gradle-$GRADLE_VERSION-bin.zip" -d "$TOOLS_ROOT"
mkdir -p "$TOOLS_ROOT/cmdline-unpack" "$SDK/cmdline-tools"
unzip -q "$DL/commandlinetools-linux-${CMDLINE_BUILD}_latest.zip" -d "$TOOLS_ROOT/cmdline-unpack"
mv "$TOOLS_ROOT/cmdline-unpack/cmdline-tools" "$SDK/cmdline-tools/latest"
rm -rf "$TOOLS_ROOT/cmdline-unpack"
export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "platforms;android-37" "build-tools;36.0.0"
[[ -f "$SDK/platforms/android-37/android.jar" ]] || { echo "ERROR: android-37 installation failed" >&2; exit 11; }
[[ -x "$SDK/build-tools/36.0.0/aapt2" ]] || { echo "ERROR: build-tools 36.0.0 installation failed" >&2; exit 12; }
cat > "$ROOT/local.properties" <<EOF
sdk.dir=$SDK
EOF
printf 'GRADLE_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$TOOLS_ROOT/gradle-$GRADLE_VERSION" "$SDK"
echo "BOOTSTRAP=PASS"
