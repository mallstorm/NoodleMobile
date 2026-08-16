# Noodle Mobile 0.6.0-alpha6 — отчёт крупного прохода

Дата прохода: 2026-08-11  
Исходная спецификация: Noodle Desktop `1.9.0-alpha.10`, Stage 9 Schema v3 Repair  
Android build version: `0.6.0-alpha6` (`versionCode 60006`)  
SNP compatibility level: `1.9.0`

## 1. Цель прохода

Шестой крупный проход переводит adaptive offline из базового Media3 backend alpha5 в пользовательскую функцию с явным выбором дорожек и отдельной библиотекой скачанного контента. Одновременно выполнена Android-specific стабилизация кода и подготовлена instrumentation test-база.

Основные задачи:

1. перейти на актуальную стабильную Media3 1.11.0;
2. планировать HLS/DASH offline через `DownloadHelper`, а не скачивать произвольный набор дорожек;
3. сохранять выбор качества/audio/subtitles в Room и восстанавливать его после перезапуска;
4. создать отдельную офлайн-библиотеку завершённых progressive/HLS/DASH загрузок;
5. гарантировать настоящий offline playback без скрытого выхода в сеть;
6. проверить thread-affinity `DownloadManager`;
7. добавить instrumentation-ready Room/policy/Compose tests;
8. повторно проверить прежние resolver/SNP/Marketplace контракты;
9. снова попытаться выполнить настоящий Android `assembleDebug` и честно зафиксировать состояние toolchain.

## 2. Media3 1.11.0 и DownloadHelper

Все Media3 зависимости переведены на `1.11.0`.

Adaptive HLS/DASH больше не создаёт сырой `DownloadRequest`. Добавлен `AdaptiveDownloadPlanner`:

- создаёт `MediaItem` с HLS/DASH MIME;
- получает manifest через SNP policy-aware DataSource;
- применяет `TrackSelectionParameters`;
- ограничивает максимальную ширину/высоту выбранным качеством;
- выбирает preferred audio language;
- позволяет выключить text tracks либо выбрать preferred subtitle language;
- использует `setForceHighestSupportedBitrate(true)`, поэтому внутри выбранных ограничений offline сохраняется один лучший поддерживаемый video/audio representation, а не лишняя лестница качеств;
- после `DownloadHelper.prepare()` создаёт `DownloadRequest` через `getDownloadRequest(id, data)`;
- в `DownloadRequest.data` сохраняется сериализованная SNP network policy;
- `DownloadHelper` освобождается при success/error/cancel.

Deprecated `DownloadHelper.forMediaItem()` не используется.

## 3. Persistent metadata для adaptive offline

Room schema повышена до v3.

В `DownloadEntity` добавлены:

- `thumbnailUrl`;
- `protocol`;
- `videoSelectionLabel`;
- `videoMaxWidth`;
- `videoMaxHeight`;
- `audioSelectionLanguage`;
- `textSelectionLanguage`;
- `includeSubtitles`.

Добавлена явная `MIGRATION_2_3`; destructive migration по-прежнему отсутствует.

Это позволяет после убийства процесса восстановить не только URL задания, но и пользовательский offline selection profile.

## 4. Надёжный resume adaptive download

При возобновлении failed HLS/DASH задания сначала читается исходный `DownloadRequest` из Media3 `DownloadIndex`. Поэтому сохраняются реальные `streamKeys`, сформированные `DownloadHelper`.

Если запись Media3 index отсутствует, выполняется deterministic re-plan из Room metadata: quality bounds, audio language, subtitle policy и SNP network policy.

Таким образом resume больше не превращает track-selective download в all-tracks download.

## 5. Офлайн-библиотека

В `DownloadDao` добавлен поток `observeCompleted()`.

Экран «Загрузки» теперь имеет два режима:

- **Очередь** — текущие/остановленные/ошибочные задания;
- **Библиотека** — только завершённые загрузки.

Добавлены `OfflineMediaRepository` и `OfflinePlaybackSpec`.

Progressive media открывается из сохранённого SAF/local URI.

Adaptive HLS/DASH восстанавливает сохранённый Media3 `DownloadRequest` из `DownloadIndex`.

Удаление элемента библиотеки также удаляет соответствующие Media3 cache данные либо progressive local state через существующий download backend.

## 6. Строгий offline playback

Для adaptive media используется:

`DownloadHelper.createMediaSource(downloadRequest, dataSourceFactory)`.

DataSource создаётся через общий `SimpleCache`, однако **upstream factory = null**. Поэтому если manifest/segment отсутствует в cache, playback завершается ошибкой вместо незаметной сетевой подкачки.

В `OfflinePlayerScreen` добавлен `Player.Listener`: пользователь получает отдельное сообщение о неполном/повреждённом офлайн-кэше либо ошибке локального progressive файла.

