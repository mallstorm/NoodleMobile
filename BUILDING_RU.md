# Сборка Noodle Mobile 0.10.0-alpha10

## Зафиксированный build contract

- JDK 17+; CI использует 21.
- Gradle 9.5.1.
- Android Gradle Plugin 9.3.1.
- compileSdk / targetSdk 37; minSdk 28.
- Android SDK Build Tools 36.0.0.
- AGP 9 built-in Kotlin; KGP/Compose compiler runtime 2.4.10.
- KSP 2.3.11.
- Compose BOM 2026.06.00.
- Media3 1.10.1 stable.
- Room 2.8.4; DataStore 1.2.1; WorkManager 2.11.2; WebKit 1.16.0.

## Автоматическое развёртывание Linux toolchain

`tools/bootstrap-android-toolchain.sh` скачивает официальный Gradle 9.5.1 и Android command-line tools build 15859902, проверяет SHA-256 **до распаковки**, затем устанавливает `platforms;android-37`, `build-tools;36.0.0` и platform-tools. Если сеть недоступна или checksum не совпадает, скрипт завершается ошибкой.

## Сертификационная сборка

```bash
./tools/verify-all-alpha9.sh
./tools/build-check.sh
./tools/ci-android-build.sh
```

`ci-android-build.sh` выполняет `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`, после чего `verify-apk.sh` проверяет identity/targetSdk/ZIP и SHA-256 APK.

В среде без Gradle/SDK `build-check.sh` возвращает `BLOCKED` (exit 3); это не считается успешной Android-компиляцией.
