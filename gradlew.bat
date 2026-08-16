@echo off
setlocal
set APP_HOME=%~dp0
if exist "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" (
  java -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
  exit /b %ERRORLEVEL%
)
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  echo gradle-wrapper.jar is not bundled in this generated source package; using system Gradle. 1>&2
  gradle %*
  exit /b %ERRORLEVEL%
)
echo Gradle is not installed and gradle-wrapper.jar is unavailable. Install Gradle 9.5.0+ or run: gradle wrapper --gradle-version 9.5.1 1>&2
exit /b 2
