#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  echo "gradle-wrapper.jar is not bundled in this generated source package; using system Gradle." >&2
  exec gradle "$@"
fi
echo "Gradle is not installed and gradle-wrapper.jar is unavailable. Install Gradle 9.5.0+ or regenerate the wrapper with: gradle wrapper --gradle-version 9.5.1" >&2
exit 2
