# Noodle Mobile 0.7.0-alpha7 — отчёт крупного прохода

## Цель прохода

Этот проход выполнен как **build-first / stabilization** поверх `0.6.0-alpha6`. Главная задача — не добавлять очередной внешний экран, а устранить Android/Media3 риски, которые не выявляются plain-JVM harness'ами, зафиксировать актуальный build-контракт и приблизить проект к первому честно собранному APK.

Android build version: `0.7.0-alpha7` (`versionCode 70007`).  
SNP compatibility level: `1.9.0` (Desktop Stage 9), отдельно от номера мобильной alpha.

## 1. Исправлена thread-affinity гонка adaptive download

В `alpha6` пользователь мог запустить HLS/DASH offline из `PlayerScreen`, где enqueue выполняется в IO coroutine. `DownloadRepository.enqueueAdaptive()` затем напрямую менял `MediaDownloadRuntime.manager().maxParallelDownloads`.

`DownloadManager` в проекте намеренно main-looper-affined и защищён fail-fast проверкой. Поэтому первый adaptive enqueue с IO мог закончиться `DOWNLOAD_MANAGER_MAIN_THREAD_REQUIRED`.

В `alpha7` изменение `maxParallelDownloads` выполняется через:

```kotlin
withContext(Dispatchers.Main.immediate) {
    MediaDownloadRuntime.manager(app).maxParallelDownloads = maxParallel
}
```

Само скачивание при этом не переносится на UI thread: bridge нужен только для thread-affined API Media3.

## 2. Исправлен DownloadHelper renderer-capabilities контракт

В `alpha6` `DownloadHelper.Factory` получал `DataSource.Factory` и `TrackSelectionParameters`, но не `RenderersFactory`. Без renderer capabilities выбор video/audio/text мог быть неполным или не соответствовать ожидаемому устройству.

Теперь planner использует:

```kotlin
DownloadHelper.Factory()
    .setRenderersFactory(DefaultRenderersFactory(context))
    .setDataSourceFactory(...)
    .setTrackSelectionParameters(parameters)
    .create(item)
```

Подготовка helper начинается на `Dispatchers.Main.immediate`, после чего callback формирует `DownloadRequest` с Media3 stream keys и зашифрованной в `data` SNP network policy.

## 3. Убран deprecated TrackSelectionParameters constructor

`TrackSelectionParameters.Builder(context)` заменён на актуальный `TrackSelectionParameters.Builder()`.

Сохраняются:

- maximum video width/height;
- `setForceHighestSupportedBitrate(true)`;
- preferred audio language;
- возможность полностью выключить text tracks;
- preferred subtitle language.

Таким образом офлайн-задача продолжает хранить один лучший поддерживаемый вариант внутри выбранных пользователем ограничений, а не лишнюю adaptive ladder.

## 4. Media3 WorkManagerScheduler

`NoodleDownloadService.getScheduler()` больше не возвращает `null`.

Добавлено:

```kotlin
WorkManagerScheduler(applicationContext, "noodle_media_download_restart")
```

и зависимость:

```text
androidx.media3:media3-exoplayer-workmanager:1.11.0
```

В manifest добавлен Media3 `downloadService.action.RESTART` intent-filter. Сервис остаётся `exported=false`, использует `foregroundServiceType=dataSync`, а permissions `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` сохранены.

Практический результат: pending adaptive downloads имеют штатный механизм повторного запуска сервиса после восстановления требований, вместо зависимости только от повторного открытия приложения.

## 5. Build baseline приведён к актуальному AGP 9 контракту

Зафиксировано:

- AGP `9.3.1`;
- Gradle `9.5.1`;
- compileSdk / targetSdk `37`;
- Java source/target `17`;
- AGP built-in Kotlin (без `org.jetbrains.kotlin.android`);
- KGP runtime pin `2.4.10`;
- Compose compiler Gradle plugin `2.4.10`;
- KSP `2.3.11`;
- Core KTX `1.19.0`;
- Media3 `1.11.0`;
- Room `2.8.4`;
- DataStore `1.2.1`;
- WorkManager `2.11.2`.

KSP/Room schema export сохраняется через `room.schemaLocation`.

## 6. Gradle wrapper integrity

