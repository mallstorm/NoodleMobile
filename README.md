# NoodleMobile

**NoodleMobile** — нативное Android-приложение для поиска, разрешения и воспроизведения медиа через набор изолированных SNP-адаптеров и runtime-движков. Проект включает защищённый сетевой слой, многоисточниковый поиск, Resolver Pipeline, Media3 playback/offline, локальное хранилище, управление источниками, диагностику и автоматическую Android CI-сертификацию.

## Текущий baseline

- App version: `0.10.0-alpha10`
- versionCode: `100010`
- applicationId: `pro.noodle.mobile`
- minSdk: `28`
- targetSdk / compileSdk: `37`
- Android Platform: `37.0`
- Android Build Tools: `36.0.0`
- JDK: `21`
- Java bytecode target: `17`
- Gradle: `9.5.1`
- Android Gradle Plugin: `9.3.1`
- Kotlin runtime/compiler line: `2.4.10`
- KSP: `2.3.11`
- Compose BOM: `2026.06.00`
- Media3: `1.10.1`
- Room: `2.8.4`
- DataStore: `1.2.1`
- WorkManager: `2.11.2`
- WebKit: `1.16.0`
- SNP compatibility contract: `1.9.0`

Фактическая текущая линия — **`0.10.0-alpha10 + MultiSource Hotfix 2`**.

## Как устроен текущий source baseline

В `main` хранится проверенный базовый архив:

`NoodleMobile_0.10.0-alpha10_SOURCE.zip`

SHA-256:

`cfe4385b17bb2763eb5947c955a311262c7dd9443a3e90c0a28397bc2a943cd2`

Перед Android-сборкой CI распаковывает базовый source snapshot и последовательно применяет актуальные source overlays:

1. `ci/overlay.py`
2. `ci/runtime_hotfix.py`
3. `ci/multisource_hotfix2.py`

Поэтому актуальным исходным состоянием проекта является **base ZIP + эти три overlay**, а не исходный ZIP отдельно.

## Основные возможности

- многоисточниковый поиск по включённым SNP-источникам;
- 15 runtime-движков и единый Resolver Pipeline;
- поддержка HTML5, Video.js, HLS, DASH, JW Player, iframe-chain, JSON/API, WordPress REST, oEmbed и специализированных legacy/KVS источников;
- безопасная нормализация URL и source-scoped host policy;
- защищённый HTTP-клиент с Safe DNS, redirect/host validation и контролем cookies;
- sandboxed WebView fallback без бесконтрольного доступа к file/content URI;
- Media3 playback;
- выбор качества, аудиодорожек и субтитров;
- Picture-in-Picture;
- сохранение позиции просмотра;
- progressive download и adaptive HLS/DASH offline;
- WorkManager / foreground download lifecycle;
- Room + DataStore для локального состояния;
- Favorites и Downloads;
- Source Center, Source Health, Source Diagnostics;
- импорт, обновление, rollback и восстановление SNP-адаптеров;
- Marketplace, Trusted Keys и Adapter Studio;
- проверка целостности SNP-пакетов и защита ZIP import от traversal/duplicate paths.

## MultiSource Hotfix 2

Последний runtime/source hotfix улучшает работу живых источников без ослабления общей security-модели:

- добавлены только source-scoped CDN / migration hosts, подтверждённые live-probe;
- исправлен KVS mixed-layout поиск `/video/*` и `/videos/*`;
- обновлён живой search endpoint для Video-laba;
- поиск пробует несколько безопасных canonical/common endpoint-вариантов;
- относительные media/preview URLs нормализуются относительно фактического final origin;
- улучшена загрузка превью с теми же SNP host-policy и Safe DNS ограничениями;
- добавлен локальный thumbnail cache;
- карточки поиска показывают реальные preview и полезные metadata вместо сырого watch URL;
- исправлена обработка playback-host миграций/CDN;
- ошибки blocked-host теперь содержат диагностируемые host details.

Ad/VAST/analytics hosts намеренно не добавляются в доверенную область только ради успешного воспроизведения.

## Compiler / Android fixes поверх base ZIP

`ci/overlay.py` содержит исправления, найденные уже реальным Android compiler/lint pipeline:

- Android `37.0` + `compileSdkMinor = 0`;
- исправление Material3 `TopAppBar` opt-in;
- явный `return` в suspend search path;
- корректный Video.js `data-setup` parsing;
- WorkManager foreground `dataSync` manifest merge;
- Media3 `UnstableApi` lint opt-in;
- исправленные APK/androidTest APK certification scripts.

## Android CI

Workflow: `.github/workflows/android-ci.yml`

Pipeline:

1. проверяет SHA-256 базового source ZIP;
2. проверяет ZIP integrity и распаковывает проект;
3. применяет три актуальных source overlay;
4. устанавливает JDK 21;
5. устанавливает Android Platform `37.0` и Build Tools `36.0.0`;
6. использует Gradle `9.5.1`;
7. запускает unit tests / lint / Android build / APK certification;
8. собирает основной debug APK и androidTest APK;
9. публикует diagnostics даже при ошибке;
10. публикует certified APK artifacts только при полном success.

Последний подтверждённый build baseline:

- Commit: `e79161d274208aa22f4aee2735cb24619989cdb3`
- Workflow: **Noodle Mobile Android CI**
- Run: `31627393933`
- Result: **SUCCESS**
- Certified artifact: `NoodleMobile-0.10.0-alpha10-MultiSourceHotfix2-API37`

## Проверки

До первого реального Android build исходная Stage 10 линия прошла переносимую regression matrix:

- Core: `29/29`
- Stage 2: `21/21`
- Stage 3: `43/43`
- Stage 4: `26/26 + 88/88`
- Stage 5: `26/26 + 78/78`
- Stage 6: `15/15 + 75/75`
- Stage 7: `19/19 + 57/57`
- Stage 8: `31/31 + 62/62`
- Stage 9: `25/25 + 29/29`
- Stage 10: `27/27 + 28/28`

Итого: **679/679 named checks PASS** для базовой alpha10-линии, после чего GitHub CI выявил и исправил реальные compiler/lint/runtime проблемы и успешно собрал MultiSource Hotfix 2.

## Безопасность

NoodleMobile придерживается source-scoped trust model:

- cleartext traffic не включается глобально;
- разрешённые hosts задаются отдельно для SNP-источника;
- redirects повторно проходят security validation;
- приватные / loopback / небезопасные адреса не должны становиться обходным путём;
- cookies изолируются по источнику;
- WebView permissions не выдаются автоматически;
- TLS bypass не используется;
- adapter packages проверяются по manifest/integrity metadata;
- обновления адаптеров проходят compatibility gate и поддерживают rollback.

## Разработка

Для дальнейшей разработки использовать **текущий `main`**, а не чистый архив `NoodleMobile_0.10.0-alpha10_SOURCE.zip` без overlays.

При изменении кода в base snapshot или overlay необходимо сохранять воспроизводимость CI: один и тот же base ZIP + зафиксированные overlays должны приводить к тому же effective source tree перед Gradle build.
