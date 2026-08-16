# NoodleMobile

**NoodleMobile** — нативное Android-приложение для многоисточникового поиска, разрешения и воспроизведения медиа через изолированные SNP-адаптеры и runtime-движки.

## Текущий baseline

- Версия: `0.10.0-alpha10`
- versionCode: `100010`
- applicationId: `pro.noodle.mobile`
- Текущая линия: **MultiSource Hotfix 2**
- minSdk: `28`
- compileSdk / targetSdk: `37`
- Android Platform: `37.0`
- Build Tools: `36.0.0`
- JDK: `21`
- Java bytecode target: `17`
- Gradle: `9.5.1`
- Android Gradle Plugin: `9.3.1`
- Kotlin: `2.4.10`
- KSP: `2.3.11`
- Compose BOM: `2026.06.00`
- Media3: `1.10.1`
- Room: `2.8.4`
- DataStore: `1.2.1`
- WorkManager: `2.11.2`
- WebKit: `1.16.0`
- SNP compatibility contract: `1.9.0`

## Структура репозитория

`main` содержит обычное развёрнутое Android source tree. Старый source ZIP и временные overlay/hotfix-скрипты больше не являются частью рабочего проекта: все сертифицированные изменения интегрированы непосредственно в исходники.

```text
app/                         Android application module
gradle/                      Gradle wrapper metadata
tools/                       build/verification utilities
docs/                        актуальная проектная документация
ci/                          live compatibility probes
.github/workflows/           GitHub Actions
build.gradle.kts             root Gradle build
settings.gradle.kts          Gradle settings
gradle.properties            Gradle configuration
```

## Основные возможности

- многоисточниковый поиск по включённым SNP-источникам;
- 15 runtime-движков и единый Resolver Pipeline;
- HTML5, Video.js, HLS, DASH, JW Player, iframe-chain, JSON/API, WordPress REST, oEmbed, legacy/KVS engines;
- source-scoped host policy, Safe DNS и redirect validation;
- sandboxed WebView fallback;
- Media3 playback, качество, аудиодорожки, субтитры и Picture-in-Picture;
- сохранение позиции просмотра;
- progressive download и adaptive HLS/DASH offline;
- Room + DataStore, Favorites и Downloads;
- Source Center, Source Health и Diagnostics;
- импорт, обновление, rollback и repair SNP-адаптеров;
- Marketplace, Trusted Keys и Adapter Studio;
- manifest/integrity verification и защита ZIP-import от traversal/duplicate paths.

## MultiSource Hotfix 2

В текущие исходники уже интегрированы:

- source-scoped CDN/migration hosts, подтверждённые live-probe;
- KVS mixed-layout parsing для `/video/*` и `/videos/*`;
- актуальный Video-laba search endpoint;
- безопасные search endpoint fallbacks;
- нормализация relative URL относительно final origin;
- secure thumbnail loading через тот же SNP/Safe DNS policy;
- thumbnail cache и реальные preview в карточках поиска;
- улучшенная диагностика blocked playback hosts;
- реальные Android compiler/lint fixes;
- Android `37.0` / `compileSdkMinor = 0`;
- исправленные APK и androidTest APK certification scripts.

## Сборка

GitHub Actions собирает проект непосредственно из файлов `main`: unit tests → lint → debug APK → APK certification → androidTest APK → androidTest certification. При наличии устройства/emulator также запускаются instrumentation tests.

Последний baseline перед развёртыванием дерева был успешно сертифицирован как `NoodleMobile-0.10.0-alpha10-MultiSourceHotfix2-API37`. После миграции тот же build contract применяется напрямую к исходникам в корне репозитория.

## Безопасность

- глобальный cleartext не включается;
- разрешённые hosts изолированы по SNP-источникам;
- redirects повторно проходят validation;
- cookies разделены по источникам;
- WebView permissions не выдаются автоматически;
- TLS bypass не используется;
- adapter packages проходят manifest/integrity и compatibility checks;
- обновления поддерживают snapshot/rollback.

## Разработка

Все дальнейшие изменения вносятся непосредственно в `app/`, `tools/`, `docs/` и остальные файлы `main`. Новые исправления должны быть обычными source-коммитами, а не overlays поверх ZIP.