В `gradle-wrapper.properties` добавлена проверка официального Gradle 9.5.1 binary distribution:

```text
distributionSha256Sum=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f
```

Также создан `gradle/wrapper/WRAPPER_INTEGRITY.md` с ожидаемым SHA-256 wrapper JAR:

```text
497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
```

Wrapper JAR не создаётся искусственно. Если он восстанавливается на build-host, его нужно сверить с этим значением.

## 7. Новые instrumentation-ready проверки

Добавлен `MediaDownloadRuntimeInstrumentedTest`.

Он должен на реальном Android runtime проверить:

1. `MediaDownloadRuntime.manager(context)` создаётся/получается на main looper;
2. `manager.applicationLooper == Looper.getMainLooper()`;
3. прямой вызов `manager()` из `Dispatchers.IO` fail-fast завершается `IllegalStateException`;
4. error message содержит `DOWNLOAD_MANAGER_MAIN_THREAD_REQUIRED`.

Ранее подготовленные тесты Room, download-policy fail-closed и Compose MainActivity также сохранены.

Важно: instrumentation source подготовлен, но в текущем контейнере device/emulator и Android SDK отсутствуют, поэтому эти тесты не заявлены как выполненные.

## 8. Строгий build-preflight

`tools/build-check.sh` переписан как реальный gate.

Он проверяет:

- JDK;
- system Gradle;
- наличие `gradle-wrapper.jar`;
- checksum hint wrapper JAR;
- `ANDROID_SDK_ROOT` / `ANDROID_HOME`;
- `platforms/android-37/android.jar`;
- Build Tools / `aapt2`;
- Stage 7 portable/API verification;
- затем реально пытается перейти к `:app:assembleDebug`, если prerequisites присутствуют.

Текущий результат:

```text
JDK 21: PRESENT
System Gradle: NOT FOUND
Gradle wrapper JAR: NOT FOUND
Android SDK root: NOT SET
Android SDK 37: NOT FOUND
Android Build Tools/aapt2: NOT FOUND
Android compile attempt: BLOCKED
exit code: 3
```

Это намеренный отличимый статус: source-level verification успешна, Android compiler физически не запущен из-за отсутствия toolchain.

## 9. Regression suite

После всех изменений повторно проверены предыдущие уровни:

- portable core — **29 PASS**;
- engine/resolver — **21/21 PASS**;
- Stage 3 — **43/43 PASS**;
- Stage 4 lifecycle — **26/26 PASS**;
- Stage 4 architecture/static — **88/88 PASS**;
- Stage 5 executable — **26/26 PASS**;
- Stage 5 architecture/static — **78/78 PASS**;
- Stage 6 executable — **15/15 PASS**;
- Stage 6 architecture/static — **75/75 PASS**;
- Stage 7 executable build/API contract — **19/19 PASS**;
- Stage 7 architecture/static — **57/57 PASS**;
- Kotlin parser smoke — **PASS**;
- original bundled Desktop Stage 9 SNP — **5/5 valid**.

Всего в сводке: **477 успешных именованных проверок**, не считая parser smoke как числовой test case.

## 10. Что ещё нельзя считать подтверждённым

Пока отсутствует реальная Android toolchain execution, нельзя утверждать следующее:

- `:app:assembleDebug` PASS;
- KSP/Room code generation PASS;
- AAPT2 resource linking PASS;
- D8/R8 PASS;
- Android Lint PASS;
- on-device WebView sandbox instrumentation PASS;
- on-device Media3 DownloadService/WorkManagerScheduler PASS;
- HLS/DASH offline field test с физически отключённой сетью PASS;
- готовый APK.

Исходники подготовлены к этим проверкам, но они должны быть реально выполнены на Android build host.

## Следующий этап

Следующий проход должен начинаться не с новой функции, а с восстановления полноценной build среды: Gradle wrapper JAR + Gradle distribution/cache + Android SDK Platform 37 + Build Tools + Maven dependencies. После этого:

1. `:app:assembleDebug`;
2. исправление реальных compiler/KSP/AAPT2 ошибок;
3. `:app:lintDebug`;
4. unit tests;
5. emulator/device instrumentation;
6. HLS/DASH offline test без сети;
7. выпуск первого фактически собранного APK.