Поддержан PiP и корректное освобождение ExoPlayer.

## 7. Исправление thread-affinity Media3

Во время ревизии найден Android-specific риск, не обнаруживаемый portable harness: `DownloadManager` должен использоваться с потока, на котором он создан.

Исправлено:

- первый `MediaDownloadRuntime.Holder` создаётся только на main looper;
- `MediaDownloadRuntime.manager()` имеет main-thread guard;
- `requestFor()` сначала гарантирует инициализацию runtime на `Dispatchers.Main.immediate`, затем выполняет блокирующее чтение `DownloadIndex` на `Dispatchers.IO` без обращения к `DownloadManager`.

Это устраняет сценарий, когда offline library первой создаёт manager из IO coroutine, а затем `DownloadService` пытается использовать его с main thread.

## 8. Пользовательский выбор offline дорожек

В PlayerScreen для adaptive stream добавлен отдельный диалог загрузки:

- качество: Auto/варианты resolver;
- audio: Auto/доступные языки;
- subtitles: «Без субтитров», Auto либо конкретная дорожка.

Выбранные параметры сохраняются вместе с download entity.

Для качества вариант интерпретируется как верхняя граница dimensions; внутри этой границы planner выбирает один лучший поддерживаемый representation. Это устойчивее, чем хранить индекс конкретного manifest representation, который может измениться между запросами.

## 9. Instrumentation-ready tests

Подключены AndroidX Test dependencies и `AndroidJUnitRunner`.

Добавлены:

### `DatabaseInstrumentedTest`

- in-memory Room;
- запись adaptive download metadata;
- проверка выборки completed library.

### `DownloadPolicyInstrumentedTest`

- encode/decode network policy;
- corrupt payload должен декодироваться в `null`/fail-closed;
- normalization adaptive selection.

### `MainActivitySmokeTest`

- запуск MainActivity через Compose test rule;
- проверка основных элементов shell.

Эти тесты находятся в `androidTest` и требуют настоящего Android SDK/device/emulator для запуска; в текущем контейнере они не объявляются выполненными.

## 10. Build stack

Проект зафиксирован на:

- AGP `9.3.1`;
- Gradle wrapper target `9.5.1`;
- compileSdk/targetSdk `37`;
- Java bytecode `17`;
- Media3 `1.11.0`;
- Room `2.8.4`;
- DataStore `1.2.1`;
- WorkManager `2.11.2`;
- WebKit `1.16.0`;
- AndroidX Test Core `1.7.0`;
- AndroidX Test Runner `1.7.0`;
- Espresso `3.7.0`;
- ext.junit `1.3.0`.

## 11. Результаты проверки

Фактически выполнены в текущем runtime:

- portable core: **29 PASS**;
- bundled SNP: **5/5 PASS**;
- Stage 2 engine/resolver: **21/21 PASS**;
- Stage 3 Android/static regression: **43/43 PASS**;
- Stage 4 SNP lifecycle: **26/26 PASS**;
- Stage 4 Android/static regression: **88/88 PASS**;
- Stage 5 Marketplace/Studio/Diagnostics: **26/26 PASS**;
- Stage 5 Android/static regression: **78/78 PASS**;
- Stage 6 executable selection harness: **15/15 PASS**;
- Stage 6 static/architecture contracts: **75/75 PASS**;
- Kotlin parser syntax smoke: **PASS**.

Старые lifecycle/Marketplace harness'ы после alpha6 изменений повторно запущены и остаются зелёными.

## 12. Реальная Android-сборка

`:app:assembleDebug` был запущен как build probe, но не смог пройти даже Gradle bootstrap:

```text
Gradle is not installed and gradle-wrapper.jar is unavailable.
```

В mounted filesystem текущего runtime также не найден `android.jar`.

Следовательно:

- Android compiler/KSP/AAPT2/D8 на alpha6 здесь **не запускались**;
- instrumentation tests здесь **не запускались**;
- APK **не создан**;
- synthetic/fake APK не создавался.

В File Library сохранились документы, подтверждающие существование ранее подготовленной APKForge offline Linux x86_64 среды, но сами пять ZIP-томов с Gradle/SDK сейчас в runtime не смонтированы. Поэтому использовать историческую среду для этой сборки технически нельзя.

## 13. Следующий крупный проход

После появления реального Android toolchain приоритет должен быть build-first:

1. `assembleDebug`;
2. исправление всех Kotlin/AndroidX/Media3 API compile errors;
3. KSP/Room schema generation и migration test;
4. lint;
5. emulator/device instrumentation;
6. HLS/DASH offline field test;
7. network-disconnected offline playback test;
8. foreground download/notification behavior на Android 13–17;
9. debug APK;
10. затем UI polish, accessibility и release stabilization.
